package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;

import test.vram.tweak.eviction.TextureCompressor;

/**
 * B1: hooks the object-layer texture upload entry
 * {@code GlCommandEncoder.writeToTexture(GpuTexture, ByteBuffer, level, face, x, y, w, h)}
 * — the actual 26.2 main upload path (NativeImage atlases go through here).
 *
 * <p>Why here and not {@code _texSubImage2D}: this method has the {@link GpuTexture}
 * (label, format, mip count, dims), which the raw GL sub-image call lacks. Rebuilding
 * a texture's storage as RGB5A1 requires those.
 *
 * <p><b>RGB5A1 compression</b> — for RGBA8 textures with binary alpha: rebuild the
 * whole mip chain as RGB5A1 and write the converted level-0 data, then convert
 * subsequent mip-level uploads for the same texture id.
 *
 * <p>Every branch is wrapped in try/catch and falls back to the original upload on
 * error — a compression mistake must never corrupt or crash the game.
 *
 * <p>The legacy eviction path ({@code MixinGlStateManager_Eviction}) was removed in
 * 2026-08-09: eviction is disabled on 26.2 (AMD driver crash on mid-render restore)
 * and this object-layer hook covers the real upload path.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder", remap = false)
public class MixinGlCommandEncoder_Compression {

    @Unique
    private static final Logger VRAM_TWEAK_COMPRESS_LOG = LoggerFactory.getLogger("vram-tweak/compress-write");

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onWriteToTexture(GpuTexture tex, ByteBuffer data,
                                          int level, int face, int x, int y, int w, int h,
                                          CallbackInfo ci) {
        if (data == null || !(tex instanceof GlTexture)) return;
        try {
            int texId = ((GlTexture) tex).glId();

            // A. Texture already converted to RGB5A1 — later mip-level uploads must
            //    be written in RGB5A1 layout to match the (now RGB5A1) storage.
            if (TextureCompressor.isCompressed(texId) && level > 0) {
                writeCompressedMip(texId, level, x, y, w, h, data);
                ci.cancel();
                return;
            }

            // B. Full level-0 image upload → RGB5A1 compression.
            if (level == 0 && face == 0 && x == 0 && y == 0
                    && w == tex.getWidth(0) && h == tex.getHeight(0)) {
                if (TextureCompressor.isEnabled() && isRgba8(tex)
                        && !TextureCompressor.isCompressed(texId)) {
                    compressAndUpload(tex, texId, w, h, data, ci);
                }
            }
        } catch (Exception e) {
            VRAM_TWEAK_COMPRESS_LOG.warn("[WriteToTexture] hook failed, falling back", e);
        }
    }

    // ---- RGB5A1: rebuild whole mip chain + write converted level 0 ----

    @Unique
    private static void compressAndUpload(GpuTexture tex, int texId, int w, int h,
                                           ByteBuffer data, CallbackInfo ci) {
        String label = tex.getLabel();
        // ponytail: only compress atlas textures — rebuilding a non-atlas RGBA8 texture
        // (e.g. an Iris intermediate/render target) as RGB5A1 reallocates storage it may
        // still be attached to, the same class of AMD driver crash eviction hit.
        if (label == null || !label.contains("atlas")) return;

        int pos = data.position();
        int pixelCount = w * h;
        if (pixelCount <= 0 || data.remaining() < (long) pixelCount * 4) return;
        if (!TextureCompressor.isBinaryAlpha(data, pixelCount, pos)) return;

        ShortBuffer rgb5a1 = TextureCompressor.toRgb5a1(data, pixelCount, pos);

        int mips = Math.max(1, tex.getMipLevels());
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texId);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        // Rebuild every mip level's storage as RGB5A1 (covers both generateMipmap
        // and per-level upload: sub-image uploads require the level's storage to exist).
        for (int m = 0; m < mips; m++) {
            int mw = Math.max(1, tex.getWidth(m));
            int mh = Math.max(1, tex.getHeight(m));
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, m, GL11C.GL_RGB5_A1, mw, mh, 0,
                    GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_5_5_5_1, (ByteBuffer) null);
        }
        // Write converted level-0 data.
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_5_5_5_1, rgb5a1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);

        TextureCompressor.markCompressed(texId);
        TextureCompressor.logConverted(texId, w, h, label, (long) pixelCount * 2);
        ci.cancel();
    }

    /** Write a converted RGB5A1 mip-level upload for an already-compressed texture. */
    @Unique
    private static void writeCompressedMip(int texId, int level, int x, int y, int w, int h,
                                            ByteBuffer data) {
        int pos = data.position();
        int pixelCount = w * h;
        if (pixelCount <= 0 || data.remaining() < (long) pixelCount * 4) return;
        ShortBuffer rgb5a1 = TextureCompressor.toRgb5a1(data, pixelCount, pos);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texId);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, level, x, y, w, h,
                GL11C.GL_RGBA, GL12C.GL_UNSIGNED_SHORT_5_5_5_1, rgb5a1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
    }

    // ---- Helpers ----

    @Unique
    private static boolean isRgba8(GpuTexture tex) {
        try {
            return "RGBA8_UNORM".equals(tex.getFormat().name());
        } catch (Exception e) {
            return false;
        }
    }
}
