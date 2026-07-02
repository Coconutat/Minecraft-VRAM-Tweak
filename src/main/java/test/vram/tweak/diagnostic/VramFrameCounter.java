package test.vram.tweak.diagnostic;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sliding-window FPS statistics with percentile lows.
 * Pattern borrowed from Sodium-Extra FrameCounter.
 * 5s window, 0.5s update interval. Thread-safe.
 */
public class VramFrameCounter {
    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/framecounter");
    private static final VramFrameCounter INSTANCE = new VramFrameCounter();

    private final Deque<FrameSample> samples = new ArrayDeque<>();
    private final long windowNanos = 5_000_000_000L;      // 5 seconds
    private final long smoothWindowNanos = 500_000_000L;   // 0.5 seconds for smooth FPS
    private final long updateIntervalNanos = 500_000_000L; // recompute every 0.5s

    private long lastFrameTime = -1;
    private long lastUpdateTime;
    private int sampleCount; // for trace logging

    // Cached stats, recomputed every updateIntervalNanos
    private int cachedSmoothFps;
    private int cachedAvgFps;
    private int cachedOnePercentLowFps;
    private int cachedPointOnePercentLowFps;
    private float cachedAvgFrameTimeMs;

    public static VramFrameCounter getInstance() {
        return INSTANCE;
    }

    /** Called every frame from GameRenderer mixin. */
    public synchronized void onFrame() {
        long now = System.nanoTime();

        if (lastFrameTime != -1) {
            long deltaNs = now - lastFrameTime;
            samples.addLast(new FrameSample(now, deltaNs));
        }
        lastFrameTime = now;

        // Trim old samples (beyond 5s window)
        while (!samples.isEmpty() && now - samples.peekFirst().timestamp > windowNanos) {
            samples.removeFirst();
        }

        // Throttle recomputation
        if (now - lastUpdateTime >= updateIntervalNanos) {
            lastUpdateTime = now;
            recomputeStats(now);

            // Trace: log first sample, then every 20th recompute (~10s)
            sampleCount++;
            if (sampleCount == 1) {
                LOG.info("[FrameCounter Start] first sample recorded, window={}ns updateInterval={}ns",
                        windowNanos, updateIntervalNanos);
            }
            if (sampleCount % 20 == 0) {
                LOG.info("[FrameCounter Trace] samples={} smoothFps={} avgFps={} 1%Low={} 0.1%Low={} avgFtMs={}",
                        samples.size(), cachedSmoothFps, cachedAvgFps,
                        cachedOnePercentLowFps, cachedPointOnePercentLowFps,
                        String.format("%.1f", cachedAvgFrameTimeMs));
            }
        }
    }

    private void recomputeStats(long now) {
        if (samples.isEmpty()) {
            cachedSmoothFps = cachedAvgFps = cachedOnePercentLowFps = cachedPointOnePercentLowFps = 0;
            cachedAvgFrameTimeMs = 0;
            return;
        }

        // Average FPS and frame time
        long totalNs = 0;
        for (FrameSample s : samples) totalNs += s.deltaNs;
        cachedAvgFps = (int) Math.round((double) (samples.size() * 1_000_000_000L) / totalNs);
        cachedAvgFrameTimeMs = totalNs / (float) samples.size() / 1_000_000f;

        // Smooth FPS (0.5s window)
        cachedSmoothFps = computeSmoothFps(now);

        // Percentile lows
        cachedOnePercentLowFps = computePercentileLow(1.0);
        cachedPointOnePercentLowFps = computePercentileLow(0.1);
    }

    private int computeSmoothFps(long now) {
        List<Long> recent = samples.stream()
                .filter(s -> now - s.timestamp <= smoothWindowNanos)
                .map(s -> s.deltaNs)
                .toList();
        if (recent.isEmpty()) return 0;
        double avgNs = recent.stream().mapToLong(Long::longValue).average().orElse(0);
        return (int) Math.round(1_000_000_000.0 / avgNs);
    }

    /**
     * Computes the FPS of the slowest `percent`% of frames.
     * Sorted descending (worst first). Takes top percent% frames, averages delta, converts to FPS.
     */
    private int computePercentileLow(double percent) {
        if (samples.isEmpty()) return 0;
        List<Long> deltas = samples.stream()
                .map(s -> s.deltaNs)
                .sorted(Comparator.reverseOrder())
                .toList();
        int count = Math.max(1, (int) Math.ceil(deltas.size() * (percent / 100.0)));
        long sum = 0;
        for (int i = 0; i < count; i++) sum += deltas.get(i);
        double avgNs = sum / (double) count;
        return (int) Math.round(1_000_000_000.0 / avgNs);
    }

    // ---- Public getters ----

    public int getSmoothFps() { return cachedSmoothFps; }
    public int getAvgFps() { return cachedAvgFps; }
    public int getOnePercentLowFps() { return cachedOnePercentLowFps; }
    public int getPointOnePercentLowFps() { return cachedPointOnePercentLowFps; }
    public float getAvgFrameTimeMs() { return cachedAvgFrameTimeMs; }

    /** For diagnostic: number of samples in current window. */
    public synchronized int getSampleCount() { return samples.size(); }

    // ---- Internal ----

    private record FrameSample(long timestamp, long deltaNs) {}
}
