package test.vram.tweak.vram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.util.ModCompat;

/**
 * Dynamically adjusts render distance based on VRAM pressure.
 *
 * When VRAM usage exceeds target threshold: reduce effective render distance
 * by 1 chunk per step. When VRAM recovers below (threshold - hysteresis):
 * restore to original render distance immediately.
 *
 * Cooldown between adjustments prevents flickering.
 * With Iris: slower adjustments (2x cooldown) to avoid shader reload issues.
 */
public class VRAMGovernor {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/governor");

    private static boolean enabled;
    private static int targetPercent;
    private static int hysteresis;
    private static int minDistance;
    private static int cooldownTicks;

    // State
    private static int currentCap = 32;           // effective cap (start at max render distance)
    private static int originalDistance = -1;             // user's setting
    private static int cooldown;
    private static boolean irisActive;
    private static boolean underPressure;                 // true when above threshold

    /** Called once at init. */
    public static void initialize() {
        reload();
    }

    /** Re-read config. Callable at runtime. */
    public static void reload() {
        var cfg = VRAMConfig.getInstance().governor;
        var vramCfg = VRAMConfig.getInstance().vram;
        enabled = cfg.enabled && vramCfg.enabled;
        targetPercent = vramCfg.budgetWarningPercent > 0 ? vramCfg.budgetWarningPercent : 80;
        hysteresis = cfg.hysteresis;
        minDistance = cfg.minDistance;
        cooldownTicks = cfg.cooldownTicks;
        irisActive = ModCompat.isIrisLoaded();
        currentCap = 32;
        originalDistance = -1;
        cooldown = 0;
        underPressure = false;

        if (enabled) {
            LOGGER.info("VRAM governor ON. target={}%, hysteresis={}%, minDist={}, cooldown={}t{}",
                    targetPercent, hysteresis, minDistance, cooldownTicks,
                    irisActive ? " (Iris: slower adjust)" : "");
        } else {
            LOGGER.info("VRAM governor OFF.");
        }
    }

    /** Called each frame from MixinGameRenderer_Metrics. */
    public static void onFrameEnd() {
        if (!enabled) return;

        if (cooldown > 0) { cooldown--; return; }

        long freeKB = VRAMOptimizer.queryFreeVRAM();
        if (freeKB <= 0) return;
        long totalMB = VRAMOptimizer.queryTotalVRAM();
        if (totalMB <= 0) return;

        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * targetPercent / 100;
        long recoverMB = totalMB * (targetPercent - hysteresis) / 100;

        boolean over = usedMB > thresholdMB;
        boolean recovered = usedMB < recoverMB;

        // Iris: use longer cooldown but still adjust
        int stepCooldown = irisActive ? cooldownTicks * 2 : cooldownTicks;

        if (over) {
            underPressure = true;
            if (currentCap > minDistance) {
                currentCap = Math.max(minDistance, currentCap - 1);
                cooldown = stepCooldown;
                VerificationLogger.logGovernorAction(
                        irisActive ? "iris_reduce" : "reduce",
                        currentCap + 1, currentCap, usedMB, totalMB);
                LOGGER.warn("VRAM pressure: {}MB/{}MB ({}%). Render dist -> {}",
                        usedMB, totalMB, usedMB * 100 / totalMB, currentCap);
            }
        } else if (recovered && underPressure) {
            // Restore in one shot when pressure is gone
            underPressure = false;
            int prevCap = currentCap;
            currentCap = 32;
            originalDistance = -1;
            cooldown = stepCooldown;
            VerificationLogger.logGovernorAction("restore", prevCap, -1, usedMB, totalMB);
            LOGGER.info("VRAM recovered: {}MB/{}MB. Render distance restored.", usedMB, totalMB);
        }
    }

    /**
     * Called by mixin. Captures original distance on first call, then applies cap.
     * @param original the user's actual render distance setting
     * @return capped value, or original if governor disabled / not active
     */
    public static int capRenderDistance(int original) {
        if (!enabled || currentCap >= 32) return original;
        if (originalDistance < 0) originalDistance = original;
        return Math.min(original, currentCap);
    }

    /** Current effective render distance cap (or 32 if no cap). */
    public static int getCurrentCap() { return currentCap; }

    /** Whether the governor is actively capping render distance. */
    public static boolean isCapping() { return enabled && currentCap < 32; }

    public static boolean isEnabled() { return enabled; }
}
