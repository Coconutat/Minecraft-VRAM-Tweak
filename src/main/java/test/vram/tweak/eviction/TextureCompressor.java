package test.vram.tweak.eviction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.diagnostic.VramModLog;

/**
 * RGB5A1 texture compression utility.
 *
 * <p>Converts RGBA8 (4 bytes/pixel) textures to RGB5A1 (2 bytes/pixel)
 * when the alpha channel is binary (only 0 or 255). This cuts VRAM usage
 * in half for qualifying textures — most Minecraft block/item atlases
 * have binary alpha.</p>
 *
 * <p>Based on the approach from vram-killer mod, adapted for vram-tweak's
 * eviction system.</p>
 */
public final class TextureCompressor {

    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/compress");

    private static volatile boolean enabled = false;
    private static volatile int convertedCount = 0;
    private static volatile int skippedCount = 0;
    private static volatile long savedBytes = 0;

    private TextureCompressor() {}

    // ---- Activation ----

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() { return enabled; }

    // ---- Conversion ----

    /**
     * Attempt to compress a texture from RGBA8 to RGB5A1 in-place.
     *
     * <p>Must be called on the render thread, AFTER the original
     * {@code glTexImage2D} has completed.</p>
     *
     * @param texId          GL texture object ID
     * @param width          texture width in pixels
     * @param height         texture height in pixels
     * @param internalformat GL internal format used for the original upload
     * @param format         GL format used for the original upload
     * @param type           GL type used for the original upload
     * @param pixels         the original pixel data (ByteBuffer)
     * @param label          texture label (for logging)
     * @return true if the texture was converted
     */
    public static boolean tryCompress(int texId, int width, int height,
                                       int internalformat, int format, int type,
                                       ByteBuffer pixels, String label) {
        if (!enabled) return false;
        if (pixels == null || width < 4 || height < 4) return false;

        // Only convert RGBA8 (GL_RGBA8 = 0x8058) with GL_RGBA format and GL_UNSIGNED_BYTE type
        if (internalformat != 0x8058 /*GL_RGBA8*/) return false;
        if (format != GL11C.GL_RGBA) return false;
        if (type != GL11C.GL_UNSIGNED_BYTE) return false;

        int pixelCount = width * height;
        int expectedBytes = pixelCount * 4;
        if (pixels.remaining() < expectedBytes) return false;

        // Check if alpha is binary (all 0 or 255)
        int pos = pixels.position();
        boolean allBinaryAlpha = true;
        for (int i = 0; i < pixelCount; i++) {
            int alpha = pixels.get(pos + i * 4 + 3) & 0xFF;
            if (alpha != 0 && alpha != 255) {
                allBinaryAlpha = false;
                break;
            }
        }

        if (!allBinaryAlpha) {
            skippedCount++;
            if (convertedCount == 0 && skippedCount <= 5) {
                VramModLog.debug("[Compress] SKIP non-binary alpha: " + label + " " + width + "x" + height);
            }
            return false;
        }

        // Convert RGBA8 → RGB5A1
        ShortBuffer shortBuffer = ByteBuffer.allocateDirect(pixelCount * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();

        for (int i = 0; i < pixelCount; i++) {
            int idx = pos + i * 4;
            int r = (pixels.get(idx) & 0xFF) >> 3;
            int g = (pixels.get(idx + 1) & 0xFF) >> 3;
            int b = (pixels.get(idx + 2) & 0xFF) >> 3;
            int a = (pixels.get(idx + 3) & 0xFF) > 127 ? 1 : 0;
            shortBuffer.put((short) ((r << 11) | (g << 6) | (b << 1) | a));
        }
        shortBuffer.flip();

        // Re-upload with RGB5A1 format
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texId);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGB5_A1,
                width, height, 0,
                GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_5_5_5_1,
                shortBuffer);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);

        // Stats
        convertedCount++;
        long originalSize = (long) width * height * 4;
        long newSize = (long) width * height * 2;
        savedBytes += (originalSize - newSize);

        if (convertedCount <= 5) {
            LOGGER.info("RGB5A1压缩: tex={} {}×{} ({}KB→{}KB, 节省 {}KB) label={}",
                    texId, width, height,
                    originalSize / 1024, newSize / 1024,
                    (originalSize - newSize) / 1024,
                    label != null ? label : "?");
        }
        if (convertedCount == 6) {
            LOGGER.info("RGB5A1压缩: 后续日志已抑制...");
        }

        VramModLog.debug("[Compress] CONVERTED tex=" + texId
                + " " + width + "x" + height
                + " " + (originalSize / 1024) + "KB→" + (newSize / 1024) + "KB"
                + " label=" + label);
        return true;
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