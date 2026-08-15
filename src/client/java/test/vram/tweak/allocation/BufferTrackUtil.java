package test.vram.tweak.allocation;

import com.mojang.blaze3d.buffers.GpuBuffer;

/**
 * Shared buffer tracking logic used by multiple buffer-tracking mixins.
 * Must NOT be placed in the mixin package — Fabric Mixin will attempt
 * to transform it and fail on LWJGL class references.
 */
public final class BufferTrackUtil {

    private BufferTrackUtil() {}

    /**
     * @param usage GpuBuffer usage bits（见 {@link GpuBuffer}）
     */
    public static void trackBuffer(VramAllocationTracker tracker, int id, int usage, long size) {
        var existing = tracker.getRecord(AllocationKind.BUFFER, id);
        if (existing != null) {
            tracker.recordFree(AllocationKind.BUFFER, id);
        }

        AllocationCategory cat = usageToCategory(usage);
        VramAllocationRecord rec = new VramAllocationRecord(
                AllocationKind.BUFFER,
                id,
                0, 0, 1,           // width=0, height=0 (buffer, not texture)
                1,                  // mipLevels (unused, clamped to 1)
                1.0f,               // bytesPerPixel (overridden below)
                null,               // formatName (buffer)
                null,               // label
                System.currentTimeMillis(),
                extractCaller()
        );
        rec.setCategory(cat);
        rec.setEstimatedBytes(size);
        tracker.recordAlloc(rec);
        VramAllocLogger.logAllocBuf(rec);
    }

    static AllocationCategory usageToCategory(int usage) {
        if (usage == 0) return classifyByCaller();
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0 || (usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            return AllocationCategory.BUFFER_UNIFORM;
        }
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0 || (usage & GpuBuffer.USAGE_INDEX) != 0) {
            return AllocationCategory.BUFFER_GEOMETRY;
        }
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0) {
            return AllocationCategory.BUFFER_PIXEL;
        }
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0 && (usage & GpuBuffer.USAGE_MAP_WRITE) != 0) {
            return AllocationCategory.BUFFER_PIXEL;
        }
        return classifyByCaller();
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
