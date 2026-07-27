package test.vram.tweak.allocation;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;

/**
 * Shared buffer tracking logic used by multiple buffer-tracking mixins.
 * Must NOT be placed in the mixin package — Fabric Mixin will attempt
 * to transform it and fail on LWJGL class references.
 */
public final class BufferTrackUtil {

    private BufferTrackUtil() {}

    public static void trackBuffer(VramAllocationTracker tracker, int id, int target, long size) {
        var existing = tracker.getRecord(id);
        if (existing != null) {
            tracker.recordFree(id);
        }

        AllocationCategory cat = targetToCategory(target);
        VramAllocationRecord rec = new VramAllocationRecord(
                id,
                0, 0, 1,           // width=0, height=0 (buffer, not texture)
                0,                  // mipLevels
                0,                  // glInternalFormat (not used for buffers)
                null,               // label
                System.currentTimeMillis(),
                extractCaller()
        );
        rec.setCategory(cat);
        rec.setEstimatedBytes(size);
        tracker.recordAlloc(rec);
        VramAllocLogger.logAllocBuf(rec);
    }

    public static int getBufferBinding(int target) {
        return switch (target) {
            case GL15C.GL_ARRAY_BUFFER         -> GL15C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            case GL15C.GL_ELEMENT_ARRAY_BUFFER -> GL15C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            case GL31C.GL_UNIFORM_BUFFER       -> GL31C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING);
            case GL21C.GL_PIXEL_PACK_BUFFER    -> GL21C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            case GL21C.GL_PIXEL_UNPACK_BUFFER  -> GL21C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            case GL43C.GL_SHADER_STORAGE_BUFFER -> GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
            case GL30C.GL_TRANSFORM_FEEDBACK_BUFFER -> GL30C.glGetInteger(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
            default -> 0;
        };
    }

    static AllocationCategory targetToCategory(int target) {
        return switch (target) {
            case GL15C.GL_ARRAY_BUFFER,
                 GL15C.GL_ELEMENT_ARRAY_BUFFER,
                 GL30C.GL_TRANSFORM_FEEDBACK_BUFFER -> AllocationCategory.BUFFER_GEOMETRY;
            case GL31C.GL_UNIFORM_BUFFER -> AllocationCategory.BUFFER_UNIFORM;
            case GL21C.GL_PIXEL_PACK_BUFFER,
                 GL21C.GL_PIXEL_UNPACK_BUFFER -> AllocationCategory.BUFFER_PIXEL;
            case GL43C.GL_SHADER_STORAGE_BUFFER -> AllocationCategory.BUFFER_STORAGE;
            default -> classifyByCaller();
        };
    }

    static AllocationCategory classifyByCaller() {
        var stack = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(stack.length, 15); i++) {
            String cn = stack[i].getClassName();
            if (cn.contains("sodium") || cn.contains("caffeinemc")) {
                return AllocationCategory.BUFFER_GEOMETRY;
            }
            if (cn.contains("irisshaders") || cn.contains("iris")) {
                return AllocationCategory.BUFFER_STORAGE;
            }
            if (cn.contains("distanthorizons") || cn.contains("seibel")) {
                return AllocationCategory.BUFFER_GEOMETRY;
            }
            if (cn.contains("blaze3d") || cn.contains("minecraft")) {
                return AllocationCategory.BUFFER_GEOMETRY;
            }
        }
        return AllocationCategory.BUFFER_GEOMETRY;
    }

    static String extractCaller() {
        var stack = Thread.currentThread().getStackTrace();
        for (int i = 4; i < Math.min(stack.length, 12); i++) {
            String cn = stack[i].getClassName();
            if (!cn.contains("test.vram.tweak") && !cn.contains("GL") && !cn.contains("lwjgl")) {
                int dot = cn.lastIndexOf('.');
                return dot >= 0 ? cn.substring(dot + 1) : cn;
            }
        }
        return "?";
    }
}
