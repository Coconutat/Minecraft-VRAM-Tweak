package test.vram.tweak.vram;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.gpu.GPUDetector;

/**
 * VRAM optimization logic + budget tracking, merged.
 *
 * Three features:
 * 1. Shadow map size cap 鈥?clamp texture dimensions to maxShadowMapSize
 * 2. Format downscale 鈥?RGBA16F 鈫?RGBA8 for color render targets
 * 3. Budget tracking 鈥?GL_ATI_meminfo polling, threshold warnings
 */
public class VRAMOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/vram");
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FB;
    // NVIDIA NVX_gpu_memory_info
    private static final int GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;

    private static boolean enabled;
    private static boolean shadowCapEnabled;
    private static boolean formatDownscale;
    private static boolean depthDownscale;
    private static int maxShadowSize;
    private static boolean budgetTracking;
    private static int budgetPercent;

    // throttle logs
    private static int downscaleLogs;
    private static int shadowCapLogs;
    private static int atlasCapLogs;
    private static final int MAX_LOGS = 10;

    // budget warning cooldown
    private static boolean overBudget;
    private static int cooldown;

    // GL extension cache
    private static Set<String> checkedExtensions = new HashSet<>();
    private static Set<String> availableExtensions = new HashSet<>();

    private static boolean hasGLExt(String ext) {
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

    public static void initialize() {
        reload();
    }

    /** Re-read config. Callable at runtime (e.g. benchmark toggle). */
    public static void reload() {
        var cfg = VRAMConfig.getInstance().vram;
        enabled = cfg.enabled;
        shadowCapEnabled = cfg.shadowCapEnabled;
        formatDownscale = cfg.formatDownscale;
        depthDownscale = cfg.depthDownscale;
        maxShadowSize = cfg.shadowMapMaxSize;
        budgetTracking = cfg.budgetTracking;
        budgetPercent = cfg.budgetWarningPercent;

        LOGGER.info("[TRACE] VRAMOptimizer.reload() called. Thread={}", Thread.currentThread().getName());
        if (enabled) {
            LOGGER.info("VRAM optimizer ON. shadowCap={}, formatDownscale={}, depthDownscale={}, budget={}%",
                    maxShadowSize, formatDownscale, depthDownscale, budgetPercent);
        } else {
            LOGGER.info("VRAM optimizer OFF. Existing textures unchanged until game restart.");
        }
    }

    public static boolean isEnabled() { return enabled; }

    /** Query VRAM free. Returns KB free, or -1 on failure. */
    public static long queryFreeVRAM() {
        return switch (GPUDetector.getGPU()) {
            case AMD -> queryFreeVRAM_AMD();
            case NVIDIA -> queryFreeVRAM_NVIDIA();
            case INTEL -> queryFreeVRAM_INTEL();
            default -> -1;
        };
    }

    // All internal query methods return KB.
    private static long queryFreeVRAM_AMD() {
        try {
            int[] result = new int[4];
            GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, result);
            // GL_ATI_meminfo slot 0 = free memory in KB. Sign extension fix.
            return result[0] & 0xFFFFFFFFL;
        } catch (Exception e) { return -1; }
    }

    private static long queryFreeVRAM_NVIDIA() {
        try {
            int[] result = new int[1];
            GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, result);
            return result[0] & 0xFFFFFFFFL;
        } catch (Exception e) { return -1; }
    }

    // Intel also supports GL_ATI_meminfo on many iGPUs; fallback to same path.
    private static long queryFreeVRAM_INTEL() {
        // ponytail: try NVX first (modern Intel Arc DG2+), fallback to ATI
        if (hasGLExt("GL_NVX_gpu_memory_info")) {
            try {
                int[] result = new int[1];
                GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX, result);
                long kb = result[0] & 0xFFFFFFFFL;
                if (kb > 0) return kb;
            } catch (Exception ignored) {}
        }
        if (hasGLExt("GL_ATI_meminfo")) {
            try {
                int[] result = new int[4];
                GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, result);
                return result[0] & 0xFFFFFFFFL;
            } catch (Exception e) { return -1; }
        }
        return -1;
    }

    /** GPU-aware total VRAM in MB. */
    public static long queryTotalVRAM() {
        try {
            return switch (GPUDetector.getGPU()) {
                case AMD -> {
                    // GL_ATI_meminfo only reports free VRAM — total not directly queryable.
                    long freeKB = queryFreeVRAM_AMD();
                    yield Math.max(freeKB, 8192L * 1024) / 1024; // KB → MB
                }
                case NVIDIA -> {
                    int[] result = new int[1];
                    GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, result);
                    long kb = result[0] & 0xFFFFFFFFL;
                    yield kb / 1024; // KB → MB
                }
                case INTEL -> {
                    if (hasGLExt("GL_NVX_gpu_memory_info")) {
                        int[] result = new int[1];
                        GL11.glGetIntegerv(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, result);
                        long kb = result[0] & 0xFFFFFFFFL;
                        if (kb > 0) yield kb / 1024;
                    }
                    // Fallback: estimate from free KB
                    long freeKB = queryFreeVRAM_INTEL();
                    yield freeKB > 0 ? Math.max(freeKB, 2048L * 1024) / 1024 : 0L;
                }
                default -> 0L;
            };
        } catch (Exception e) { return 0; }
    }

    /** Estimate total VRAM in MB. */
    public static long estimateTotalMB() {
        long total = queryTotalVRAM();
        if (total > 0) return total;
        long freeKB = queryFreeVRAM();
        if (freeKB <= 0) return 0;
        return Math.max(freeKB, 8192L * 1024) / 1024;
    }

    /** Called each frame. Logs warnings when VRAM exceeds threshold. */
    public static void onFrameEnd() {
        if (!enabled || !budgetTracking) return;

        long freeKB = queryFreeVRAM();
        long totalMB = queryTotalVRAM();
        if (freeKB <= 0 || totalMB <= 0) return;

        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * budgetPercent / 100;

        MetricsEngine.setVramUsed(usedMB);

        if (usedMB > thresholdMB) {
            if (!overBudget && cooldown <= 0) {
                LOGGER.warn("VRAM BUDGET: {}MB / {}MB ({}%). Consider reducing settings.",
                        usedMB, totalMB, usedMB * 100 / totalMB);
                VerificationLogger.logBudgetWarning(usedMB, totalMB, (int)(usedMB * 100 / totalMB));
                overBudget = true;
                cooldown = 600;
            } else {
                cooldown--;
            }
        } else {
            if (overBudget) {
                LOGGER.info("VRAM budget restored: {}MB (below {}%)", usedMB, budgetPercent);
                overBudget = false;
            }
        }
    }

    // ---- format downscale ----

    /** Only downscale 16-bit color formats. 1.21.11 TextureFormat only has RGBA8 — no-op. */
    public static boolean shouldDownscaleFormat(String formatName) {
        if (!enabled || !formatDownscale || formatName == null) return false;
        if (formatName.startsWith("D") || formatName.startsWith("S")) return false;
        return formatName.contains("16"); // ponytail: dormant, TextureFormat has no 16-bit in 1.21.11
    }

    public static void logDownscale(String source, String original) {
        VerificationLogger.logFormatDownscale(source, original, "RGBA8");
        if (downscaleLogs < MAX_LOGS) {
            LOGGER.debug("Format downscale: {} ({} → RGBA8)", source, original);
            downscaleLogs++;
        } else if (downscaleLogs == MAX_LOGS) {
            LOGGER.debug("Format downscale log limit reached.");
            downscaleLogs++;
        }
    }

    // ---- shadow map cap (independent of formatDownscale) ----

    public static int capSize(int size) {
        return Math.min(size, maxShadowSize);
    }

    /** Only cap if enabled + shadowCapEnabled AND the texture exceeds maxShadowSize AND is square (shadow maps are always square). */
    public static boolean shouldCap(int width, int height) {
        return enabled && shadowCapEnabled && width == height && width > maxShadowSize;
    }

    /** Depth formats — 1.21.11 TextureFormat: DEPTH32 starts with "D". */
    public static boolean isDepthFormat(String formatName) {
        return formatName != null && formatName.startsWith("D");
    }

    // ---- depth downscale ----

    /** 1.21.11 TextureFormat only has DEPTH32, no lower depth format — no-op. */
    public static boolean shouldDownscaleDepth(String formatName) {
        return enabled && depthDownscale && "DEPTH32".equals(formatName);
    }

    public static void logDepthDownscale(String original) {
        VerificationLogger.logDepthDownscale(original, "DEPTH32");
        if (downscaleLogs < MAX_LOGS) {
            LOGGER.debug("Depth downscale: {} (1.21.11: no lower depth format)", original);
            downscaleLogs++;
        } else if (downscaleLogs == MAX_LOGS) {
            LOGGER.debug("Downscale log limit reached.");
            downscaleLogs++;
        }
    }

    public static void logShadowCap(int origW, int origH, int newW, int newH) {
        VerificationLogger.logShadowCap(origW, origH, newW, newH);
        if (shadowCapLogs < MAX_LOGS) {
            LOGGER.debug("Shadow cap: {}x{} 鈫?{}x{}", origW, origH, newW, newH);
            shadowCapLogs++;
        } else if (shadowCapLogs == MAX_LOGS) {
            LOGGER.debug("Shadow cap log limit reached.");
            shadowCapLogs++;
        }
    }
    // ---- atlas tracking ----

    /** Log every atlas texture creation (always, no limit — only ~3-5 per session). */
    public static void logAtlasTracked(String name, int width, int height, String format) {
        VerificationLogger.logAtlasTracked(name, width, height, format);
        LOGGER.info("Atlas tracked: {} {}×{} {}", name, width, height, format);
    }

    /** Log atlas dimension cap (first 10 full detail, then suppressed). */
    public static void logAtlasCap(String name, String dim, int orig, int capped) {
        VerificationLogger.logAtlasCap(name, dim, orig, capped);
        if (atlasCapLogs < MAX_LOGS) {
            LOGGER.info("Atlas {} cap: {} {}→{}", dim, name, orig, capped);
            atlasCapLogs++;
        } else if (atlasCapLogs == MAX_LOGS) {
            LOGGER.info("Atlas cap log limit reached.");
            atlasCapLogs++;
        }
    }}
