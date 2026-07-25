package test.vram.tweak.client.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL44C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pinned PBO pool for texture upload via GL_AMD_pinned_memory.
 *
 * Pre-allocates pinned PBOs and reuses them, avoiding glGenBuffers/glDeleteBuffers
 * per upload (which caused game-freeze during loading with the old approach).
 *
 * Two modes:
 *   **Mode A (persistent mapping):** glBufferStorage + glMapBufferRange with
 *       MAP_PERSISTENT|MAP_COHERENT. CPU writes directly to mapped memory,
 *       GPU DMAs from it. Zero driver stall.
 *   **Mode B (fallback):** glBufferData + glBufferSubData. Pre-allocated PBOs
 *       avoid gen/delete overhead even without persistent mapping.
 *
 * Pool: 512KB, 2MB, 8MB, 32MB — covers common atlas sizes.
 */
public class PinnedMemoryPool {
    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/pinned-pool");

    private static final int GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD = 0x9160;

    private static final long[] POOL_SIZES = {
        512 * 1024L,       // 512 KB
        2 * 1024 * 1024L,  // 2 MB
        8 * 1024 * 1024L,  // 8 MB
        32 * 1024 * 1024L, // 32 MB
    };

    private static final List<PboEntry> pool = new ArrayList<>();
    private static boolean initialized;
    private static boolean usePersistent; // true = Mode A, false = Mode B

    // ---- Init / shutdown ----

    public static synchronized void init() {
        if (initialized) return;
        if (!PinnedMemory.isAvailable()) {
            LOG.info("[PinnedPool] GL_AMD_pinned_memory not available, pool disabled");
            return;
        }

        // Try Mode A first (persistent mapping via glBufferStorage)
        boolean persistentWorked = false;
        for (long size : POOL_SIZES) {
            try {
                PboEntry entry = createPersistentPbo(size);
                if (entry != null) {
                    pool.add(entry);
                    persistentWorked = true;
                }
            } catch (Exception ignored) {
            }
            if (!persistentWorked) break; // first failure → fall back
        }

        if (persistentWorked) {
            usePersistent = true;
            LOG.info("[PinnedPool] Mode A (persistent mapping): {} PBOs", pool.size());
        } else {
            // Mode B: glBufferData fallback
            pool.clear();
            int count = 0;
            for (int i = 0; i < POOL_SIZES.length; i++) {
                long size = POOL_SIZES[i];
                try {
                    PboEntry entry = createFallbackPbo(size);
                    if (entry != null) {
                        pool.add(entry);
                        count++;
                    }
                } catch (Exception e) {
                    LOG.warn("[PinnedPool] Fallback PBO #{} failed: {}", i, e.getMessage());
                }
            }
            usePersistent = false;
            LOG.info("[PinnedPool] Mode B (glBufferData/glBufferSubData): {} PBOs", count);
        }

        initialized = true;
    }

    public static synchronized void shutdown() {
        for (PboEntry entry : pool) {
            try {
                if (entry.fence != 0) {
                    GL32C.glDeleteSync(entry.fence);
                    entry.fence = 0;
                }
                GL15.glDeleteBuffers(entry.id);
            } catch (Exception ignored) {
            }
        }
        pool.clear();
        initialized = false;
        LOG.info("[PinnedPool] Shut down");
    }

    // ---- Acquire / release ----

    public static PboSlot acquire(ByteBuffer data) {
        if (!initialized || data == null) return null;
        int size = data.remaining();
        if (size <= 0) return null;

        PboEntry best = null;
        int bestIdx = -1;

        for (int i = 0; i < pool.size(); i++) {
            PboEntry entry = pool.get(i);
            if (entry.capacity < size) continue;
            if (!isFenceSignaled(entry)) continue;
            if (best == null || entry.capacity < best.capacity) {
                best = entry;
                bestIdx = i;
            }
        }

        if (best == null) {
            for (int i = 0; i < pool.size(); i++) {
                PboEntry entry = pool.get(i);
                if (entry.capacity < size) continue;
                waitForFence(entry);
                if (isFenceSignaled(entry)) {
                    best = entry;
                    bestIdx = i;
                    break;
                }
            }
        }

        if (best == null) return null;

        // Clear old fence
        if (best.fence != 0) {
            GL32C.glDeleteSync(best.fence);
            best.fence = 0;
        }

        // Write data into pinned PBO
        if (usePersistent && best.mappedBuffer != null) {
            // Mode A: memcpy to persistent mapped memory (CPU coherent)
            int pos = data.position();
            best.mappedBuffer.clear();
            best.mappedBuffer.put(data);
            best.mappedBuffer.flip();
            data.position(pos);
        } else {
            // Mode B: glBufferSubData
            GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, best.id);
            GL15.glBufferSubData(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0, data);
            GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0);
        }

        return new PboSlot(best, bestIdx);
    }

    public static void release(PboSlot slot) {
        if (slot == null || slot.entry == null) return;
        slot.entry.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public static void bindAsUnpack(PboSlot slot) {
        if (slot == null || slot.entry == null) return;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.entry.id);
    }

    public static void unbindUnpack() {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    // ---- Internal ----

    /** Mode A: glBufferStorage + persistent mapping. */
    private static PboEntry createPersistentPbo(long size) {
        int pboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, pboId);

        int flags = GL30.GL_MAP_WRITE_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
        GL44C.glBufferStorage(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, size, flags);

        ByteBuffer mapped = GL30.glMapBufferRange(
                GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0, size,
                GL30.GL_MAP_WRITE_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT);
        GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0);

        if (mapped == null) {
            GL15.glDeleteBuffers(pboId);
            return null;
        }

        PboEntry entry = new PboEntry();
        entry.id = pboId;
        entry.capacity = size;
        entry.mappedBuffer = mapped;
        entry.fence = 0;
        return entry;
    }

    /** Mode B: glBufferData (no persistent mapping, but still pre-allocated). */
    private static PboEntry createFallbackPbo(long size) {
        int pboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, pboId);
        GL15.glBufferData(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, size, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0);

        PboEntry entry = new PboEntry();
        entry.id = pboId;
        entry.capacity = size;
        entry.mappedBuffer = null;
        entry.fence = 0;
        return entry;
    }

    private static boolean isFenceSignaled(PboEntry entry) {
        if (entry.fence == 0) return true;
        int result = GL32C.glClientWaitSync(entry.fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        return result == GL32C.GL_ALREADY_SIGNALED || result == GL32C.GL_CONDITION_SATISFIED;
    }

    private static void waitForFence(PboEntry entry) {
        if (entry.fence == 0) return;
        GL32C.glClientWaitSync(entry.fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
    }

    // ---- Entry / Slot types ----

    static class PboEntry {
        int id;
        long capacity;
        ByteBuffer mappedBuffer; // null in Mode B
        long fence; // GLsync, 0 = free
    }

    public static class PboSlot {
        final PboEntry entry;
        final int index;
        PboSlot(PboEntry entry, int index) {
            this.entry = entry;
            this.index = index;
        }
    }
}
