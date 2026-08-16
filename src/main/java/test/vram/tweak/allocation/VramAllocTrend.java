package test.vram.tweak.allocation;

import java.util.ArrayList;
import java.util.List;

/**
 * AllocTracker 快照趋势（环形缓冲）。
 *
 * <p>每次 {@link VramAllocLogger#logSnapshot} 时记录一条
 * (GL 已用, 追踪已用, 未追踪差额)，供 {@code /vramtweak allocreport}
 * 展示“GL − 追踪”差额趋势。</p>
 */
public final class VramAllocTrend {

    private static final int MAX_ENTRIES = 20;

    private static final long[] times = new long[MAX_ENTRIES];
    private static final long[] glUsed = new long[MAX_ENTRIES];
    private static final long[] tracked = new long[MAX_ENTRIES];
    private static final long[] untracked = new long[MAX_ENTRIES];
    private static int writeIdx;
    private static int count;

    private VramAllocTrend() { /* utility class */ }

    public static synchronized void record(long glUsedMB, long trackedMB, long untrackedMB) {
        int idx = writeIdx % MAX_ENTRIES;
        times[idx] = System.currentTimeMillis();
        glUsed[idx] = glUsedMB;
        tracked[idx] = trackedMB;
        untracked[idx] = untrackedMB;
        writeIdx++;
        if (count < MAX_ENTRIES) count++;
    }

    /** 最近到最早排序的不可变快照。 */
    public static synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int idx = (writeIdx - i) % MAX_ENTRIES;
            out.add(new Entry(times[idx], glUsed[idx], tracked[idx], untracked[idx]));
        }
        return out;
    }

    public static synchronized int size() {
        return count;
    }

    public record Entry(long timeMs, long glUsedMB, long trackedMB, long untrackedMB) {}
}
