package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import test.vram.tweak.allocation.VramAllocationTracker;

/**
 * Hooks BufferStorage$Mutable.createBuffer — the exact point where
 * GlBuffer$Direct is instantiated. Verified via javap decompilation:
 *
 * <pre>
 * BufferStorage$Mutable.createBuffer(DirectStateAccess, int, long):
 *   handle = dsa.createBuffer()
 *   dsa.bufferData(handle, size, usage)
 *   return new GlBuffer$Direct(dsa, usage, size, handle, false)
 * </pre>
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.BufferStorage$Mutable", remap = false)
public class MixinGlBuffer_Init_BufferTracker {

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
