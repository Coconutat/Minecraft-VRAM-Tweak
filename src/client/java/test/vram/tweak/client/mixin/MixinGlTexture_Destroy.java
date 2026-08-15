package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.allocation.AllocationKind;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.allocation.VramAllocLogger;
import test.vram.tweak.diagnostic.MetricsEngine;

/**
 * Blaze3D-layer texture free tracking.
 *
 * <p>{@code GlTexture.destroyImmediately()} is the single point where the GL id
 * is actually deleted (both from {@code close()} and deferred {@code removeViews()}).
 * Hooking HEAD means we mark freed exactly when GL deletion happens.</p>
 */
@Mixin(GlTexture.class)
public class MixinGlTexture_Destroy {

    @Inject(method = "destroyImmediately()V", at = @At("HEAD"), remap = false)
    private void onDestroy(CallbackInfo ci) {
        MetricsEngine.textureFrees.incrementAndGet();

        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;
        try {
            int id = ((GlTexture)(Object)this).glId();
            var rec = tracker.getRecord(AllocationKind.TEXTURE, id);
            tracker.recordFree(AllocationKind.TEXTURE, id);
            VramAllocLogger.logFree(id, rec);
        } catch (Exception ignored) {
        }
    }
}
