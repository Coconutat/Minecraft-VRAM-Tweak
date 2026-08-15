package test.vram.tweak.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;

/**
 * Applies vram-tweak's Voxy geometry-buffer limit by injecting the official
 * {@code voxy.geometryBufferSizeOverrideMB} system property BEFORE Voxy allocates
 * its geometry buffer (Voxy reads it when creating the render system on world enter).
 *
 * <p>Voxy pre-allocates ~4GB of immutable (non-sparse on AMD) geometry buffer that
 * stays fully resident in VRAM — the single biggest VRAM consumer on 8GB cards.
 * Capping it via the property (officially supported by Voxy) trades far-LOD capacity
 * for ~3GB of VRAM.</p>
 */
public final class VoxyTweak {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/voxy");

    /**
     * 512MB 及以下在重资源包 + Iris 场景下会把 Voxy 几何缓冲压满，
     * 导致节点层级频繁重建、驱动显存膨胀（实测 8GB 卡直接打满）。
     * 因此 1024MB 是 vram-tweak 允许的最低安全值。
     */
    public static final int MIN_GEOMETRY_LIMIT_MB = 1024;

    private VoxyTweak() {}

    /** Called at client init and on config save, before any world is entered. */
    public static void applyGeometryLimit() {
        if (!VoxyMemoryProbe.isAvailable()) {
            LOGGER.warn("Voxy geometry limit NOT applied: Voxy memory probe unavailable (class layout changed?)");
            return;
        }
        var cfg = VRAMConfig.getInstance().voxy;
        if (cfg.enabled && cfg.geometryBufferLimitMB > 0) {
            int limitMB = Math.max(cfg.geometryBufferLimitMB, MIN_GEOMETRY_LIMIT_MB);
            if (limitMB != cfg.geometryBufferLimitMB) {
                LOGGER.warn("Voxy geometry limit {}MB is unsafe (VRAM blow-up risk); clamped to {}MB.",
                        cfg.geometryBufferLimitMB, limitMB);
            }
            System.setProperty("voxy.geometryBufferSizeOverrideMB", String.valueOf(limitMB));
            LOGGER.info("Voxy geometry buffer limit -> {}MB (effective on next world enter)", limitMB);
        } else {
            System.clearProperty("voxy.geometryBufferSizeOverrideMB");
            LOGGER.info("Voxy geometry buffer limit disabled (property cleared)");
        }
    }
}
