package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlStateManager;

import test.vram.tweak.allocation.AllocLabelBridge;
import test.vram.tweak.client.gpu.PinnedMemory;
import test.vram.tweak.eviction.TextureCompressor;
import test.vram.tweak.eviction.VramEvictionManager;

/**
 * Hook {@code GlStateManager} for texture eviction: backup pixel data on
 * allocation, LRU-track on bind, restore on eviction hit, cleanup on delete.
 * Also performs RGB5A1 texture compression for qualifying textures.
 *
 * <p>Works independently of the AllocTracker — no dependency on its state.</p>
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManager_Eviction {

    /** Carries pixel data + label from HEAD to RETURN so compression can re-read after GL upload. */
    @Unique
    private static final ThreadLocal<CompressParams> PENDING_COMPRESS = new ThreadLocal<>();

    @Unique
    private static final class CompressParams {
        final int texId;
        final int width;
        final int height;
        final int internalformat;
        final int format;
        final int type;
        final ByteBuffer pixels;
        final String label;
        CompressParams(int texId, int width, int height, int internalformat,
                       int format, int type, ByteBuffer pixels, String label) {
            this.texId = texId; this.width = width; this.height = height;
            this.internalformat = internalformat; this.format = format; this.type = type;
            this.pixels = pixels; this.label = label;
        }
    }

    // ==================== _texImage2D (ByteBuffer) — backup + compress ====================

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), remap = false)
    private static void onTexImage2D_HEAD(int target, int level, int internalformat,
                                           int width, int height, int border,
                                           int format, int type, ByteBuffer pixels,
                                           CallbackInfo ci) {
        if (pixels == null) return;
        try {
            int texId = getTextureBinding(target);
            if (texId == 0) return;

            String label = AllocLabelBridge.peek();

            // 1. Save params for RETURN-stage compression
            PENDING_COMPRESS.set(new CompressParams(texId, width, height,
                    internalformat, format, type, pixels, label));

            // 2. Eviction backup (if active)
            var mgr = VramEvictionManager.getInstance();
            if (mgr.isActive()) {
                mgr.backupTexture(texId, width, height, 1, 0, internalformat,
                        format, type, pixels, label);
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("RETURN"), remap = false)
    private static void onTexImage2D_RETURN(int target, int level, int internalformat,
                                              int width, int height, int border,
                                              int format, int type, ByteBuffer pixels,
                                              CallbackInfo ci) {
        if (pixels == null) return;
        try {
            CompressParams cp = PENDING_COMPRESS.get();
            PENDING_COMPRESS.remove();
            if (cp == null) return;

            // Pinned memory already uploaded this texture via PBO — re-uploading for
            // compression would be a redundant third upload (both features on).
            if (PinnedMemory.consumeUploadHandled()) return;

            // The buffer's position may have moved after GL upload; reset it
            cp.pixels.position(0);
            TextureCompressor.tryCompress(cp.texId, cp.width, cp.height,
                    cp.internalformat, cp.format, cp.type, cp.pixels, cp.label);
        } catch (Exception ignored) {
        }
    }

    // ==================== _bindTexture — LRU touch + restore ====================

    @Inject(method = "_bindTexture(I)V",
            at = @At("HEAD"), remap = false)
    private static void onBindTexture(int texture, CallbackInfo ci) {
        if (texture == 0) return;
        var mgr = VramEvictionManager.getInstance();
        if (!mgr.isActive()) return;

        try {
            mgr.touchTexture(texture);
        } catch (Exception ignored) {
        }
    }

    // ==================== _deleteTexture — cleanup ====================

    @Inject(method = "_deleteTexture(I)V",
            at = @At("HEAD"), remap = false)
    private static void onDeleteTexture(int id, CallbackInfo ci) {
        var mgr = VramEvictionManager.getInstance();
        if (!mgr.isActive()) return;

        try {
            mgr.forgetTexture(id);
        } catch (Exception ignored) {
        }
    }

    // ==================== Helpers ====================

    @Unique
    private static int getTextureBinding(int target) {
        return switch (target) {
            case GL11C.GL_TEXTURE_2D       -> GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            case GL12C.GL_TEXTURE_3D       -> GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
            case GL13C.GL_TEXTURE_CUBE_MAP -> GL11C.glGetInteger(GL13C.GL_TEXTURE_BINDING_CUBE_MAP);
            case GL30C.GL_TEXTURE_2D_ARRAY -> GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
            default                        -> 0;
        };
    }
}