package test.vram.tweak.diagnostic;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import test.vram.tweak.gpu.AmdVramLookup;
import test.vram.tweak.gpu.GPUDetector;

/**
 * Ring-buffer runtime metrics engine. Thread-safe, minimal GC.
 *
 * Collected: FPS, frame time, VRAM usage (independent of optimizer state),
 * texture alloc counts, FPS min/max per window for stutter detection.
 */
public class MetricsEngine {
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FB;

    // ---- Sample ring buffer ----
    public static final class Sample {
        private volatile long timestamp;
        private volatile float fps;
        private volatile float fpsMin;       // min FPS in this window
        private volatile float fpsMax;       // max FPS in this window
        private volatile float frameTimeMs;
        private volatile float frameTimeMaxMs; // worst frame in window (stutter detection)
        private volatile long vramUsedMB;
        private volatile long vramTotalMB;
        private volatile long textureAllocCount;
        private volatile long textureFreeCount;
        private volatile long heapUsedMB;     // JVM heap used
        private volatile long heapMaxMB;      // JVM heap max
        private volatile int threadCount;     // active threads

        public long getTimestamp() { return timestamp; }
        public float getFps() { return fps; }
        public float getFpsMin() { return fpsMin; }
        public float getFpsMax() { return fpsMax; }
        public float getFrameTimeMs() { return frameTimeMs; }
        public float getFrameTimeMaxMs() { return frameTimeMaxMs; }
        public long getVramUsedMB() { return vramUsedMB; }
        public long getVramTotalMB() { return vramTotalMB; }
        public long getTextureAllocCount() { return textureAllocCount; }
        public long getTextureFreeCount() { return textureFreeCount; }
        public long getHeapUsedMB() { return heapUsedMB; }
        public long getHeapMaxMB() { return heapMaxMB; }
        public int getThreadCount() { return threadCount; }
    }

    private static final Sample[] buffer;
    private static final int mask;
    private static final AtomicLong writeIdx = new AtomicLong(0);

    // ---- Monotonic counters (thread-safe) ----
    public static final AtomicLong textureAllocations = new AtomicLong(0);
    public static final AtomicLong textureFrees = new AtomicLong(0);
    public static final AtomicLong frameCount = new AtomicLong(0);

    // ---- Per-window state ----
    private static long lastSampleTime = System.currentTimeMillis();
    private static long lastFrameCount;
    private static float currentFps;
    private static float currentFrameTimeMs;
    private static long frameTimeAccum;
    private static int frameTimeCount;
    private static float windowFpsMin = Float.MAX_VALUE;
    private static float windowFpsMax;
    private static float windowFrameTimeMax;

    // ---- VRAM state (polled independently) ----
    private static volatile long lastVramUsedMB;
    private static volatile long lastVramTotalMB;
    private static long lastVramPollTime;
    private static Set<String> checkedExtensions = new HashSet<>();
    private static Set<String> availableExtensions = new HashSet<>();
    private static long calibrationTotalKB;

    // AMD ATI_meminfo dynamic calibration
    private static long amdMinFreeKB;          // low water mark: minimum free KB ever seen
    private static double amdSmoothedUsedMB;   // EMA-smoothed used VRAM
    private static long amdPrevFreeKB;
    private static boolean amdCalibrated;

    /** Check GL extension availability, cached. */
    private static boolean hasGLExt(String ext) {
        if (checkedExtensions.contains(ext)) return availableExtensions.contains(ext);
        checkedExtensions.add(ext);
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            if (ext.equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, i))) {
                availableExtensions.add(ext);
                return true;
            }
        }
        return false;
    }

    static {
        int size = nextPowerOfTwo(60);
        buffer = new Sample[size];
        mask = size - 1;
        for (int i = 0; i < size; i++) buffer[i] = new Sample();
    }

    private static int nextPowerOfTwo(int n) {
        n--;
        n |= n >> 1; n |= n >> 2; n |= n >> 4; n |= n >> 8; n |= n >> 16;
        return Math.max(n + 1, 16);
    }

    /** Called every frame (from GameRenderer mixin). */
    public static void onFrame(float deltaMs) {
        frameCount.incrementAndGet();
        float ftMs = deltaMs * 1000f;
        frameTimeAccum += (long)ftMs;
        frameTimeCount++;
        if (ftMs > windowFrameTimeMax) windowFrameTimeMax = ftMs;

        long now = System.currentTimeMillis();

        // Poll VRAM every 2 seconds (independent of optimizer state)
        if (now - lastVramPollTime >= 2000) {
            pollVRAM();
            lastVramPollTime = now;
        }

        long elapsed = now - lastSampleTime;
        int intervalSec = 5;

        if (elapsed >= intervalSec * 1000L) {
            long frames = frameCount.get() - lastFrameCount;
            currentFps = frames * 1000f / elapsed;
            currentFrameTimeMs = frameTimeCount > 0 ? frameTimeAccum / (float)frameTimeCount / 1000f : 0;

            // FPS per-window stats
            if (currentFps < windowFpsMin) windowFpsMin = currentFps;
            if (currentFps > windowFpsMax) windowFpsMax = currentFps;

            int idx = (int)(writeIdx.getAndIncrement() & mask);
            Sample s = buffer[idx];
            s.timestamp = now;
            s.fps = currentFps;
            s.fpsMin = windowFpsMin;
            s.fpsMax = windowFpsMax;
            s.frameTimeMs = currentFrameTimeMs;
            s.frameTimeMaxMs = windowFrameTimeMax;
            s.vramUsedMB = lastVramUsedMB;
            s.vramTotalMB = lastVramTotalMB;
            s.textureAllocCount = textureAllocations.get();
            s.textureFreeCount = textureFrees.get();
            pollCpu(s);

            // Reset window accumulators
            lastSampleTime = now;
            lastFrameCount = frameCount.get();
            frameTimeAccum = 0;
            frameTimeCount = 0;
            windowFpsMin = Float.MAX_VALUE;
            windowFpsMax = 0;
            windowFrameTimeMax = 0;
        }
    }

    /** Independent VRAM poll. Works regardless of optimizer state. */
    private static void pollVRAM() {
        try {
            long freeKB;
            long totalKB;
            switch (GPUDetector.getGPU()) {
                case AMD -> {
                    // === Dynamic calibration using low-water-mark + EMA smoothing ===
                    // ATI_meminfo overestimates free VRAM (driver includes reclaimable memory).
                    // Instead of a one-time offset, we track the minimum free ever seen
                    // (low water mark = point of peak usage) and smooth with EMA.

                    // 0. Get total from model lookup (most accurate)
                    long knownTotalKB = queryAmdKnownTotalKB();
                    if (knownTotalKB <= 0) {
                        // Fallback: try NVX total
                        if (hasGLExt("GL_NVX_gpu_memory_info")) {
                            int[] totalVal = new int[1];
                            GL11.glGetIntegerv(0x9047, totalVal);
                            knownTotalKB = (totalVal[0] & 0xFFFFFFFFL);
                        }
                    }
                    if (knownTotalKB <= 0 || knownTotalKB > 128L * 1024 * 1024) {
                        return; // can't determine total
                    }
                    calibrationTotalKB = knownTotalKB;
                    totalKB = knownTotalKB;

                    // Read ATI_meminfo free KB
                    int[] vals = new int[4];
                    GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, vals);
                    freeKB = vals[0] & 0xFFFFFFFFL;
                    if (freeKB <= 0 || freeKB > 128L * 1024 * 1024) return;

                    // Initialize on first poll
                    if (!amdCalibrated) {
                        amdMinFreeKB = freeKB;
                        amdSmoothedUsedMB = knownTotalKB / 1024.0 - freeKB / 1024.0;
                        amdPrevFreeKB = freeKB;
                        amdCalibrated = true;
                        // Use raw value for first sample
                        lastVramTotalMB = totalKB / 1024;
                        lastVramUsedMB = (long)amdSmoothedUsedMB;
                        return;
                    }

                    // Update low water mark (tracks peak VRAM usage)
                    if (freeKB < amdMinFreeKB) {
                        amdMinFreeKB = freeKB;
                    }

                    double totalMB = totalKB / 1024.0;
                    double rawUsedMB = totalMB - freeKB / 1024.0;
                    double peakUsedMB = totalMB - amdMinFreeKB / 1024.0;

                    // === Smoothing logic ===
                    // Fast rise: VRAM increases are trusted immediately (texture loading)
                    // Slow fall: VRAM decreases are smoothed (prevents spikes on world exit)
                    // Very slow after large jump in free (>20% of total = world exit)
                    double smoothed;
                    if (rawUsedMB > amdSmoothedUsedMB) {
                        // VRAM usage increased — trust it
                        smoothed = rawUsedMB;
                    } else {
                        long freeDelta = freeKB - amdPrevFreeKB;
                        if (freeDelta > knownTotalKB / 5) {
                            // Large free increase (world exit / resource cleanup)
                            // Converge slowly: 2% per step toward raw value
                            smoothed = amdSmoothedUsedMB * 0.98 + rawUsedMB * 0.02;
                        } else {
                            // Normal fluctuation: gentle EMA
                            smoothed = amdSmoothedUsedMB * 0.85 + rawUsedMB * 0.15;
                        }
                    }

                    // Sanity: never exceed total, never below 0
                    smoothed = Math.max(0, Math.min(smoothed, totalMB * 0.98));

                    amdSmoothedUsedMB = smoothed;
                    amdPrevFreeKB = freeKB;
                    freeKB = (long)((totalMB - smoothed) * 1024); // synthetic free for later calculation
                }
                case NVIDIA -> {
                    int[] freeVal = new int[1], totalVal = new int[1];
                    GL11.glGetIntegerv(0x9049, freeVal);
                    GL11.glGetIntegerv(0x9047, totalVal);
                    freeKB = freeVal[0] & 0xFFFFFFFFL;
                    totalKB = totalVal[0] & 0xFFFFFFFFL;
                    if (freeKB > 128L * 1024 * 1024 || totalKB > 128L * 1024 * 1024) { return; }
                }
                case INTEL -> {
                    // NVX first (modern Intel Arc DG2+), fallback ATI (some older iGPUs)
                    if (hasGLExt("GL_NVX_gpu_memory_info")) {
                        int[] freeVal = new int[1], totalVal = new int[1];
                        GL11.glGetIntegerv(0x9049, freeVal);
                        GL11.glGetIntegerv(0x9047, totalVal);
                        freeKB = freeVal[0] & 0xFFFFFFFFL;
                        totalKB = totalVal[0] & 0xFFFFFFFFL;
                        if (freeKB > 0 && totalKB > 0
                                && freeKB <= 128L * 1024 * 1024
                                && totalKB <= 128L * 1024 * 1024) {
                            break; // use NVX values
                        }
                    }
                    if (hasGLExt("GL_ATI_meminfo")) {
                        int[] vals = new int[4];
                        GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, vals);
                        freeKB = vals[0] & 0xFFFFFFFFL;
                        if (freeKB <= 0 || freeKB > 128L * 1024 * 1024) { return; }
                        totalKB = Math.max(freeKB, 2048L * 1024);
                    } else {
                        return; // no VRAM query extension available
                    }
                }
                default -> { return; }
            }
            lastVramTotalMB = totalKB / 1024;
            long raw = lastVramTotalMB - (freeKB / 1024);
            lastVramUsedMB = Math.max(0, raw);
        } catch (Exception ignored) {
        }
    }

    /** Model-based AMD total VRAM in KB. Returns 0 if model unknown or not AMD. */
    private static long queryAmdKnownTotalKB() {
        try {
            var info = GPUDetector.getGPUInfo();
            if (info == null || !info.getVendor().isAMD()) return 0;
            long knownMB = AmdVramLookup.lookup(info.getAmdArch(), info.getAmdModelName());
            if (knownMB > 0) return knownMB * 1024; // MB → KB
        } catch (Exception ignored) {}
        return 0;
    }

    // ---- Public queries ----

    public static float getFps() { return currentFps; }
    public static float getFrameTimeMs() { return currentFrameTimeMs; }
    public static long getVramUsedMB() { return lastVramUsedMB; }
    public static long getVramTotalMB() { return lastVramTotalMB; }
    public static long getTextureAllocs() { return textureAllocations.get(); }
    public static long getTextureFrees() { return textureFrees.get(); }
    public static long getFrameCount() { return frameCount.get(); }

    /** Called by VRAMOptimizer when budget tracking is active, for more precise per-frame VRAM. */
    public static void setVramUsed(long mb) { lastVramUsedMB = mb; }

    /** Poll JVM CPU/memory stats. Called once per sample window. */
    private static void pollCpu(Sample s) {
        Runtime rt = Runtime.getRuntime();
        s.heapUsedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        s.heapMaxMB = rt.maxMemory() / (1024 * 1024);
        s.threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
    }

    /** Snapshot of the ring buffer (most recent first). Thread-safe copy. */
    public static Sample[] snapshot() {
        long end = writeIdx.get();
        int size = buffer.length;
        Sample[] snap = new Sample[size];
        for (int i = 0; i < size; i++) {
            Sample src = buffer[(int)((end - 1 - i) & mask)];
            Sample dst = new Sample();
            dst.timestamp = src.timestamp;
            dst.fps = src.fps;
            dst.fpsMin = src.fpsMin;
            dst.fpsMax = src.fpsMax;
            dst.frameTimeMs = src.frameTimeMs;
            dst.frameTimeMaxMs = src.frameTimeMaxMs;
            dst.vramUsedMB = src.vramUsedMB;
            dst.vramTotalMB = src.vramTotalMB;
            dst.textureAllocCount = src.textureAllocCount;
            dst.textureFreeCount = src.textureFreeCount;
            dst.heapUsedMB = src.heapUsedMB;
            dst.heapMaxMB = src.heapMaxMB;
            dst.threadCount = src.threadCount;
            snap[i] = dst;
        }
        return snap;
    }
}
