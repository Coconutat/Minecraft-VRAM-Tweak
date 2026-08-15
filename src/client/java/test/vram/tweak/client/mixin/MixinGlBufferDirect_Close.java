package test.vram.tweak.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.opengl.GlBuffer;

import test.vram.tweak.allocation.AllocationKind;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.allocation.VramAllocLogger;

/**
 * Blaze3D-layer buffer free tracking.
 *
 * <p>{@code GlBuffer$Direct.close()} calls {@code GlStateManager._glDeleteBuffers(handle())}
 * before returning; hooking TAIL marks the allocation freed with the same GL id
 * that was recorded at creation.</p>
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlBuffer$Direct", remap = false)
public class MixinGlBufferDirect_Close {

    @Inject(method = "close()V", at = @At("TAIL"), remap = false)
    private void onClose(CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;
        try {
            int handle = ((GlBuffer)(Object)this).handle();
            var rec = tracker.getRecord(AllocationKind.BUFFER, handle);
            tracker.recordFree(AllocationKind.BUFFER, handle);
            VramAllocLogger.logFree(handle, rec);
        } catch (Exception ignored) {
        }
    }
}
