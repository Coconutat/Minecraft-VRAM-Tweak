package test.vram.tweak.allocation;

import java.util.List;
import java.util.Map;

/**
 * Immutable aggregated snapshot of current GPU allocation state.
 *
 * <p>Computed by {@link VramAllocationTracker#computeSummary()}.
 * Suitable for logging, HUD display, and command output.</p>
 *
 * @param totalAllocations     total records ever tracked (including freed)
 * @param aliveAllocations     currently alive (not yet deleted)
 * @param aliveEstimatedBytes  sum of estimated bytes for all alive allocations
 * @param byCategory           estimated bytes grouped by AllocationCategory (alive only)
 * @param bySource             estimated bytes grouped by SourceTag (alive only)
 * @param topAllocations       top N largest alive allocations (descending)
 * @param untrackedBytes       difference between GL-reported usage and tracked total
 * @param snapshotTimeMs       system time when this snapshot was computed
 */
public record VramAllocSummary(
        int totalAllocations,
        int aliveAllocations,
        long aliveEstimatedBytes,
        Map<AllocationCategory, Long> byCategory,
        Map<SourceTag, Long> bySource,
        List<VramAllocationRecord> topAllocations,
        long untrackedBytes,
        long snapshotTimeMs
) {

    /** Convenience: estimated bytes for a specific category (0 if absent). */
    public long getBytesFor(AllocationCategory cat) {
        return byCategory.getOrDefault(cat, 0L);
    }

    /** Convenience: estimated bytes for a specific source (0 if absent). */
    public long getBytesFor(SourceTag src) {
        return bySource.getOrDefault(src, 0L);
    }

    /** Human-readable MB string for a byte count. */
    public static String toMB(long bytes) {
        return String.format("%dMB", bytes / (1024 * 1024));
    }
}
