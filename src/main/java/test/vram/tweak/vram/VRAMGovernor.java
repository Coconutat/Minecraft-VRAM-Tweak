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
 * by 1 chunk per step, starting from the user's actual setting.
 * When VRAM recovers below (threshold - hysteresis): restore immediately.
 *
 * Operating range: 4-32 chunks.
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
    private static int currentCap = -1;           // -1 = no cap, ≥4 = active cap
    private static int userRenderDistance = -1;   // user's actual setting (captured by mixin)
    private static int cooldown;
    private static boolean irisActive;
    private static boolean underPressure;
    private static boolean capDirty;              // true when cap changed and needs applying

    // VRAM query failure diagnostic
    private static int vramQueryFailures;          // consecutive failures
    private static boolean vramQueryFailLogged;    // only log once per burst

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
        currentCap = -1;
        userRenderDistance = -1;
        cooldown = 0;
        underPressure = false;
        capDirty = false;
        vramQueryFailures = 0;
        vramQueryFailLogged = false;

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
        if (freeKB <= 0) {
            vramQueryFailures++;
            if (vramQueryFailures >= 60 && !vramQueryFailLogged) {
                LOGGER.warn("VRAM query failing for {} consecutive frames — governor cannot adjust. " +
                        "GPU={}, freeKB={}", vramQueryFailures,
                        test.vram.tweak.gpu.GPUDetector.getGPU(), freeKB);
                vramQueryFailLogged = true;
            }
            return;
        }
        vramQueryFailures = 0;
        vramQueryFailLogged = false;

        long totalMB = VRAMOptimizer.queryTotalVRAM();
        if (totalMB <= 0) return;

        long usedMB = totalMB - (freeKB / 1024);
        long thresholdMB = totalMB * targetPercent / 100;
        long recoverMB = totalMB * (targetPercent - hysteresis) / 100;

        boolean over = usedMB > thresholdMB;
        boolean recovered = usedMB < recoverMB;

        int stepCooldown = irisActive ? cooldownTicks * 2 : cooldownTicks;

        if (over) {
            underPressure = true;
            // First pressure: start capping from user's actual render distance
            if (currentCap < 0 && userRenderDistance > 0) {
                currentCap = userRenderDistance;
            }
            if (currentCap > minDistance) {
                currentCap = Math.max(minDistance, currentCap - 1);
                capDirty = true;
                cooldown = stepCooldown;
                VerificationLogger.logGovernorAction(
                        irisActive ? "iris_reduce" : "reduce",
                        currentCap + 1, currentCap, usedMB, totalMB);
                LOGGER.warn("VRAM pressure: {}MB/{}MB ({}%). Render dist cap -> {}",
                        usedMB, totalMB, usedMB * 100 / totalMB, currentCap);
            }
        } else if (recovered && underPressure) {
            // Restore in one shot when pressure is gone
            underPressure = false;
            int prevCap = currentCap;
            currentCap = -1;
            userRenderDistance = -1;
            capDirty = true;
            cooldown = stepCooldown;
            VerificationLogger.logGovernorAction("restore", prevCap, -1, usedMB, totalMB);
            LOGGER.info("VRAM recovered: {}MB/{}MB. Render distance restored.", usedMB, totalMB);
        }
    }

    /**
     * Called by mixin. Captures user's render distance, then applies cap.
     * @param original the user's actual render distance setting
     * @return capped value, or original if governor disabled / no active cap
     */
    public static int capRenderDistance(int original) {
        if (!enabled) return original;
        userRenderDistance = original; // always track user's setting
        if (currentCap < 0) return original; // no active cap
        return Math.min(original, currentCap);
    }

    /**
     * Called by Mixin each frame. Returns the view radius to apply via
     * {@code updateViewRadius()}, or -1 if no change pending.
     * The caller MUST call {@code updateViewRadius()} with the returned value
     * and then call this again (which will return -1 after consuming).
     */
    public static int consumeCapChange() {
        if (!enabled || !capDirty) return -1;
        capDirty = false;
        // currentCap < 0 means restore: use userRenderDistance (capped to original)
        // If userRenderDistance is also -1 (unknown), use a high safe default
        if (currentCap < 0) {
            return userRenderDistance > 0 ? userRenderDistance : 32;
        }
        return currentCap;
    }

    /** Current cap (-1 means no cap). */
    public static int getCurrentCap() { return currentCap; }

    /** Whether the governor is actively capping. */
    public static boolean isCapping() { return enabled && currentCap >= 0; }

    public static boolean isEnabled() { return enabled; }
}
