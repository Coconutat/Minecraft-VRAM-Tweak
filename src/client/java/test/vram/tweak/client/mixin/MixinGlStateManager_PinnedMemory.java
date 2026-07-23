package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlStateManager;

import test.vram.tweak.client.gpu.PinnedMemory;
import test.vram.tweak.config.VRAMConfig;

/**
 * Experimental: use GL_AMD_pinned_memory for zero-copy texture uploads.
 *
 * Hooks GlStateManager._texImage2D and _texSubImage2D to create a pinned PBO
 * for large texture data, avoiding the driver copy during upload.
 *
 * Only active when:
 * 1. experimental.pinnedMemory = true in config
 * 2. GL_AMD_pinned_memory extension is available
 * 3. Texture dimensions exceed pinnedMemoryMinSize threshold
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManager_PinnedMemory {
    @Unique
    private static final Logger VRAM_TWEAK_PINNED_LOG = LoggerFactory.getLogger("vram-tweak/pinned");

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTexImage2D(int target, int level, int internalformat,
                                     int width, int height, int border,
                                     int format, int type, ByteBuffer pixels,
                                     CallbackInfo ci) {
        if (pixels == null || !shouldUsePinned(width, height)) return;

        int pboId = PinnedMemory.createPinnedPBO(pixels.remaining(), pixels);
        if (pboId < 0) return;

        try {
            PinnedMemory.bindAsUnpackPBO(pboId);
            GL32C.glTexImage2D(target, level, internalformat, width, height,
                    border, format, type, 0L);
            PinnedMemory.unbindUnpackPBO();
            ci.cancel();
            VRAM_TWEAK_PINNED_LOG.info("[Pinned] _texImage2D {}x{} via PBO (experimental)", width, height);
        } catch (Exception e) {
            VRAM_TWEAK_PINNED_LOG.warn("[Pinned] _texImage2D failed, falling back", e);
            PinnedMemory.unbindUnpackPBO();
        } finally {
            PinnedMemory.deletePBO(pboId);
        }
    }

    @Inject(method = "_texSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTexSubImage2D(int target, int level,
                                         int xOffset, int yOffset,
                                         int width, int height,
                                         int format, int type, ByteBuffer pixels,
                                         CallbackInfo ci) {
        if (pixels == null || !shouldUsePinned(width, height)) return;

        int pboId = PinnedMemory.createPinnedPBO(pixels.remaining(), pixels);
        if (pboId < 0) return;

        try {
            PinnedMemory.bindAsUnpackPBO(pboId);
            GL32C.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                    format, type, 0L);
            PinnedMemory.unbindUnpackPBO();
            ci.cancel();
            VRAM_TWEAK_PINNED_LOG.debug("[Pinned] _texSubImage2D {}x{} at ({},{}) via PBO",
                    width, height, xOffset, yOffset);
        } catch (Exception e) {
            VRAM_TWEAK_PINNED_LOG.warn("[Pinned] _texSubImage2D failed, falling back", e);
            PinnedMemory.unbindUnpackPBO();
        } finally {
            PinnedMemory.deletePBO(pboId);
        }
    }

    @Unique
    private static boolean shouldUsePinned(int width, int height) {
        var cfg = VRAMConfig.getInstance().experimental;
        if (!cfg.pinnedMemory) return false;
        if (!PinnedMemory.isAvailable()) return false;
        // Only use PBO for textures above threshold size
        return Math.max(width, height) >= cfg.pinnedMemoryMinSize;
    }
}
