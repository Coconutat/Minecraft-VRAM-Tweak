package test.vram.tweak.gpu;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects GPU vendor via OpenGL strings. Call after GL context is ready.
 */
public class GPUDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/gpu");

    private static GPUType detectedGPU = GPUType.OTHER;
    private static String renderer = "unknown";
    private static String vendor = "unknown";
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;
        try {
            renderer = GL11.glGetString(GL11.GL_RENDERER);
            vendor = GL11.glGetString(GL11.GL_VENDOR);
            detectedGPU = detect(vendor, renderer);
            LOGGER.info("GPU: {} | Vendor: {} | Renderer: {}", detectedGPU, vendor, renderer);
        } catch (Exception e) {
            LOGGER.warn("GPU detection failed, defaulting to OTHER", e);
            detectedGPU = GPUType.OTHER;
        }
        initialized = true;
    }

    private static GPUType detect(String vendorStr, String rendererStr) {
        String s = ((vendorStr != null ? vendorStr : "") + " "
                + (rendererStr != null ? rendererStr : "")).toLowerCase();
        if (s.contains("ati") || s.contains("amd") || s.contains("radeon")) return GPUType.AMD;
        if (s.contains("nvidia") || s.contains("geforce")) return GPUType.NVIDIA;
        if (s.contains("intel")) return GPUType.INTEL;
        return GPUType.OTHER;
    }

    public static GPUType getGPU() { return detectedGPU; }
    public static String getRenderer() { return renderer; }
    public static String getVendor() { return vendor; }
    public static boolean isReady() { return initialized; }

    public static boolean shouldOptimize(boolean force) {
        return force || detectedGPU != GPUType.OTHER;
    }
}
