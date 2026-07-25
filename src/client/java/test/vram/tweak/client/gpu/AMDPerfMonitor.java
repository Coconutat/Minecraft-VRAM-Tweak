package test.vram.tweak.client.gpu;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.AMDPerformanceMonitor;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.AMDPerformanceMonitor.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.gpu.GPUDetector;

/**
 * Wraps GL_AMD_performance_monitor for per-frame GPU hardware counter queries.
 *
 * Enumerates available groups/counters at init, begins a monitor before each
 * render frame and ends after, then reads results asynchronously (one frame behind).
 *
 * Provides: GPU core clock (MHz), memory clock (MHz), GPU busy (%) — if the
 * driver exposes those counters.
 */
public class AMDPerfMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/perfmon");

    // ---- Known counter name substrings (driver-specific) ----
    private static final String[] CORE_CLOCK_NAMES  = {"gpu clock", "core clock", "gpu frequency", "core frequency"};
    private static final String[] MEM_CLOCK_NAMES   = {"memory clock", "vram clock", "mem clock", "memory frequency"};
    private static final String[] GPU_BUSY_NAMES    = {"gpu busy", "gpu utilization", "core utilization", "gpu activity"};

    // ---- State ----
    private static boolean available;
    private static boolean initialized;
    private static int monitorId = -1;

    // Enumerated counters
    private static final List<PerfCounter> activeCounters = new ArrayList<>();

    // Results (read one frame behind due to GPU pipelining)
    private static long lastCoreClockMHz;
    private static long lastMemClockMHz;
    private static float lastGpuBusyPct;
    private static boolean hasResults;

    private static class PerfCounter {
        final int groupId;
        final int counterId;
        final int type;       // GL_UNSIGNED_INT, GL_UNSIGNED_INT64_AMD, GL_PERCENTAGE_AMD, GL_FLOAT
        final String groupName;
        final String counterName;
        final boolean isPercentage;

        PerfCounter(int groupId, int counterId, int type, String groupName, String counterName) {
            this.groupId = groupId;
            this.counterId = counterId;
            this.type = type;
            this.groupName = groupName;
            this.counterName = counterName;
            this.isPercentage = type == GL_PERCENTAGE_AMD;
        }

        boolean matchesAny(String[] keywords) {
            String lower = counterName.toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw)) return true;
            }
            return false;
        }
    }

    /** Initialize: check extension, enumerate counters, create monitor. */
    public static void initialize() {
        if (initialized) return;
        initialized = true;

        if (GPUDetector.getGPU() == null || !GPUDetector.getGPU().isAMD()) {
            LOGGER.info("[PerfMon] Skipped — non-AMD GPU");
            return;
        }

        // Check GL extension
        if (!GL.getCapabilities().GL_AMD_performance_monitor) {
            LOGGER.info("[PerfMon] Not available — GL_AMD_performance_monitor not supported");
            return;
        }

        try {
            enumerateCounters();

            if (activeCounters.isEmpty()) {
                LOGGER.warn("[PerfMon] No usable counters found");
                return;
            }

            // Create monitor
            monitorId = glGenPerfMonitorsAMD();

            // Enable all matched counters
            for (PerfCounter pc : activeCounters) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer buf = stack.ints(pc.counterId);
                    glSelectPerfMonitorCountersAMD(monitorId, true, pc.groupId, buf);
                }
            }

            available = true;
            LOGGER.info("[PerfMon] Initialized. monitor={} activeCounters={}", monitorId, activeCounters.size());
            for (PerfCounter pc : activeCounters) {
                LOGGER.info("[PerfMon]   Group={} Counter={} type={}", pc.groupName, pc.counterName, pc.type);
            }
        } catch (Exception e) {
            LOGGER.warn("[PerfMon] Init failed", e);
            available = false;
        }
    }

    /** Enumerate all groups/counters, keep the ones we care about. */
    private static void enumerateCounters() {
        // Get number of groups
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer numGroups = stack.mallocInt(1);
            glGetPerfMonitorGroupsAMD(numGroups, null);
            int n = numGroups.get(0);
            if (n <= 0) return;

            IntBuffer groups = stack.mallocInt(n);
            glGetPerfMonitorGroupsAMD(null, groups);

            for (int gi = 0; gi < n; gi++) {
                int groupId = groups.get(gi);

                // Get group name
                String groupName = getGroupName(groupId);

                // Get counters in this group
                IntBuffer numCounters = stack.mallocInt(1);
                IntBuffer maxActive = stack.mallocInt(1);
                glGetPerfMonitorCountersAMD(groupId, numCounters, maxActive, null);

                int nc = numCounters.get(0);
                if (nc <= 0) continue;

                IntBuffer counters = stack.mallocInt(nc);
                glGetPerfMonitorCountersAMD(groupId, null, null, counters);

                for (int ci = 0; ci < nc; ci++) {
                    int counterId = counters.get(ci);
                    String counterName = getCounterName(groupId, counterId);
                    int counterType = getCounterType(groupId, counterId);

                    // Match against our known patterns
                    PerfCounter pc = new PerfCounter(groupId, counterId, counterType, groupName, counterName);
                    if (pc.matchesAny(CORE_CLOCK_NAMES)
                            || pc.matchesAny(MEM_CLOCK_NAMES)
                            || pc.matchesAny(GPU_BUSY_NAMES)) {
                        activeCounters.add(pc);
                    }
                }
            }
        }
    }

    private static String getGroupName(int groupId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Allocate a fixed buffer (group names rarely exceed 256 chars)
            int maxLen = 256;
            IntBuffer lenOut = stack.mallocInt(1);
            ByteBuffer buf = stack.malloc(maxLen);
            glGetPerfMonitorGroupStringAMD(groupId, lenOut, buf);
            int slen = lenOut.get(0);
            if (slen <= 0 || slen > maxLen) return "unknown";
            byte[] bytes = new byte[slen];
            buf.clear().limit(slen);
            buf.get(bytes);
            return new String(bytes).trim();
        }
    }

    private static String getCounterName(int groupId, int counterId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int maxLen = 256;
            IntBuffer lenOut = stack.mallocInt(1);
            ByteBuffer buf = stack.malloc(maxLen);
            glGetPerfMonitorCounterStringAMD(groupId, counterId, lenOut, buf);
            int slen = lenOut.get(0);
            if (slen <= 0 || slen > maxLen) return "unknown-" + counterId;
            byte[] bytes = new byte[slen];
            buf.clear().limit(slen);
            buf.get(bytes);
            return new String(bytes).trim();
        }
    }

    private static int getCounterType(int groupId, int counterId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer data = stack.mallocInt(2); // space for min/max range
            glGetPerfMonitorCounterInfoAMD(groupId, counterId, GL_COUNTER_TYPE_AMD, data);
            return data.get(0);
        }
    }

    // ---- Per-frame calls ----

    /** Call before rendering starts. */
    public static void beginFrame() {
        if (!available) return;
        try {
            glBeginPerfMonitorAMD(monitorId);
        } catch (Exception e) {
            LOGGER.warn("[PerfMon] beginFrame failed", e);
            available = false;
        }
    }

    /** Call after rendering ends. Reads previous frame results, then ends current. */
    public static void endFrame() {
        if (!available) return;
        try {

            // Read previous frame's results (if any) before ending current
            readResults();

            glEndPerfMonitorAMD(monitorId);
        } catch (Exception e) {
            LOGGER.warn("[PerfMon] endFrame failed", e);
            available = false;
        }
    }

    /** Read counter data from the most recently completed monitor session. */
    private static void readResults() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Check if result is available
            IntBuffer avail = stack.mallocInt(1);
            glGetPerfMonitorCounterDataAMD(monitorId, GL_PERFMON_RESULT_AVAILABLE_AMD, avail, null);
            if (avail.get(0) == 0) return; // not ready yet

            // Get result size
            IntBuffer size = stack.mallocInt(1);
            glGetPerfMonitorCounterDataAMD(monitorId, GL_PERFMON_RESULT_SIZE_AMD, size, null);
            int resultBytes = size.get(0);
            if (resultBytes <= 0) return;

            // Read data
            IntBuffer data = stack.mallocInt(resultBytes / 4 + 4);
            IntBuffer written = stack.mallocInt(1);
            glGetPerfMonitorCounterDataAMD(monitorId, GL_PERFMON_RESULT_AMD, data, written);

            int words = written.get(0) / 4;
            int pos = 0;
            while (pos + 2 < words) {
                int rGroupId = data.get(pos++);
                int rCounterId = data.get(pos++);
                int raw = data.get(pos++); // raw counter value (lower 32 bits)

                // Find the matching PerfCounter
                for (PerfCounter pc : activeCounters) {
                    if (pc.groupId == rGroupId && pc.counterId == rCounterId) {
                        long value = raw & 0xFFFFFFFFL; // unsigned extend
                        if (pc.type == GL_UNSIGNED_INT64_AMD && pos < words) {
                            long hi = data.get(pos++) & 0xFFFFFFFFL;
                            value |= (hi << 32);
                        }
                        storeValue(pc, value);
                        break;
                    }
                }
            }
            hasResults = true;
        }
    }

    private static void storeValue(PerfCounter pc, long value) {
        if (pc.matchesAny(CORE_CLOCK_NAMES)) {
            lastCoreClockMHz = value;
        } else if (pc.matchesAny(MEM_CLOCK_NAMES)) {
            lastMemClockMHz = value;
        } else if (pc.matchesAny(GPU_BUSY_NAMES)) {
            lastGpuBusyPct = pc.isPercentage ? (float) value : (float) value / 100f;
        }
    }

    // ---- Public queries ----

    /** GPU core clock in MHz, or 0 if unavailable. */
    public static long getCoreClockMHz() { return lastCoreClockMHz; }

    /** GPU memory clock in MHz, or 0 if unavailable. */
    public static long getMemClockMHz() { return lastMemClockMHz; }

    /** GPU busy percentage [0-100], or -1 if unavailable. */
    public static float getGpuBusyPct() { return hasResults ? lastGpuBusyPct : -1f; }

    /** True if the extension is available and initialized. */
    public static boolean isAvailable() { return available; }

    /** Cleanup. */
    public static void destroy() {
        if (monitorId >= 0 && available) {
            try {
                glDeletePerfMonitorsAMD(monitorId);
            } catch (Exception ignored) {}
            monitorId = -1;
            available = false;
        }
        initialized = false;
    }
}
