package test.vram.tweak.eviction;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.opengl.GL11C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.diagnostic.VramModLog;

/**
 * Manages texture eviction from VRAM to system RAM.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Backup</b> — When a texture is created via {@code _texImage2D(ByteBuffer)},
 *       pixel data is copied to a byte[] in the {@link #backupById} map.</li>
 *   <li><b>Evict</b> — When VRAM usage exceeds threshold, the LRU texture with a
 *       backup is replaced by a 1×1 placeholder. Its ID goes into {@link #evictedSet}.</li>
 *   <li><b>Restore</b> — When a evicted texture is bound via {@code _bindTexture},
 *       pixel data is re-uploaded from the backup and its ID leaves the evicted set.</li>
 *   <li><b>Free</b> — When a texture is deleted, both backup and eviction status
 *       are cleaned up.</li>
 * </ol>
 */
public final class VramEvictionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/evict");

    private static final VramEvictionManager INSTANCE = new VramEvictionManager();

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong totalBytesEvicted = new AtomicLong(0);
    private final AtomicLong totalBytesRestored = new AtomicLong(0);
    private final AtomicInteger evictCount = new AtomicInteger(0);
    private final AtomicInteger restoreCount = new AtomicInteger(0);

    // Pixel data backups: GL object ID → pixel data (all textures, whether evicted or not)
    private final ConcurrentHashMap<Integer, VramEvictionRecord> backupById = new ConcurrentHashMap<>();

    // Set of texture IDs that have been evicted (replaced with 1×1 placeholder in VRAM)
    private final ConcurrentHashMap.KeySetView<Integer, Boolean> evictedSet =
            ConcurrentHashMap.newKeySet();

    // LRU tracking: GL object ID → last access time (ms)
    private final LinkedHashMap<Integer, Long> lruTracker = new LinkedHashMap<>(256, 0.75f, true);

    // Cooldown to prevent rapid evict/restore cycles
    private static final long EVICTION_COOLDOWN_MS = 5000;
    private long lastEvictionTime = 0;
    private int evictionCheckInterval = 0;

    // Minimum texture size (bytes) to bother backing up
    private static final long MIN_BACKUP_BYTES = 256 * 1024; // 256 KB

    private VramEvictionManager() {}

    public static VramEvictionManager getInstance() { return INSTANCE; }

    // ---- Activation ----

    public void activate() {
        active.set(true);
        VramModLog.info("[Eviction] 纹理驱逐已激活！VRAM 紧张时将自动卸载不常用纹理到系统 RAM。");
    }

    public void deactivate() {
        active.set(false);
        restoreAll();
        backupById.clear();
        evictedSet.clear();
        synchronized (lruTracker) { lruTracker.clear(); }
        VramModLog.info("[Eviction] 纹理驱逐已停用，已恢复所有纹理。");
    }

    public boolean isActive() { return active.get(); }

    // ---- Backup (called from _texImage2D hook) ----

    /**
     * Back up pixel data for a texture allocation. Called from the
     * {@code _texImage2D(ByteBuffer)} mixin hook.
     * Skips tiny textures ({@literal <64px} or {@literal <256KB}).
     */
    public void backupTexture(int glObjectId, int width, int height, int depth,
                               int mipLevels, int internalformat,
                               int format, int type,
                               ByteBuffer pixels, String label) {
        if (!active.get() || pixels == null) return;
        if (width < 64 || height < 64) return;

        long bytes = estimateBytes(width, height, depth, mipLevels, internalformat);
        if (bytes < MIN_BACKUP_BYTES) return;

        // Copy pixel data to byte[]
        int pos = pixels.position();
        int remaining = pixels.remaining();
        byte[] data = new byte[remaining];
        pixels.get(data);
        pixels.position(pos);

        VramEvictionRecord record = new VramEvictionRecord(
                glObjectId, data, width, height, depth,
                mipLevels, internalformat, format, type,
                bytes, label);

        backupById.put(glObjectId, record);
        touchLRU(glObjectId);
    }

    /**
     * Remove a texture from all tracking (when genuinely deleted).
     * Called from {@code _deleteTexture} mixin hook.
     */
    public void forgetTexture(int glObjectId) {
        backupById.remove(glObjectId);
        evictedSet.remove(glObjectId);
        synchronized (lruTracker) { lruTracker.remove(glObjectId); }
    }

    // ---- LRU tracking (called from _bindTexture hook) ----

    /**
     * Mark a texture as recently used. If the texture is evicted in VRAM,
     * restore it from the backup before rendering.
     */
    public void touchTexture(int glObjectId) {
        if (!active.get()) return;

        if (evictedSet.contains(glObjectId)) {
            VramEvictionRecord rec = backupById.get(glObjectId);
            if (rec != null) {
                restoreTexture(glObjectId, rec);
            }
        }
        touchLRU(glObjectId);
    }

    private void touchLRU(int glObjectId) {
        synchronized (lruTracker) {
            lruTracker.put(glObjectId, System.currentTimeMillis());
        }
    }

    // ---- Eviction (called from onFrame) ----

    /** Called every frame. Checks VRAM usage and evicts if needed. */
    public void onFrame() {
        if (!active.get()) return;

        // Check every 20 ticks (~1 second)
        evictionCheckInterval++;
        if (evictionCheckInterval < 20) return;
        evictionCheckInterval = 0;

        var cfg = VRAMConfig.getInstance().experimental;
        int thresholdPercent = cfg.evictionThresholdPercent;
        long totalMB = MetricsEngine.getVramTotalMB();
        long usedMB = MetricsEngine.getVramUsedMB();
        if (totalMB <= 0 || usedMB <= 0) return;

        int usedPct = (int) (usedMB * 100 / totalMB);
        if (usedPct < thresholdPercent) return;

        long now = System.currentTimeMillis();
        if (now - lastEvictionTime < EVICTION_COOLDOWN_MS) return;

        evictOne();
    }

    private void evictOne() {
        // Find the LRU texture that has a backup and is NOT already evicted
        Integer lruId = null;
        synchronized (lruTracker) {
            if (lruTracker.isEmpty()) return;
            for (Integer id : lruTracker.keySet()) {
                if (!evictedSet.contains(id) && backupById.containsKey(id)) {
                    lruId = id;
                    break;
                }
            }
        }
        if (lruId == null) return;

        VramEvictionRecord rec = backupById.get(lruId);
        if (rec == null) return;

        // Replace the texture with a 1×1 placeholder
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lruId);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, rec.getInternalformat(),
                    1, 1, 0, rec.getFormat(), rec.getType(),
                    (ByteBuffer) null);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);

            evictedSet.add(lruId);
            lastEvictionTime = System.currentTimeMillis();
            totalBytesEvicted.addAndGet(rec.getEstimatedBytesSaved());
            evictCount.incrementAndGet();

            VramModLog.debug("[Eviction] EVICT tex=" + lruId
                    + " " + rec.getWidth() + "x" + rec.getHeight()
                    + " " + (rec.getEstimatedBytesSaved() / (1024 * 1024)) + "MB"
                    + " label=" + rec.getLabel());
            if (evictCount.get() <= 5) {
                LOGGER.info("纹理驱逐: tex={} {}×{} -> 1×1 placeholder (节省 ~{}MB)",
                        lruId, rec.getWidth(), rec.getHeight(),
                        rec.getEstimatedBytesSaved() / (1024 * 1024));
            }
        } catch (Exception e) {
            LOGGER.warn("纹理驱逐失败: tex={}", lruId, e);
        }
    }

    // ---- Restoration ----

    private void restoreTexture(int glObjectId, VramEvictionRecord rec) {
        try {
            ByteBuffer data = ByteBuffer.wrap(rec.getPixelData());
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glObjectId);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, rec.getInternalformat(),
                    rec.getWidth(), rec.getHeight(), 0,
                    rec.getFormat(), rec.getType(), data);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);

            evictedSet.remove(glObjectId);
            rec.markRestored();
            totalBytesRestored.addAndGet(rec.getEstimatedBytesSaved());
            restoreCount.incrementAndGet();
            lastEvictionTime = 0;

            VramModLog.debug("[Eviction] RESTORE tex=" + glObjectId
                    + " " + rec.getWidth() + "x" + rec.getHeight()
                    + " " + (rec.getEstimatedBytesSaved() / (1024 * 1024)) + "MB");
            if (restoreCount.get() <= 5) {
                LOGGER.info("纹理恢复: tex={} {}×{} -> 已重新上传 ({}MB)",
                        glObjectId, rec.getWidth(), rec.getHeight(),
                        rec.getEstimatedBytesSaved() / (1024 * 1024));
            }
        } catch (Exception e) {
            LOGGER.warn("纹理恢复失败: tex={}", glObjectId, e);
        }
    }

    private void restoreAll() {
        for (Integer id : evictedSet) {
            VramEvictionRecord rec = backupById.get(id);
            if (rec != null) restoreTexture(id, rec);
        }
    }

    // ---- Query ----

    public long getBytesSaved() {
        return totalBytesEvicted.get() - totalBytesRestored.get();
    }
    public int getEvictedCount() { return evictedSet.size(); }
    public int getEvictCount() { return evictCount.get(); }
    public int getRestoreCount() { return restoreCount.get(); }
    public int getBackupCount() { return backupById.size(); }

    public void reset() {
        restoreAll();
        backupById.clear();
        evictedSet.clear();
        synchronized (lruTracker) { lruTracker.clear(); }
        totalBytesEvicted.set(0);
        totalBytesRestored.set(0);
        evictCount.set(0);
        restoreCount.set(0);
        lastEvictionTime = 0;
    }

    // ---- Helpers ----

    private static long estimateBytes(int width, int height, int depth,
                                       int mipLevels, int internalformat) {
        float bpp = switch (internalformat) {
            case 0x8058 /*GL_RGBA8*/, 0x822E /*GL_R32F*/,
                 0x8233 /*GL_RG16F*/ -> 4.0f;
            case 0x8234 /*GL_RG32F*/, 0x881A /*GL_RGBA16F*/ -> 8.0f;
            case 0x8051 /*GL_RGB8*/ -> 3.0f;
            case 0x8229 /*GL_R8*/ -> 1.0f;
            case 0x822D /*GL_R16F*/, 0x8230 /*GL_RG8*/ -> 2.0f;
            default -> 4.0f; // conservative
        };
        long base = (long) (width * height * depth * bpp);
        if (mipLevels > 1) {
            // Mip chain: base + base/4 + base/16 + ...
            base = (long) (base * (1.0f - (float) Math.pow(0.25, mipLevels)) / (1.0f - 0.25f));
        }
        return base;
    }
}