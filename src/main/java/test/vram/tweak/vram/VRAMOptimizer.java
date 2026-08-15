package test.vram.tweak.vram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.gpu.GlVramProbe;

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

    private static boolean enabled;
    private static boolean formatDownscale;
    private static boolean depthDownscale;
    private static boolean budgetTracking;
    private static int budgetPercent;

    // throttle logs
    private static int downscaleLogs;
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
        budgetTracking = cfg.budgetTracking;
        budgetPercent = cfg.budgetWarningPercent;

        LOGGER.info("[TRACE] VRAMOptimizer.reload() called. Thread={}", Thread.currentThread().getName());
        if (enabled) {
            LOGGER.info("VRAM optimizer ON. formatDownscale={}, depthDownscale={}, budget={}%",
                    formatDownscale, depthDownscale, budgetPercent);
        } else {
            LOGGER.info("VRAM optimizer OFF. Existing textures unchanged until game restart.");
        }
    }

    public static boolean isEnabled() { return enabled; }

    /** Query VRAM free. Returns KB free, or -1 on failure. */
    public static long queryFreeVRAM() {
        return GlVramProbe.INSTANCE.freeKB();
    }

    /** GPU-aware total VRAM in MB. */
    public static long queryTotalVRAM() {
        long totalKB = GlVramProbe.INSTANCE.totalKB();
        return totalKB > 0 ? totalKB / 1024 : 0;
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
        if (!enabled || !budgetTracking) {
            return;
        }

        long freeKB = queryFreeVRAM();
        long totalMB = queryTotalVRAM();
        if (freeKB <= 0 || totalMB <= 0) return;

        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * budgetPercent / 100;

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

    /** Downscale RGBA16F → RGBA8 only. Other 16-bit/float formats must not be touched. */
    public static boolean shouldDownscaleFormat(String formatName) {
        if (!enabled || !formatDownscale || formatName == null) return false;
        return "RGBA16F".equals(formatName);
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

    // ---- depth downscale ----

    /** 26.2 GpuFormat: D32_FLOAT → D16_UNORM. */
    public static boolean shouldDownscaleDepth(String formatName) {
        return enabled && depthDownscale && "D32_FLOAT".equals(formatName);
    }

    public static void logDepthDownscale(String original) {
        VerificationLogger.logDepthDownscale(original, "D16_UNORM");
        if (downscaleLogs < MAX_LOGS) {
            LOGGER.debug("Depth downscale: {} → D16_UNORM", original);
            downscaleLogs++;
        } else if (downscaleLogs == MAX_LOGS) {
            LOGGER.debug("Downscale log limit reached.");
            downscaleLogs++;
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
