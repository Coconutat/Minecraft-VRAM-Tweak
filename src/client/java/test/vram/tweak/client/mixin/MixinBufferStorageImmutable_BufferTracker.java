package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import test.vram.tweak.allocation.BufferTrackUtil;
import test.vram.tweak.allocation.VramAllocationTracker;

/**
 * Hooks BufferStorage$Immutable.createBuffer — the immutable (glBufferStorage) path.
 * AMD GPUs with GL_ARB_buffer_storage use this path.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.BufferStorage$Immutable", remap = false)
public class MixinBufferStorageImmutable_BufferTracker {

    @Inject(
        method = "createBuffer(Lcom/mojang/blaze3d/opengl/DirectStateAccess;IJ)"
                + "Lcom/mojang/blaze3d/opengl/GlBuffer;",
        at = @At("RETURN"),
        remap = false)
    private void onCreateBuffer(DirectStateAccess dsa, int usage, long size,
                                 CallbackInfoReturnable<GlBuffer> cir) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || size <= 0) return;
        try {
            GlBuffer buf = cir.getReturnValue();
            if (buf == null) return;
            int handle = buf.handle();
            if (handle <= 0) return;
            BufferTrackUtil.trackBuffer(tracker, handle, 0, size);
        } catch (Exception ignored) {}
    }
}
