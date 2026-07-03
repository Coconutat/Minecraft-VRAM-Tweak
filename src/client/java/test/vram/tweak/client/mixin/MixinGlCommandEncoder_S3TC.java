package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
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
import test.vram.tweak.compression.S3TCFlag;
import test.vram.tweak.diagnostic.VerificationLogger;

/**
 * Intercept GlCommandEncoder.writeToTexture(GpuTexture, NativeImage) for S3TC compression.
 *
 * Flow:
 * 1. MixinGpuDevice_VRAMOptimize classifies texture at createTexture time, stores flags
 * 2. This mixin checks flag + label at writeToTexture time
 * 3. If flagged: compress NativeImage to DXT, re-upload with glCompressedTexImage2D
 * 4. Cancel original writeToTexture
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public class MixinGlCommandEncoder_S3TC {

    private static final int GL_COMPRESSED_RGBA_S3TC_DXT1_EXT = 0x83F1;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTexture(GpuTexture texture, NativeImage image, CallbackInfo ci) {
        if (!S3TCFlag.isSet()) return;
        String label = S3TCFlag.getLabel();
        S3TCFlag.clear();

        try {
            int width = image.getWidth();
            int height = image.getHeight();

            if (width < 4 || height < 4 || width % 4 != 0 || height % 4 != 0) {
                VRAMTweak.LOGGER.warn("[S3TC] {} {}×{} not multiple of 4, skipping", label, width, height);
                VerificationLogger.logS3TCSkip(label, width, height, "non-4-aligned");
                return;
            }

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    pixels[y * width + x] = image.getPixel(x, y);

            boolean hasAlpha = S3TCDxtEncoder.hasAlpha(pixels);
            byte[] compressed;
            int glFormat;
            String fmt;
            if (hasAlpha) { compressed = S3TCDxtEncoder.compressBC3(pixels, width, height); glFormat = GL_COMPRESSED_RGBA_S3TC_DXT5_EXT; fmt = "BC3"; }
            else { compressed = S3TCDxtEncoder.compressBC1(pixels, width, height); glFormat = GL_COMPRESSED_RGBA_S3TC_DXT1_EXT; fmt = "BC1"; }

            int glId = ((GlTextureAccessor) texture).getId();
            int dataSize = compressed.length;
            java.nio.ByteBuffer buf = MemoryUtil.memAlloc(dataSize);
            try {
                buf.put(compressed); buf.flip();
                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, glId);
                GL32C.nglCompressedTexImage2D(GL32C.GL_TEXTURE_2D, 0, glFormat, width, height, 0, dataSize, MemoryUtil.memAddress(buf));
                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, 0);
            } finally { MemoryUtil.memFree(buf); }

            ci.cancel();
            int origBytes = width * height * 4;
            VRAMTweak.LOGGER.info("[S3TC] {} {}×{} {} {}→{} bytes ~{}x",
                    label, width, height, fmt, origBytes, dataSize, origBytes / Math.max(dataSize, 1));
            VerificationLogger.logS3TCCompress(label, width, height, fmt, origBytes, dataSize);
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[S3TC] {} compression failed", label, e);
            VerificationLogger.logS3TCSkip(label, 0, 0, "error: " + e.getMessage());
        }
    }
}
