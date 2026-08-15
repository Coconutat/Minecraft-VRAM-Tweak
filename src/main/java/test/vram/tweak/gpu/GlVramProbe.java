package test.vram.tweak.gpu;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * OpenGL 后端的 VRAM 查询实现。
 *
 * <p>AMD 口径统一：总量优先用模型查表（{@link AmdVramLookup}），其次 NVX，最后
 * 启动期 ATI 空闲值四舍五入；空闲值在 ATI 原始读数之上做低水位 + EMA 平滑，
 * 避免"可回收内存"造成的空闲虚高。</p>
 */
public final class GlVramProbe implements VramProbe {
    public static final GlVramProbe INSTANCE = new GlVramProbe();

    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FB;
    private static final int GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;

    /** 已知 VRAM 容量（KB） */
    private static final long[] KNOWN_VRAM_KB = {
        1024L * 1024, 2048L * 1024, 3072L * 1024, 4096L * 1024,
        5120L * 1024, 6144L * 1024, 7168L * 1024, 8192L * 1024,
        10240L * 1024, 12288L * 1024, 16384L * 1024, 24576L * 1024, 32768L * 1024
    };

    private static final long MAX_SANE_KB = 128L * 1024 * 1024; // 128 GB

    private final Set<String> checkedExtensions = new HashSet<>();
    private final Set<String> availableExtensions = new HashSet<>();

    // AMD 校准状态
    private long amdTotalKB;
    private long amdMinFreeKB;
    private double amdSmoothedUsedKB;
    private long amdPrevFreeKB;
    private boolean amdCalibrated;

    private GlVramProbe() {}

    // ---- 公共 API ----

    @Override
    public long freeKB() {
        try {
            return switch (GPUDetector.getGPU()) {
                case AMD -> queryFreeKB_AMD();
                case NVIDIA -> queryFreeKB_NVIDIA();
                case INTEL -> queryFreeKB_INTEL();
                default -> -1;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public long totalKB() {
        try {
            return switch (GPUDetector.getGPU()) {
                case AMD -> queryTotalKB_AMD();
                case NVIDIA -> queryTotalKB_NVIDIA();
                case INTEL -> queryTotalKB_INTEL();
                default -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String source() {
        return switch (GPUDetector.getGPU()) {
            case AMD -> amdTotalKB > 0 ? "AMD model lookup" : "GL_ATI_meminfo";
            case NVIDIA -> "GL_NVX_gpu_memory_info";
            case INTEL -> hasGLExt("GL_NVX_gpu_memory_info") ? "GL_NVX_gpu_memory_info" : "GL_ATI_meminfo";
            default -> "none";
        };
    }

    // ---- AMD ----

    private long queryFreeKB_AMD() {
        long totalKB = queryTotalKB_AMD();
        if (totalKB <= 0) return -1;

        long freeKB = queryRawFreeKB_AMD();
        if (freeKB <= 0 || freeKB > MAX_SANE_KB) return -1;

        if (!amdCalibrated) {
            amdMinFreeKB = freeKB;
            amdSmoothedUsedKB = totalKB - freeKB;
            amdPrevFreeKB = freeKB;
            amdCalibrated = true;
            return freeKB;
        }

        if (freeKB < amdMinFreeKB) {
            amdMinFreeKB = freeKB;
        }

        double rawUsedKB = totalKB - freeKB;
        double smoothed;
        if (rawUsedKB > amdSmoothedUsedKB) {
            // 用量上升立即信任
            smoothed = rawUsedKB;
        } else {
            long freeDelta = freeKB - amdPrevFreeKB;
            if (freeDelta > totalKB / 5) {
                // 大量空闲释放（退出世界 / 清理资源）：缓慢收敛
                smoothed = amdSmoothedUsedKB * 0.98 + rawUsedKB * 0.02;
            } else {
                smoothed = amdSmoothedUsedKB * 0.85 + rawUsedKB * 0.15;
            }
        }
        smoothed = Math.max(0, Math.min(smoothed, totalKB * 0.98));

        amdSmoothedUsedKB = smoothed;
        amdPrevFreeKB = freeKB;
        return (long) (totalKB - smoothed);
    }

    private long queryRawFreeKB_AMD() {
        int[] vals = new int[4];
        GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, vals);
        return vals[0] & 0xFFFFFFFFL;
    }

    private long queryTotalKB_AMD() {
        if (amdTotalKB > 0) return amdTotalKB;

        long freeKB = queryRawFreeKB_AMD();
        long freeMB = freeKB > 0 && freeKB <= MAX_SANE_KB ? freeKB / 1024 : 0;

        // 1. 模型查表（最准），freeMB 用于多容量变体消歧
        var info = GPUDetector.getGPUInfo();
        if (info != null) {
            long knownMB = AmdVramLookup.lookup(info.getAmdArch(), info.getAmdModelName(), freeMB);
            if (knownMB > 0) {
                amdTotalKB = knownMB * 1024;
                return amdTotalKB;
            }
        }

        // 2. NVX 总量（部分新驱动暴露）
        if (hasGLExt("GL_NVX_gpu_memory_info")) {
            int[] v = new int[1];
            GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, v);
            long kb = v[0] & 0xFFFFFFFFL;
            if (kb > 0 && kb <= MAX_SANE_KB) {
                amdTotalKB = kb;
                return amdTotalKB;
            }
        }

        // 3. 启动期 ATI 空闲值四舍五入到已知容量，并预留 2.5% 驱动开销
        if (freeKB > 0 && freeKB <= MAX_SANE_KB) {
            amdTotalKB = roundTotalKB(freeKB);
        }
        return amdTotalKB;
    }

    // ---- NVIDIA ----

    private long queryFreeKB_NVIDIA() {
        int[] v = new int[1];
        GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, v);
        long kb = v[0] & 0xFFFFFFFFL;
        return kb > 0 && kb <= MAX_SANE_KB ? kb : -1;
    }

    private long queryTotalKB_NVIDIA() {
        int[] v = new int[1];
        GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, v);
        long kb = v[0] & 0xFFFFFFFFL;
        return kb > 0 && kb <= MAX_SANE_KB ? kb : 0;
    }

    // ---- Intel ----

    private long queryFreeKB_INTEL() {
        if (hasGLExt("GL_NVX_gpu_memory_info")) {
            long kb = queryFreeKB_NVIDIA();
            if (kb > 0) return kb;
        }
        if (hasGLExt("GL_ATI_meminfo")) {
            long kb = queryRawFreeKB_AMD();
            return kb > 0 && kb <= MAX_SANE_KB ? kb : -1;
        }
        return -1;
    }

    private long queryTotalKB_INTEL() {
        if (hasGLExt("GL_NVX_gpu_memory_info")) {
            long kb = queryTotalKB_NVIDIA();
            if (kb > 0) return kb;
        }
        long freeKB = queryFreeKB_INTEL();
        if (freeKB > 0) {
            return Math.max(freeKB, 2048L * 1024);
        }
        return 0;
    }

    // ---- 通用 ----

    private boolean hasGLExt(String ext) {
        if (checkedExtensions.contains(ext)) return availableExtensions.contains(ext);
        checkedExtensions.add(ext);
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            if (ext.equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, i))) {
                availableExtensions.add(ext);
                return true;
            }
        }
        return false;
    }

    /** 把估算总量四舍五入到已知容量，并预留约 2.5% 驱动开销。 */
    private static long roundTotalKB(long estimatedKB) {
        long closest = KNOWN_VRAM_KB[0];
        long minDiff = Long.MAX_VALUE;
        for (long size : KNOWN_VRAM_KB) {
            long diff = Math.abs(estimatedKB - size);
            if (diff < minDiff) { minDiff = diff; closest = size; }
        }
        return closest * 975 / 1000;
    }
}
