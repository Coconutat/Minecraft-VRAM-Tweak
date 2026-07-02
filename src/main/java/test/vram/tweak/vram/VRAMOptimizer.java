package test.vram.tweak.vram;

import org.lwjgl.opengl.GL11;
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

    private static boolean enabled;
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

    public static void initialize() {
        reload();
    }

    /** Re-read config. Callable at runtime (e.g. benchmark toggle). */
    public static void reload() {
        var cfg = VRAMConfig.getInstance().vram;
        enabled = cfg.enabled;
        formatDownscale = cfg.formatDownscale;
        depthDownscale = cfg.depthDownscale;
        maxShadowSize = cfg.shadowMapMaxSize;
        budgetTracking = cfg.budgetTracking;
        budgetPercent = cfg.budgetWarningPercent;

        if (enabled) {
            LOGGER.info("VRAM optimizer ON. shadowCap={}, formatDownscale={}, depthDownscale={}, budget={}%",
                    maxShadowSize, formatDownscale, depthDownscale, budgetPercent);
        } else {
            LOGGER.info("VRAM optimizer OFF.");
        }
    }

    public static boolean isEnabled() { return enabled; }

    /** Query VRAM free via GL_ATI_meminfo. Returns KB free, or -1 on failure. Public for metrics. */
    public static long queryFreeVRAM() {
        try {
            int[] result = new int[4];
            GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, result);
            return (long) result[0] * 1024L; // KB 鈫?bytes
        } catch (Exception e) {
            return -1;
        }
    }

    /** Estimate total VRAM. Rough: free / ((100-budget%) / 100). */
    public static long estimateTotalMB() {
        long free = queryFreeVRAM();
        if (free <= 0) return 0;
        return free / 1024 / 1024;
    }

    /** Called each frame. Logs warnings when VRAM exceeds threshold. */
    public static void onFrameEnd() {
        if (!enabled || !budgetTracking) return;

        long freeBytes = queryFreeVRAM();
        if (freeBytes <= 0) return;

        // rough: GPU has ~8176MB on RX 6650 XT. used = total - free
        long totalMB = 8176;
        long freeMB = freeBytes / 1024 / 1024;
        long usedMB = totalMB - freeMB;
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

    /** Only downscale 16-bit color formats. Never touch depth/stencil. */
    public static boolean shouldDownscaleFormat(String formatName) {
        if (!enabled || !formatDownscale || formatName == null) return false;
        if (formatName.startsWith("D") || formatName.startsWith("S")) return false;
        return formatName.contains("16");
    }

    public static void logDownscale(String source, String original) {
        VerificationLogger.logFormatDownscale(source, original, "RGBA8");
        if (downscaleLogs < MAX_LOGS) {
            LOGGER.debug("Format downscale: {} ({} 鈫?RGBA8)", source, original);
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

    /** Only cap if enabled AND the texture exceeds maxShadowSize AND is square (shadow maps are always square). */
    public static boolean shouldCap(int width, int height) {
        return enabled && width == height && width > maxShadowSize;
    }

    /** Depth formats start with "D" (D16_UNORM, D24_UNORM_S8_UINT, D32_FLOAT, etc.). */
    public static boolean isDepthFormat(String formatName) {
        return formatName != null && formatName.startsWith("D");
    }

    // ---- depth downscale ----

    /** D32_FLOAT 鈫?D16_UNORM for shadow maps. Only pure depth, no stencil. */
    public static boolean shouldDownscaleDepth(String formatName) {
        return enabled && depthDownscale && "D32_FLOAT".equals(formatName);
    }

    public static void logDepthDownscale(String original) {
        VerificationLogger.logDepthDownscale(original, "D16_UNORM");
        if (downscaleLogs < MAX_LOGS) {
            LOGGER.debug("Depth downscale: {} 鈫?D16_UNORM", original);
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
