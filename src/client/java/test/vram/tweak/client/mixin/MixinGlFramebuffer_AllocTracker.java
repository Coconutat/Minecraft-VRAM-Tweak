package test.vram.tweak.client.mixin;

import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlStateManager;

import test.vram.tweak.allocation.AllocationCategory;
import test.vram.tweak.allocation.SourceTag;
import test.vram.tweak.allocation.VramAllocationTracker;

/**
 * D-layer: marks textures as render-target attachments when they are bound to
 * a framebuffer.
 *
 * <p>Allocation records are created at the Blaze3D layer
 * ({@code GpuDevice.createTexture}); this hook reclassifies the record when the
 * texture is attached to a framebuffer.</p>
 *
 * <p><b>Classification rules:</b>
 * <ul>
 *   <li>GL_DEPTH_ATTACHMENT / GL_DEPTH_STENCIL_ATTACHMENT → {@code RENDER_TARGET_DEPTH}</li>
 *   <li>Any other attachment → {@code RENDER_TARGET_COLOR}</li>
 * </ul></p>
 */
@Mixin(GlStateManager.class)
public class MixinGlFramebuffer_AllocTracker {

    /**
     * Hook {@code GlStateManager._glFramebufferTexture2D}.
     *
     * <p>Signature (Mojang mappings for MC 1.21.11):
     * {@code _glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level)}</p>
     *
     * <p>The {@code texture} parameter is the GL texture object ID, matching what we
     * recorded in the B-layer hook.</p>
     */
    @Inject(method = "_glFramebufferTexture2D(IIIII)V",
            at = @At("HEAD"), remap = false)
    private static void onFramebufferTexture2D(int target, int attachment,
                                                int textarget, int texture, int level,
                                                CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;

        try {
            AllocationCategory rtCategory = classifyAttachment(attachment);
            if (rtCategory != null) {
                // P0 fix: never label every attachment as Iris. The record keeps its
                // label-derived source; unknown sources stay unknown. Stack-based
                // Iris/vanilla discrimination lands in P1.
                tracker.markAsRenderTarget(texture, rtCategory, SourceTag.UNKNOWN_SOURCE);
            }
        } catch (Exception ignored) {
            // Don't crash the game
        }
    }

    @Unique
    private static AllocationCategory classifyAttachment(int attachment) {
        if (attachment == GL30C.GL_DEPTH_ATTACHMENT
                || attachment == GL30C.GL_DEPTH_STENCIL_ATTACHMENT) {
            return AllocationCategory.RENDER_TARGET_DEPTH;
        }
        // For color attachments, category stays as RENDER_TARGET_COLOR
        // (set by SourceTag-based classification in VramAllocationRecord)
        return AllocationCategory.RENDER_TARGET_COLOR;
    }
}
