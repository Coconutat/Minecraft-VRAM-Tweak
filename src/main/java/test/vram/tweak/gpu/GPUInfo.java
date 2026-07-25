package test.vram.tweak.gpu;

/**
 * Detailed GPU information beyond vendor detection.
 * Populated by GPUDetector after initialization.
 */
public class GPUInfo {
    private final GPUType vendor;
    private final String renderer;
    private final String vendorStr;
    private final AmdGpuArch amdArch;
    private final String amdModelName;
    private final boolean isAPU;

    public GPUInfo(GPUType vendor, String renderer, String vendorStr,
                   AmdGpuArch amdArch, String amdModelName) {
        this.vendor = vendor;
        this.renderer = renderer;
        this.vendorStr = vendorStr;
        this.amdArch = amdArch;
        this.amdModelName = amdModelName;
        this.isAPU = amdArch != null && amdArch.isAPU();
    }

    public GPUType getVendor() { return vendor; }
    public String getRenderer() { return renderer; }
    public String getVendorStr() { return vendorStr; }

    /** AMD architecture generation. Non-null only on AMD GPUs. */
    public AmdGpuArch getAmdArch() { return amdArch; }

    /** AMD model name (e.g. "RX 9070 XT"). Empty string for non-AMD. */
    public String getAmdModelName() { return amdModelName; }

    /** True for integrated AMD GPUs (APUs). */
    public boolean isAPU() { return isAPU; }
}
