package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlStateManager;

import test.vram.tweak.client.gpu.PinnedMemory;
import test.vram.tweak.client.gpu.PinnedMemoryPool;
import test.vram.tweak.config.VRAMConfig;

/**
 * Experimental: use GL_AMD_pinned_memory PBO pool for zero-copy texture uploads.
 *
 * Hooks GlStateManager._texImage2D and both _texSubImage2D overloads to route
 * large texture data through a pre-allocated pinned PBO pool (see
 * PinnedMemoryPool). Instead of creating/deleting GL buffers per upload, the
 * pool reuses persistently-mapped buffers — no driver stalls, async GPU DMA.
 *
 * Only active when:
 * 1. experimental.pinnedMemory = true in config
 * 2. GL_AMD_pinned_memory extension is available
 * 3. Texture dimensions ≥ pinnedMemoryMinSize
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManager_PinnedMemory {
    @Unique
    private static final Logger VRAM_TWEAK_PINNED_LOG = LoggerFactory.getLogger("vram-tweak/pinned");

    // ---- _texImage2D (ByteBuffer) ----

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTexImage2D(int target, int level, int internalformat,
                                     int width, int height, int border,
                                     int format, int type, ByteBuffer pixels,
                                     CallbackInfo ci) {
        if (pixels == null || !shouldUsePinned(width, height)) return;

        var slot = PinnedMemoryPool.acquire(pixels);
        if (slot == null) return;

        try {
            PinnedMemoryPool.bindAsUnpack(slot);
            GL32C.glTexImage2D(target, level, internalformat, width, height,
                    border, format, type, 0L);
            PinnedMemoryPool.unbindUnpack();
            ci.cancel();
            PinnedMemory.markUploadHandled(); // Eviction's RETURN must not re-compress
            VRAM_TWEAK_PINNED_LOG.info("[Pinned] _texImage2D {}x{} via PBO pool", width, height);
        } catch (Exception e) {
            VRAM_TWEAK_PINNED_LOG.warn("[Pinned] _texImage2D failed, falling back", e);
            PinnedMemoryPool.unbindUnpack();
        } finally {
            PinnedMemoryPool.release(slot);
        }
    }

    // ---- _texSubImage2D (ByteBuffer) ----

    @Inject(method = "_texSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTexSubImage2D(int target, int level,
                                         int xOffset, int yOffset,
                                         int width, int height,
                                         int format, int type, ByteBuffer pixels,
                                         CallbackInfo ci) {
        if (pixels == null || !shouldUsePinned(width, height)) return;

        var slot = PinnedMemoryPool.acquire(pixels);
        if (slot == null) return;

        try {
            PinnedMemoryPool.bindAsUnpack(slot);
            GL32C.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                    format, type, 0L);
            PinnedMemoryPool.unbindUnpack();
            ci.cancel();
            VRAM_TWEAK_PINNED_LOG.debug("[Pinned] _texSubImage2D {}x{} at ({},{}) via PBO pool",
                    width, height, xOffset, yOffset);
        } catch (Exception e) {
            VRAM_TWEAK_PINNED_LOG.warn("[Pinned] _texSubImage2D failed, falling back", e);
            PinnedMemoryPool.unbindUnpack();
        } finally {
            PinnedMemoryPool.release(slot);
        }
    }

    // ---- _texSubImage2D (long / native pointer — NativeImage primary path) ----

    @Inject(method = "_texSubImage2D(IIIIIIIIJ)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void onTexSubImage2D_Long(int target, int level,
                                              int xOffset, int yOffset,
                                              int width, int height,
                                              int format, int type, long pixels,
                                              CallbackInfo ci) {
        if (pixels == 0L || !shouldUsePinned(width, height)) return;

        var slot = PinnedMemoryPool.acquire(MemoryUtil.memByteBuffer(
                pixels, (int) uploadSize(width, height, format, type)));
        if (slot == null) return;

        try {
            PinnedMemoryPool.bindAsUnpack(slot);
            GL32C.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                    format, type, 0L);
            PinnedMemoryPool.unbindUnpack();
            ci.cancel();
            VRAM_TWEAK_PINNED_LOG.debug("[Pinned] _texSubImage2D(long) {}x{} at ({},{}) via PBO pool",
                    width, height, xOffset, yOffset);
        } catch (Exception e) {
            VRAM_TWEAK_PINNED_LOG.warn("[Pinned] _texSubImage2D(long) failed, falling back", e);
            PinnedMemoryPool.unbindUnpack();
        } finally {
            PinnedMemoryPool.release(slot);
        }
    }

    /** Bytes for a w×h upload in the given format/type (conservative 4B/px default). */
    @Unique
    private static long uploadSize(int width, int height, int format, int type) {
        int components = switch (format) {
            case GL11C.GL_RED, GL11C.GL_DEPTH_COMPONENT -> 1;
            case GL30C.GL_RG, GL30C.GL_DEPTH_STENCIL -> 2;
            case GL11C.GL_RGB, GL12C.GL_BGR -> 3;
            default -> 4;
        };
        int bytesPerComponent = switch (type) {
            case GL11C.GL_UNSIGNED_BYTE, GL11C.GL_BYTE -> 1;
            case GL11C.GL_UNSIGNED_SHORT, GL11C.GL_SHORT,
                 GL12C.GL_UNSIGNED_SHORT_5_5_5_1, GL12C.GL_UNSIGNED_SHORT_5_6_5,
                 GL12C.GL_UNSIGNED_SHORT_4_4_4_4 -> 2;
            default -> 4;
        };
        return (long) width * height * components * bytesPerComponent;
    }

    // ---- Threshold check ----

    @Unique
    private static boolean shouldUsePinned(int width, int height) {
        var cfg = VRAMConfig.getInstance();
        if (!cfg.showExperimental) return false;
        if (!cfg.experimental.pinnedMemory) return false;
        if (!PinnedMemory.isAvailable()) return false;
        return Math.max(width, height) >= cfg.experimental.pinnedMemoryMinSize;
    }
}
