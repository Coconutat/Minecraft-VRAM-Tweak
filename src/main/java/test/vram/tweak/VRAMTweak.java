package test.vram.tweak;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.vram.VRAMGovernor;

public class VRAMTweak implements ModInitializer {
    public static final String MOD_ID = "vram-tweak";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        VRAMConfig.load(FabricLoader.getInstance().getConfigDir());
        var cfg = VRAMConfig.getInstance();
        LOGGER.info("VRAM optimizer. enabled={}, shadowCap={}, downscale={}, budget={}",
                cfg.vram.enabled, cfg.vram.shadowMapMaxSize,
                cfg.vram.formatDownscale, cfg.vram.budgetTracking);
        VRAMGovernor.initialize();
    }

    public static VRAMConfig getConfig() {
        return VRAMConfig.getInstance();
    }
}
