package test.vram.tweak.vram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.util.ModCompat;

/**
 * Dynamically adjusts render distance based on VRAM pressure.
 *
 * When VRAM usage exceeds target threshold: reduce effective render distance.
 * When VRAM recovers below (threshold - hysteresis): restore.
 * Cooldown between adjustments prevents flickering.
 *
 * Designed to work with MixinOptions_RenderDistance (client sourceSet).
 */
public class VRAMGovernor {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/governor");

    private static boolean enabled;
    private static int targetPercent;
    private static int hysteresis;
    private static int minDistance;
    private static int cooldownTicks;

    // State
    private static int currentCap = Integer.MAX_VALUE;
    private static int originalDistance = -1; // captured once, restored on recovery
    private static int cooldown;

    // Iris compatibility: governor reduces to warning-only mode
    private static boolean irisActive;
    private static boolean irisWarningShown;

    /** Called once at init. */
    public static void initialize() {
        reload();
    }

    /** Re-read config. */
    public static void reload() {
        var cfg = VRAMConfig.getInstance().governor;
        var vramCfg = VRAMConfig.getInstance().vram;
        enabled = cfg.enabled && vramCfg.enabled;
        targetPercent = vramCfg.budgetWarningPercent > 0 ? vramCfg.budgetWarningPercent : 80;
        hysteresis = cfg.hysteresis;
        minDistance = cfg.minDistance;
        cooldownTicks = cfg.cooldownTicks;

        // Iris compatibility check
        irisActive = ModCompat.isIrisLoaded();
        irisWarningShown = false;

        // Reset state on reload
        currentCap = Integer.MAX_VALUE;
        originalDistance = -1;
        cooldown = 0;

        if (enabled) {
            if (irisActive) {
                LOGGER.warn("VRAM governor ON (Iris detected — auto-adjust disabled, warning only)."
                        + " target={}%, hysteresis={}", targetPercent, hysteresis);
            } else {
                LOGGER.info("VRAM governor ON. target={}%, hysteresis={}, minDist={}, cooldown={}t",
                        targetPercent, hysteresis, minDistance, cooldownTicks);
            }
        } else {
            LOGGER.info("VRAM governor OFF.");
        }
    }

    /**
     * When Iris is active: only check VRAM and log warnings, never adjust render distance.
     * Shows one warning per session when threshold is exceeded.
     */
    private static void checkIrisWarning() {
        if (irisWarningShown) return;
        long freeKB = VRAMOptimizer.queryFreeVRAM();
        if (freeKB <= 0) return;
        long totalMB = VRAMOptimizer.queryTotalVRAM();
        if (totalMB <= 0) return;
        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * targetPercent / 100;
        if (usedMB > thresholdMB) {
            irisWarningShown = true;
            VerificationLogger.logGovernorAction("iris_warn", 0, 0, usedMB, totalMB);
            LOGGER.warn("VRAM pressure with Iris: {}MB/{}MB ({}%). "
                    + "Iris shaders may cause stuttering. Consider lowering settings.",
                    usedMB, totalMB, usedMB * 100 / totalMB);
        }
    }

    /** Called each frame from MixinGameRenderer_Metrics. */
    public static void onFrameEnd() {
        if (!enabled) return;

        // Iris compatibility: don't auto-adjust render distance,
        // only show warnings when under pressure
        if (irisActive) {
            checkIrisWarning();
            return;
        }

        if (cooldown > 0) { cooldown--; return; }

        long freeKB = VRAMOptimizer.queryFreeVRAM();
        if (freeKB <= 0) return;

        long totalMB = VRAMOptimizer.queryTotalVRAM();
        if (totalMB <= 0) return;

        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * targetPercent / 100;

        if (usedMB > thresholdMB && currentCap > minDistance) {
            // reduce by 1 chunk
            currentCap = Math.max(minDistance, currentCap - 1);
            cooldown = cooldownTicks;
            VerificationLogger.logGovernorAction("reduce", currentCap + 1, currentCap, usedMB, totalMB);
            LOGGER.warn("VRAM pressure: {}MB/{}MB ({}%). Reducing render distance -> {}",
                    usedMB, totalMB, usedMB * 100 / totalMB, currentCap);
        } else if (usedMB < totalMB * (targetPercent - hysteresis) / 100 && currentCap < Integer.MAX_VALUE) {
            // recover by 1 chunk
            currentCap = Math.min(Integer.MAX_VALUE, currentCap + 1);
            cooldown = cooldownTicks;
            VerificationLogger.logGovernorAction("restore", currentCap - 1, currentCap, usedMB, totalMB);
            if (currentCap >= originalDistance || currentCap >= 32) {
                currentCap = Integer.MAX_VALUE;
                originalDistance = -1;
                LOGGER.info("VRAM recovered: {}MB. Render distance restored.", usedMB);
            } else {
                LOGGER.info("VRAM recovering: {}MB. Render distance -> {}", usedMB, currentCap);
            }
        }
    }

    /**
     * Called by mixin. Captures original distance on first call, then applies cap.
     * @param original the user's actual render distance setting
     * @return capped value, or original if governor disabled / not active
     */
    public static int capRenderDistance(int original) {
        if (!enabled || irisActive || currentCap == Integer.MAX_VALUE) return original;
        if (originalDistance < 0) originalDistance = original;
        return Math.min(original, currentCap);
    }

    public static boolean isEnabled() { return enabled; }
}
