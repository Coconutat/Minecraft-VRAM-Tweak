package test.vram.tweak.util;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Runtime mod detection and compatibility helpers.
 */
public class ModCompat {
    private static Boolean irisLoaded;
    private static Boolean sodiumLoaded;

    /** True if Iris shader mod is loaded. */
    public static boolean isIrisLoaded() {
        if (irisLoaded == null) {
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        }
        return irisLoaded;
    }

    /** True if Sodium mod is loaded. */
    public static boolean isSodiumLoaded() {
        if (sodiumLoaded == null) {
            sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        }
        return sodiumLoaded;
    }
}
