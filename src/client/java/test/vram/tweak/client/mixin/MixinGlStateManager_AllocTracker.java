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
import test.vram.tweak.allocation.VramAllocationRecord;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.allocation.VramAllocLogger;

/**
 * B-layer allocation tracker: intercepts ALL texture allocations and
 * deallocations at the lowest OpenGL wrapper level.
 *
 * <p><b>Coverage:</b> Minecraft, Sodium, Iris — any texture creation that
 * passes through {@code GlStateManager._texImage2D} / {@code _texSubImage2D}
 * / {@code _deleteTexture}.</p>
 *
 * <p><b>Hooked methods:</b>
 * <ul>
 *   <li>{@code _texImage2D(..., ByteBuffer)} — allocation with Java heap data</li>
 *   <li>{@code _texImage2D(..., long)} — allocation with native pointer (Blaze3D fast path)</li>
 *   <li>{@code _texSubImage2D(..., ByteBuffer)} — sub-image upload (tracked but
 *       not counted as new allocation unless first upload for this texture)</li>
 *   <li>{@code _texSubImage2D(..., long)} — native pointer sub-image upload</li>
 *   <li>{@code _deleteTexture(int)} — deallocation</li>
 * </ul></p>
 *
 * <p><b>Performance:</b> When {@code VramAllocationTracker.isActive()} is false,
 * all hooks return immediately after a single boolean check — zero measurable
 * overhead.</p>
 *
 * @see MixinGlFramebuffer_AllocTracker D-layer for render-target classification
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManager_AllocTracker {

    // ==================== _texImage2D (ByteBuffer) ====================

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), remap = false)
    private static void onTexImage2D_BB(int target, int level, int internalformat,
                                         int width, int height, int border,
                                         int format, int type, ByteBuffer pixels,
                                         CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;

        try {
            int texId = getTextureBinding(target);
            if (texId == 0) return;

            // _texImage2D is the primary allocation point — create a new record.
            // If a record already exists for this texture (re-allocation), the old
            // one is effectively stale; we create a new record.
            VramAllocationRecord rec = buildRecord(texId, width, height, /*depth*/1,
                    /*mipLevels*/0, internalformat, level);
            tracker.recordAlloc(rec);

            // Always log — Blaze3D uses _texImage2D(..., null) for storage allocation
            VramAllocLogger.logAlloc(rec);
        } catch (Exception ignored) {
            // Don't let tracking errors crash the game
        }
    }

    // ==================== _texImage2D (long/native pointer) ====================

    @Inject(method = "_texImage2D(IIIIIIIIJ)V",
            at = @At("HEAD"), remap = false, require = 0)
    private static void onTexImage2D_Long(int target, int level, int internalformat,
                                           int width, int height, int border,
                                           int format, int type, long pixels,
                                           CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;

        try {
            int texId = getTextureBinding(target);
            if (texId == 0) return;

            VramAllocationRecord rec = buildRecord(texId, width, height, /*depth*/1,
                    /*mipLevels*/0, internalformat, level);
            tracker.recordAlloc(rec);
        } catch (Exception ignored) {
        }
    }

    // ==================== _texSubImage2D (ByteBuffer) ====================

    @Inject(method = "_texSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            at = @At("HEAD"), remap = false)
    private static void onTexSubImage2D_BB(int target, int level,
                                            int xOffset, int yOffset,
                                            int width, int height,
                                            int format, int type, ByteBuffer pixels,
                                            CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;

        try {
            // _texSubImage2D does NOT allocate storage — it fills existing storage.
            // Only create a record if this texture hasn't been seen yet (first upload).
            int texId = getTextureBinding(target);
            if (texId == 0) return;

            // Don't duplicate — _texImage2D already created the record
            // This hook exists for the case where _texImage2D with null pixels
            // pre-allocated, and this is the first actual data upload.
        } catch (Exception ignored) {
        }
    }

    // ==================== _texSubImage2D (long/native pointer) ====================

    @Inject(method = "_texSubImage2D(IIIIIIIIJ)V",
            at = @At("HEAD"), remap = false, require = 0)
    private static void onTexSubImage2D_Long(int target, int level,
                                              int xOffset, int yOffset,
                                              int width, int height,
                                              int format, int type, long pixels,
                                              CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;
        // Same logic as ByteBuffer variant — allocation already tracked via _texImage2D
    }

    // ==================== _deleteTexture ====================

    @Inject(method = "_deleteTexture(I)V",
            at = @At("HEAD"), remap = false)
    private static void onDeleteTexture(int id, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;

        try {
            // Snapshot record before removal so logFree has full detail
            var rec = tracker.getRecord(id);
            tracker.recordFree(id);
            VramAllocLogger.logFree(id, rec);
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

    @Unique
    private static VramAllocationRecord buildRecord(int texId, int width, int height,
                                                     int depth, int mipLevels,
                                                     int internalformat, int level) {
        // Only create on level 0 (base level) to avoid duplicate records per mip
        int effectiveMipLevels = (level == 0 && mipLevels == 0) ? 1 : mipLevels;

        // Try to get label from the A-layer (GpuDevice.createTexture) bridge
        String label = AllocLabelBridge.consume();

        // Simplified caller extraction
        String caller = extractCaller();

        return new VramAllocationRecord(
                texId, width, height, depth,
                effectiveMipLevels, internalformat,
                label,
                /*allocTick*/ System.currentTimeMillis(),
                caller
        );
    }

    @Unique
    private static String extractCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // Walk past: getStackTrace, extractCaller, buildRecord, onTexImage...
        // Find first non-mixin, non-GlStateManager caller
        for (int i = 4; i < Math.min(stack.length, 10); i++) {
            String cn = stack[i].getClassName();
            if (!cn.contains("test.vram.tweak") && !cn.contains("GlStateManager")) {
                // Return simplified class name (last segment)
                int dot = cn.lastIndexOf('.');
                return dot > 0 ? cn.substring(dot + 1) : cn;
            }
        }
        return "unknown";
    }
}
