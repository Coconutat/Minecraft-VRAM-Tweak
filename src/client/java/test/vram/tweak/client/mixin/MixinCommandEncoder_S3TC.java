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

@Mixin(targets = "com.mojang.blaze3d.systems.CommandEncoder")
public class MixinCommandEncoder_S3TC {

    private static final int GL_COMPRESSED_RGBA_S3TC_DXT1_EXT = 0x83F1;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;

    // ---- Overload 1: full-image write (1.21.11 compat, also used by some 26.2 paths) ----

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTexture(GpuTexture texture, NativeImage image, CallbackInfo ci) {
        if (!S3TCFlag.isSet()) return;
        String label = S3TCFlag.getLabel();
        S3TCFlag.clear();
        compressAndUpload(texture, image, label, 0, 0, image.getWidth(), image.getHeight(), ci);
    }

    // ---- Overload 2: 4-int overload (xOffset, yOffset, layer, mipLevel) ----
    // ponytail: 2-arg calls this with (0,0,0,0). Four ints are metadata, NOT w/h/x/y.
    //           NativeImage carries its own dimensions — use those. Same logic as overload 1.

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void onWriteToTextureRegion(GpuTexture texture, NativeImage image,
            int xOffset, int yOffset, int layer, int mipLevel, CallbackInfo ci) {
        if (!S3TCFlag.isSet()) return;
        String label = S3TCFlag.getLabel();
        S3TCFlag.clear();
        // Same as overload 1 — use full image dimensions, 4 ints are metadata
        compressAndUpload(texture, image, label, 0, 0, image.getWidth(), image.getHeight(), ci);
    }

    // ---- Shared compression logic ----

    private void compressAndUpload(GpuTexture texture, NativeImage image, String label,
            int srcX, int srcY, int w, int h, CallbackInfo ci) {
        try {
            // Read pixel region
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    pixels[y * w + x] = image.getPixel(srcX + x, srcY + y);

            boolean hasAlpha = S3TCDxtEncoder.hasAlpha(pixels);
            byte[] compressed;
            int glFormat;
            String fmt;
            if (hasAlpha) { compressed = S3TCDxtEncoder.compressBC3(pixels, w, h); glFormat = GL_COMPRESSED_RGBA_S3TC_DXT5_EXT; fmt = "BC3"; }
            else { compressed = S3TCDxtEncoder.compressBC1(pixels, w, h); glFormat = GL_COMPRESSED_RGBA_S3TC_DXT1_EXT; fmt = "BC1"; }

            int glId = ((GlTextureAccessor) texture).getId();
            int dataSize = compressed.length;
            java.nio.ByteBuffer buf = MemoryUtil.memAlloc(dataSize);
            try {
                buf.put(compressed); buf.flip();
                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, glId);
                if (srcX == 0 && srcY == 0 && w >= image.getWidth() && h >= image.getHeight()) {
                    // Full-image write: replace entire texture
                    GL32C.nglCompressedTexImage2D(GL32C.GL_TEXTURE_2D, 0, glFormat, w, h, 0, dataSize, MemoryUtil.memAddress(buf));
                } else {
                    // Sub-region write
                    GL32C.nglCompressedTexSubImage2D(GL32C.GL_TEXTURE_2D, 0, srcX, srcY, w, h, glFormat, dataSize, MemoryUtil.memAddress(buf));
                }
                GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, 0);
            } finally { MemoryUtil.memFree(buf); }

            ci.cancel();
            int origBytes = w * h * 4;
            VRAMTweak.LOGGER.info("[S3TC] {} {}×{} {} {}→{} bytes ~{}x",
                    label, w, h, fmt, origBytes, dataSize, origBytes / Math.max(dataSize, 1));
            VerificationLogger.logS3TCCompress(label, w, h, fmt, origBytes, dataSize);
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[S3TC] {} compression failed", label, e);
            VerificationLogger.logS3TCSkip(label, 0, 0, "error: " + e.getMessage());
        }
    }
}
