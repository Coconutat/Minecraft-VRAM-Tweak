package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.opengl.GlTexture;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.NativeImage;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.client.mixin.accessor.GlTextureAccessor;
import test.vram.tweak.compression.S3TCDxtEncoder;
import test.vram.tweak.compression.TextureCategory;
import test.vram.tweak.config.VRAMConfig;

/**
 * Intercept CommandEncoder.writeToTexture(GpuTexture, NativeImage) for S3TC compression.
 *
 * Flow:
 * 1. MixinGpuDevice_VRAMOptimize classifies texture at createTexture time, stores in S3TC_FLAG
 * 2. This mixin checks S3TC_FLAG at writeToTexture time
 * 3. If flagged: compress NativeImage to DXT, re-upload with glCompressedTexImage2D
 * 4. Cancel original writeToTexture
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class MixinGlCommandEncoder_S3TC {

    // ThreadLocal flag set by MixinGpuDevice_VRAMOptimize
    public static final ThreadLocal<Boolean> S3TC_FLAG = new ThreadLocal<>();
    public static final ThreadLocal<Integer> S3TC_WIDTH = new ThreadLocal<>();
    public static final ThreadLocal<Integer> S3TC_HEIGHT = new ThreadLocal<>();
    public static final ThreadLocal<Boolean> S3TC_HAS_ALPHA = new ThreadLocal<>();

    private static final int GL_COMPRESSED_RGBA_S3TC_DXT1_EXT = 0x83F1;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTexture(GpuTexture texture, NativeImage image, CallbackInfo ci) {
        Boolean flagged = S3TC_FLAG.get();
        if (!Boolean.TRUE.equals(flagged)) return;
        S3TC_FLAG.remove();

        try {
            int width = image.getWidth();
            int height = image.getHeight();

            if (width < 4 || height < 4 || width % 4 != 0 || height % 4 != 0) {
                VRAMTweak.LOGGER.warn("[S3TC] Texture {}×{} not multiple of 4, skipping compression", width, height);
                return;
            }

            // Read RGBA pixels from NativeImage
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = image.getPixel(x, y);
                }
            }

            boolean hasAlpha = S3TCDxtEncoder.hasAlpha(pixels);
            byte[] compressed;
            int glFormat;

            if (hasAlpha) {
                compressed = S3TCDxtEncoder.compressBC3(pixels, width, height);
                glFormat = GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            } else {
                compressed = S3TCDxtEncoder.compressBC1(pixels, width, height);
                glFormat = GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            }

            // Upload compressed data directly to GL
            int glId = ((GlTextureAccessor) texture).getId();
            int dataSize = compressed.length;
            java.nio.ByteBuffer buf = MemoryUtil.memAlloc(dataSize);
            try {
                buf.put(compressed);
                buf.flip();

                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, glId);
                GL32C.nglCompressedTexImage2D(GL32C.GL_TEXTURE_2D, 0, glFormat,
                        width, height, 0, dataSize, MemoryUtil.memAddress(buf));
                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, 0);
            } finally {
                MemoryUtil.memFree(buf);
            }

            // Cancel the original uncompressed write
            ci.cancel();

            VRAMTweak.LOGGER.info("[S3TC] Compressed {}×{}: {}→{} bytes ({}:{}),  {}→{}x savings",
                    width, height,
                    width * height * 4, dataSize,
                    hasAlpha ? "BC3" : "BC1",
                    hasAlpha ? "DXT5" : "DXT1",
                    width * height * 4 / Math.max(dataSize, 1),
                    "x");
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[S3TC] Compression failed, falling through to original upload", e);
            // Fall through: original writeToTexture proceeds
        }
    }
}
