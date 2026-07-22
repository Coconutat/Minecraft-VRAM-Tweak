package test.vram.tweak.gpu;

/**
 * AMD GPU architecture family.
 * Used to identify which performance counters and features are available.
 */
public enum AmdGpuArch {
    RDNA1("RDNA 1", "Navi 10 / 14 / 21"),
    RDNA2("RDNA 2", "Navi 21 / 22 / 23 / 24"),
    RDNA3("RDNA 3", "Navi 31 / 32 / 33"),
    RDNA4("RDNA 4", "Navi 48"),
    CDNA("CDNA", "Instinct series"),
    GCN("GCN", "Graphics Core Next"),
    APU_RDNA2("APU RDNA 2", "Rembrandt / Barcelo"),
    APU_RDNA3("APU RDNA 3", "Phoenix / Hawk Point"),
    APU_RDNA35("APU RDNA 3.5", "Strix Point / Kraken Point"),
    UNKNOWN("Unknown", "Unrecognized AMD GPU");

    private final String displayName;
    private final String codename;

    AmdGpuArch(String displayName, String codename) {
        this.displayName = displayName;
        this.codename = codename;
    }

    public String getDisplayName() { return displayName; }
    public String getCodename() { return codename; }

    /** True if this is an APU (integrated GPU). */
    public boolean isAPU() {
        return this == APU_RDNA2 || this == APU_RDNA3 || this == APU_RDNA35;
    }
}
