package test.vram.tweak.eviction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RGB5A1 texture compression utility.
 *
 * <p>Converts RGBA8 (4 bytes/pixel) textures to RGB5A1 (2 bytes/pixel)
 * when the alpha channel is binary (only 0 or 255). This cuts VRAM usage
 * in half for qualifying textures — most Minecraft block/item atlases
 * have binary alpha.</p>
 *
 * <p>Based on the approach from vram-killer mod, adapted for vram-tweak's
 * 26.2 writeToTexture hook.</p>
 */
public final class TextureCompressor {

    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/compress");

    private static volatile boolean enabled = false;
    private static volatile int convertedCount = 0;
    private static volatile int skippedCount = 0;
    private static volatile long savedBytes = 0;

    // GL texture ids already converted to RGB5A1 storage via the writeToTexture hook.
    // Subsequent mip-level uploads for these ids must be written as RGB5A1 too.
    private static final Set<Integer> COMPRESSED_IDS = ConcurrentHashMap.newKeySet();

    public static boolean isCompressed(int glObjectId) { return COMPRESSED_IDS.contains(glObjectId); }
    public static void markCompressed(int glObjectId) { COMPRESSED_IDS.add(glObjectId); }

    private TextureCompressor() {}

    // ---- Activation ----

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() { return enabled; }

    // ---- Pure pixel helpers (writeToTexture hook, 26.2 main upload path) ----
    // ponytail: these are pure byte/bit ops, unit-testable without GL.

    /** True if every alpha byte in the RGBA8 block is 0 or 255 (binary alpha). */
    public static boolean isBinaryAlpha(ByteBuffer pixels, int pixelCount, int offset) {
        for (int i = 0; i < pixelCount; i++) {
            int a = pixels.get(offset + i * 4 + 3) & 0xFF;
            if (a != 0 && a != 255) return false;
        }
        return true;
    }

    /** Convert an RGBA8 pixel block to RGB5A1 (2 bytes/px, native order, flipped). */
    public static ShortBuffer toRgb5a1(ByteBuffer pixels, int pixelCount, int offset) {
        ShortBuffer out = ByteBuffer.allocateDirect(pixelCount * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int i = 0; i < pixelCount; i++) {
            int idx = offset + i * 4;
            int r = (pixels.get(idx) & 0xFF) >> 3;
            int g = (pixels.get(idx + 1) & 0xFF) >> 3;
            int b = (pixels.get(idx + 2) & 0xFF) >> 3;
            int a = (pixels.get(idx + 3) & 0xFF) > 127 ? 1 : 0;
            out.put((short) ((r << 11) | (g << 6) | (b << 1) | a));
        }
        out.flip();
        return out;
    }

    /** Log a writeToTexture-level conversion and update stats. */
    public static void logConverted(int texId, int w, int h, String label, long level0SavedBytes) {
        convertedCount++;
        savedBytes += level0SavedBytes;
        if (convertedCount <= 5) {
            LOGGER.info("RGB5A1压缩(写入层): tex={} {}×{} 节省≈{}KB label={}",
                    texId, w, h, level0SavedBytes / 1024,
                    label != null ? label : "?");
        }
        if (convertedCount == 6) {
            LOGGER.info("RGB5A1压缩(写入层): 后续日志已抑制...");
        }
    }

    // ---- Stats ----

    public static int getConvertedCount() { return convertedCount; }
    public static int getSkippedCount() { return skippedCount; }
    public static long getSavedBytes() { return savedBytes; }
    public static long getSavedKB() { return savedBytes / 1024; }

    public static void resetStats() {
        convertedCount = 0;
        skippedCount = 0;
        savedBytes = 0;
    }
}