package test.vram.tweak.diagnostic;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.opengl.GL11;

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
                    int[] vals = new int[4];
                    GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, vals);
                    freeKB = vals[0] & 0xFFFFFFFFL;
                    // Sanity check: free VRAM must be 0–128 GB. AMD drivers may return garbage.
                    if (freeKB > 128L * 1024 * 1024) { return; }
                    totalKB = Math.max(freeKB, 8192L * 1024); // can't query total on AMD, assume 8GB+
                }
                case NVIDIA -> {
                    int[] freeVal = new int[1], totalVal = new int[1];
                    GL11.glGetIntegerv(0x9049, freeVal);  // CURRENT_AVAILABLE_VIDMEM_NVX
                    GL11.glGetIntegerv(0x9047, totalVal); // DEDICATED_VIDMEM_NVX
                    freeKB = freeVal[0] & 0xFFFFFFFFL;
                    totalKB = totalVal[0] & 0xFFFFFFFFL;
                    if (freeKB > 128L * 1024 * 1024 || totalKB > 128L * 1024 * 1024) { return; }
                }
                default -> { return; } // Intel/OTHER — no VRAM tracking
            }
            long freeMB = freeKB / 1024;
            lastVramTotalMB = totalKB / 1024;
            lastVramUsedMB = lastVramTotalMB - freeMB;
        } catch (Exception ignored) {
            // GL context might not be current — skip this poll
        }
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
