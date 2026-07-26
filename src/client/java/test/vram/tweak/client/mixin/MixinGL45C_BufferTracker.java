package test.vram.tweak.client.mixin;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.allocation.VramAllocationTracker;

/**
 * Tracks GPU buffer allocations via the OpenGL DSA (Direct State Access) path.
 */
@Mixin(value = GL45C.class, remap = false)
public class MixinGL45C_BufferTracker {

    @Inject(method = "glNamedBufferData(IJI)V", at = @At("TAIL"), remap = false)
    private static void onNamedBufferData_Long(int buffer, long size, int usage, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || size <= 0 || buffer <= 0) return;
        try {
            int target = queryBufferTarget(buffer);
            BufferTrackUtil.trackBuffer(tracker, buffer, target, size);
        } catch (Exception ignored) {}
    }

    @Inject(method = "glNamedBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("TAIL"), remap = false, require = 0)
    private static void onNamedBufferData_BB(int buffer, ByteBuffer data, int usage, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || data == null || data.capacity() <= 0 || buffer <= 0) return;
        try {
            int target = queryBufferTarget(buffer);
            BufferTrackUtil.trackBuffer(tracker, buffer, target, data.capacity());
        } catch (Exception ignored) {}
    }

    @Inject(method = "glNamedBufferStorage(IJI)V", at = @At("TAIL"), remap = false, require = 0)
    private static void onNamedBufferStorage(int buffer, long size, int flags, CallbackInfo ci) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive() || size <= 0 || buffer <= 0) return;
        try {
            int target = queryBufferTarget(buffer);
            BufferTrackUtil.trackBuffer(tracker, buffer, target, size);
        } catch (Exception ignored) {}
    }

    private static int queryBufferTarget(int buffer) {
        if (GL15C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING) == buffer)
            return GL15C.GL_ARRAY_BUFFER;
        if (GL15C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == buffer)
            return GL15C.GL_ELEMENT_ARRAY_BUFFER;
        if (GL31C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING) == buffer)
            return GL31C.GL_UNIFORM_BUFFER;
        if (GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING) == buffer)
            return GL43C.GL_SHADER_STORAGE_BUFFER;
        if (GL21C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING) == buffer)
            return GL21C.GL_PIXEL_PACK_BUFFER;
        if (GL21C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING) == buffer)
            return GL21C.GL_PIXEL_UNPACK_BUFFER;
        return 0;
    }
}
