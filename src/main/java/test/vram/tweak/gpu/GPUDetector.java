package test.vram.tweak.gpu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects GPU vendor + AMD architecture via OpenGL strings. Call after GL context is ready.
 */
public class GPUDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/gpu");

    private static GPUType detectedGPU = GPUType.OTHER;
    private static GPUInfo gpuInfo;
    private static String renderer = "unknown";
    private static String vendor = "unknown";
    private static boolean initialized = false;

    // Pattern: "RX NNNN" or "RX NNNN XT" etc.
    private static final Pattern AMD_MODEL_PATTERN = Pattern.compile("RX\\s*(\\d{4})\\s*(XTX|XT|GRE)?",
            Pattern.CASE_INSENSITIVE);
    // Pattern for APUs: "Radeon NNNM" like "Radeon 780M"
    private static final Pattern AMD_APU_PATTERN = Pattern.compile("Radeon\\s*(\\d{3})[M]?",
            Pattern.CASE_INSENSITIVE);
    // Pattern for Instinct (CDNA)
    private static final Pattern AMD_INSTINCT_PATTERN = Pattern.compile("Instinct\\s*(MI\\w+)",
            Pattern.CASE_INSENSITIVE);

    public static void initialize() {
        if (initialized) return;
        try {
            renderer = GL11.glGetString(GL11.GL_RENDERER);
            vendor = GL11.glGetString(GL11.GL_VENDOR);
            detectedGPU = detect(vendor, renderer);
            gpuInfo = buildInfo(detectedGPU, vendor, renderer);
            LOGGER.info("GPU: {} | Vendor: {} | Renderer: {}", detectedGPU, vendor, renderer);
            if (detectedGPU == GPUType.AMD && gpuInfo != null) {
                LOGGER.info("AMD arch: {} ({}) | Model: {} | APU: {}",
                        gpuInfo.getAmdArch().getDisplayName(),
                        gpuInfo.getAmdArch().getCodename(),
                        gpuInfo.getAmdModelName(),
                        gpuInfo.isAPU());
            }
        } catch (Exception e) {
            LOGGER.warn("GPU detection failed, defaulting to OTHER", e);
            detectedGPU = GPUType.OTHER;
            gpuInfo = null;
        }
        initialized = true;
    }

    private static GPUType detect(String vendorStr, String rendererStr) {
        String s = ((vendorStr != null ? vendorStr : "") + " "
                + (rendererStr != null ? rendererStr : "")).toLowerCase();
        // NVIDIA first — "NVIDIA Corporation" contains "ati" substring
        if (s.contains("nvidia") || s.contains("geforce")) return GPUType.NVIDIA;
        if (s.contains("ati") || s.contains("amd") || s.contains("radeon")) return GPUType.AMD;
        if (s.contains("intel")) return GPUType.INTEL;
        return GPUType.OTHER;
    }

    /** Build detailed GPU info. Currently only AMD has extra detail. */
    private static GPUInfo buildInfo(GPUType type, String v, String r) {
        if (type != GPUType.AMD) {
            return new GPUInfo(type, r, v, null, "");
        }
        String full = (r != null ? r : "") + " " + (v != null ? v : "");
        AmdGpuArch arch = AmdGpuArch.UNKNOWN;
        String model = "";

        // Try Instinct (CDNA) first
        Matcher mi = AMD_INSTINCT_PATTERN.matcher(full);
        if (mi.find()) {
            return new GPUInfo(type, r, v, AmdGpuArch.CDNA, mi.group(0));
        }

        // Try discrete GPU model numbers
        Matcher m = AMD_MODEL_PATTERN.matcher(full);
        if (m.find()) {
            model = m.group(0);
            int num;
            try {
                num = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                num = 0;
            }
            arch = classifyAmdByModelNumber(num);
            return new GPUInfo(type, r, v, arch, model);
        }

        // Try APU detection
        Matcher apu = AMD_APU_PATTERN.matcher(full);
        if (apu.find()) {
            model = apu.group(0);
            int num;
            try {
                num = Integer.parseInt(apu.group(1));
            } catch (NumberFormatException e) {
                num = 0;
            }
            arch = classifyAmdApu(num);
            return new GPUInfo(type, r, v, arch, model);
        }

        // Fallback: check for specific keywords in renderer string
        String rl = (r != null ? r : "").toLowerCase();
        if (rl.contains("instinct")) {
            arch = AmdGpuArch.CDNA;
        } else if (rl.contains("w6000") || rl.contains("w6800") || rl.contains("w5700")
                || rl.contains("pro")) {
            arch = AmdGpuArch.RDNA2; // Radeon Pro W-series typically RDNA2
        } else if (rl.contains("firepro") || rl.contains("firegl")) {
            arch = AmdGpuArch.GCN;
        }

        return new GPUInfo(type, r, v, arch, model);
    }

    /**
     * Classify AMD architecture by GPU model number.
     * RX NNNN where NNNN is the model number.
     */
    private static AmdGpuArch classifyAmdByModelNumber(int modelNum) {
        if (modelNum >= 9000 && modelNum <= 9099) return AmdGpuArch.RDNA4;
        if (modelNum >= 8900 && modelNum <= 8999) return AmdGpuArch.RDNA4; // future
        if (modelNum >= 8000 && modelNum <= 8899) return AmdGpuArch.RDNA4; // RX 8000? future
        if (modelNum >= 7900 && modelNum <= 7999) return AmdGpuArch.RDNA3;
        if (modelNum >= 7600 && modelNum <= 7899) return AmdGpuArch.RDNA3;
        if (modelNum >= 7000 && modelNum <= 7599) return AmdGpuArch.RDNA3;
        if (modelNum >= 6400 && modelNum <= 6999) return AmdGpuArch.RDNA2;
        if (modelNum >= 6000 && modelNum <= 6399) return AmdGpuArch.RDNA2;
        if (modelNum >= 5500 && modelNum <= 5999) return AmdGpuArch.RDNA1;
        if (modelNum >= 5000 && modelNum <= 5499) return AmdGpuArch.RDNA1;
        if (modelNum >= 4000 && modelNum <= 4999) return AmdGpuArch.GCN; // R9 400 series
        if (modelNum >= 200 && modelNum <= 3999) return AmdGpuArch.GCN;  // HD 2000-7000, R5/R7/R9
        return AmdGpuArch.UNKNOWN;
    }

    /**
     * Classify AMD APU architecture by GPU model number.
     * Radeon NNNM where NNN is the model number (e.g. 780M, 890M).
     */
    private static AmdGpuArch classifyAmdApu(int apuNum) {
        if (apuNum >= 890 && apuNum <= 999) return AmdGpuArch.APU_RDNA35;
        if (apuNum >= 800 && apuNum <= 889) return AmdGpuArch.APU_RDNA3;  // Radeon 880M/870M
        if (apuNum >= 760 && apuNum <= 799) return AmdGpuArch.APU_RDNA3;  // Radeon 780M
        if (apuNum >= 700 && apuNum <= 759) return AmdGpuArch.APU_RDNA35; // Strix Point
        if (apuNum >= 680 && apuNum <= 699) return AmdGpuArch.APU_RDNA2;  // Radeon 680M
        if (apuNum >= 600 && apuNum <= 679) return AmdGpuArch.APU_RDNA2;  // Radeon 660M/610M
        return AmdGpuArch.UNKNOWN;
    }

    // ---- Public queries ----

    public static GPUType getGPU() { return detectedGPU; }
    public static String getRenderer() { return renderer; }
    public static String getVendor() { return vendor; }
    public static boolean isReady() { return initialized; }

    /** Detailed GPU info (architecture, model, APU status). May be null before init. */
    public static GPUInfo getGPUInfo() { return gpuInfo; }

    /** Helper: get AMD architecture display string. Returns "N/A" for non-AMD or unknown. */
    public static String getAmdArchDisplay() {
        if (gpuInfo == null || gpuInfo.getAmdArch() == null) return "N/A";
        return gpuInfo.getAmdArch().getDisplayName();
    }

    /** Helper: get AMD model name. Returns "N/A" for non-AMD. */
    public static String getAmdModel() {
        if (gpuInfo == null) return "N/A";
        String m = gpuInfo.getAmdModelName();
        return (m == null || m.isEmpty()) ? "N/A" : m;
    }

    public static boolean shouldOptimize(boolean force) {
        return force || detectedGPU != GPUType.OTHER;
    }
}
