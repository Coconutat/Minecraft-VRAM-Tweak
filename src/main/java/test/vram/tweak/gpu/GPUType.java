package test.vram.tweak.gpu;

public enum GPUType {
    AMD,
    NVIDIA,
    INTEL,
    OTHER;

    public boolean isAMD() {
        return this == AMD;
    }
}
