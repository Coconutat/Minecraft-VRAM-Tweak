package test.vram.tweak.client.mixin;

import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlStateManager;

import test.vram.tweak.allocation.AllocationCategory;
import test.vram.tweak.allocation.VramAllocationTracker;

/**
 * D-layer: marks textures as render-target attachments when they are bound to
 * a framebuffer.
 *
 * <p>This hook fires <em>after</em> the B-layer ({@link MixinGlStateManager_AllocTracker})
 * has already recorded the allocation via {@code _texImage2D}. At that point the
 * category defaults to a texture type; this hook reclassifies the record to the
 * correct render-target category.</p>
 *
 * <p><b>Classification rules:</b>
 * <ul>
 *   <li>GL_DEPTH_ATTACHMENT / GL_DEPTH_STENCIL_ATTACHMENT → {@code RENDER_TARGET_DEPTH}</li>
 *   <li>Any other attachment → {@code RENDER_TARGET_COLOR}</li>
 * </ul></p>
 *
 * <p>Shadow maps are classified as {@code RENDER_TARGET_SHADOW} when the source tag
 * matches shadow patterns. If the source tag is not yet set at attachment time,
 * this hook marks as {@code RENDER_TARGET_DEPTH} which is a reasonable default
 * for shadow maps.</p>
 *
 * @see MixinGlStateManager_AllocTracker B-layer
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
                tracker.markAsRenderTarget(texture, rtCategory);
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
