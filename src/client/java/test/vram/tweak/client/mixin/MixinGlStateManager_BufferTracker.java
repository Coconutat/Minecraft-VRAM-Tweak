package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL15C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.allocation.BufferTrackUtil;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.allocation.VramAllocLogger;

/**
 * Tracks GPU buffer allocations via the traditional bind-to-target path.
 *
 * <p>Hooks {@code GL15C} methods used by the emulated DSA path
 * ({@code DirectStateAccess$Emulated}) and older mods.</p>
 *
 * <p><b>DSA path</b> ({@code DirectStateAccess$Core}) is handled by
 * {@link MixinGL45C_BufferTracker} targeting {@code GL45C}.</p>
 */
@Mixin(value = GL15C.class, remap = false)
public class MixinGlStateManager_BufferTracker {

    @Inject(method = "glBufferData(IJI)V", at = @At("TAIL"), remap = false)
    private static void onBufferData_Long(int target, long size, int usage, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || size <= 0) return;
        try {
            int buf = BufferTrackUtil.getBufferBinding(target);
            if (buf == 0) return;
            BufferTrackUtil.trackBuffer(tracker, buf, target, size);
        } catch (Exception ignored) {}
    }

    @Inject(method = "glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("TAIL"), remap = false)
    private static void onBufferData_BB(int target, ByteBuffer data, int usage, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || data == null || data.capacity() <= 0) return;
        try {
            int buf = BufferTrackUtil.getBufferBinding(target);
            if (buf == 0) return;
            BufferTrackUtil.trackBuffer(tracker, buf, target, data.capacity());
        } catch (Exception ignored) {}
    }

    @Inject(method = "glDeleteBuffers(I)V", at = @At("HEAD"), remap = false)
    private static void onDeleteBuffer(int buffer, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) return;
        try {
            var rec = tracker.getRecord(buffer);
            tracker.recordFree(buffer);
            VramAllocLogger.logFree(buffer, rec);
        } catch (Exception ignored) {}
    }
}
