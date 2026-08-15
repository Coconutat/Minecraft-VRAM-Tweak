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

    private VoxyTweak() {}

    /** Called from {@code VRAMTweakClient} at client init, before any world is entered. */
    public static void applyGeometryLimit() {
        if (!VoxyMemoryProbe.isAvailable()) return;
        var cfg = VRAMConfig.getInstance().voxy;
        if (cfg.enabled && cfg.geometryBufferLimitMB > 0) {
            System.setProperty("voxy.geometryBufferSizeOverrideMB", String.valueOf(cfg.geometryBufferLimitMB));
            LOGGER.info("Voxy geometry buffer limit -> {}MB (effective on next world enter)", cfg.geometryBufferLimitMB);
        }
    }
}
