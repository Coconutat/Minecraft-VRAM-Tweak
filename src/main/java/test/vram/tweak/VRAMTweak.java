package test.vram.tweak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;

// ponytail: no longer ModInitializer — mod is client-only, init lives in VRAMTweakClient
public class VRAMTweak {
    public static final String MOD_ID = "vram-tweak";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static VRAMConfig getConfig() {
        return VRAMConfig.getInstance();
    }
}
