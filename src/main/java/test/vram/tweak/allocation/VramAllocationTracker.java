package test.vram.tweak.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe singleton tracking every GPU texture allocation and deallocation.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #activate()} — enable tracking (immediate effect, no restart required)</li>
 *   <li>{@link #recordAlloc(VramAllocationRecord)} — called from B-layer mixin hooks</li>
 *   <li>{@link #recordFree(int)} — called from _deleteTexture hook</li>
 *   <li>{@link #computeSummary()} — aggregate snapshot for logging/HUD</li>
 *   <li>{@link #getTopAllocations(int)} — largest N alive allocations</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>{@code aliveById} is a ConcurrentHashMap for O(1) free lookup by GL object ID.
 * {@code history} is a ConcurrentLinkedDeque with configurable capacity, kept as a
 * sliding window of recent allocations for detailed logging.</p>
 *
 * <h3>Performance</h3>
 * <p>When {@link #isActive()} returns false, the mixin hooks do zero work — a single
 * boolean check. When active, recordAlloc/recordFree are O(1) map insertion/removal.</p>
 */
public final class VramAllocationTracker {

    // ---- Singleton ----
    private static final VramAllocationTracker INSTANCE = new VramAllocationTracker();

    private VramAllocationTracker() {}

    public static VramAllocationTracker getInstance() { return INSTANCE; }

    // ---- State ----
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong totalAllocs = new AtomicLong(0);
    private final AtomicLong totalFrees = new AtomicLong(0);
    private final AtomicInteger recentAllocCount = new AtomicInteger(0);  // reset per snapshot
    private final AtomicInteger recentFreeCount = new AtomicInteger(0);
    private final AtomicLong recentAllocBytes = new AtomicLong(0);
    private final AtomicLong recentFreeBytes = new AtomicLong(0);

    // Map: GL object ID → allocation record (O(1) free lookup)
    private final ConcurrentHashMap<Integer, VramAllocationRecord> aliveById = new ConcurrentHashMap<>();

    // Sliding window of recent allocations for detailed logging (ring-buffer style via deque)
    private static final int MAX_HISTORY = 4096;
    private final ConcurrentLinkedDeque<VramAllocationRecord> history = new ConcurrentLinkedDeque<>();

    // ---- Activation ----

    /** Enable allocation tracking. Thread-safe, immediate effect. */
    public void activate() {
        active.set(true);
        VramAllocLogger.logTrackerActivated();
    }

    /** Disable allocation tracking. Does NOT clear existing data. */
    public void deactivate() {
        active.set(false);
    }

    /** Whether tracking is currently active. */
    public boolean isActive() {
        return active.get();
    }

    // ---- Recording ----

    /**
     * Record a new GPU allocation.
     * <p>Called from B-layer mixin ({@code _texImage2D} hook).
     * Only records if tracking is active.</p>
     */
    public void recordAlloc(VramAllocationRecord record) {
        if (!active.get()) return;

        totalAllocs.incrementAndGet();
        recentAllocCount.incrementAndGet();
        recentAllocBytes.addAndGet(record.getEstimatedBytes());

        // Index by GL object ID for O(1) free lookup
        aliveById.put(record.getGlObjectId(), record);

        // Add to history sliding window
        history.addLast(record);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
    }

    /**
     * Get a record by GL object ID without removing from alive map.
     * Returns {@code null} if not found.
     */
    public VramAllocationRecord getRecord(int glObjectId) {
        return aliveById.get(glObjectId);
    }

    /**
     * Mark an allocation as freed.
     * <p>Called from B-layer mixin ({@code _deleteTexture} hook).
     * Only records if tracking is active.</p>
     */
    public void recordFree(int glObjectId) {
        if (!active.get()) return;

        VramAllocationRecord record = aliveById.remove(glObjectId);
        if (record != null) {
            record.markFreed(System.currentTimeMillis());
            totalFrees.incrementAndGet();
            recentFreeCount.incrementAndGet();
            recentFreeBytes.addAndGet(record.getEstimatedBytes());
        }
    }

    /**
     * Update a record's render-target classification.
     * <p>Called from D-layer mixin (framebuffer attachment hook).</p>
     */
    public void markAsRenderTarget(int glObjectId, AllocationCategory rtCategory) {
        if (!active.get()) return;

        VramAllocationRecord record = aliveById.get(glObjectId);
        if (record != null) {
            record.markAsRenderTarget(rtCategory);
        }
    }

    // ---- Query ----

    /** Total number of allocations ever tracked. */
    public long getTotalAllocs() { return totalAllocs.get(); }

    /** Total number of frees ever tracked. */
    public long getTotalFrees() { return totalFrees.get(); }

    /** Current number of alive (not yet freed) allocations. */
    public int getAliveCount() { return aliveById.size(); }

    /**
     * Build an aggregated snapshot of current allocation state.
     * <p>This is relatively expensive (iterates all alive records) — call at
     * the snapshot interval (e.g. every 30s), not every frame.</p>
     */
    public VramAllocSummary computeSummary() {
        // Snap the alive set
        List<VramAllocationRecord> aliveList = new ArrayList<>(aliveById.values());

        long totalBytes = 0;
        EnumMap<AllocationCategory, Long> byCategory = new EnumMap<>(AllocationCategory.class);
        EnumMap<SourceTag, Long> bySource = new EnumMap<>(SourceTag.class);

        for (VramAllocationRecord r : aliveList) {
            long bytes = r.getEstimatedBytes();
            totalBytes += bytes;
            byCategory.merge(r.getCategory(), bytes, Long::sum);
            bySource.merge(r.getSource(), bytes, Long::sum);
        }

        // Top N largest
        List<VramAllocationRecord> top = aliveList.stream()
                .sorted(Comparator.comparingLong(VramAllocationRecord::getEstimatedBytes).reversed())
                .limit(10)
                .collect(Collectors.toList());

        // Untracked bytes will be filled in by caller using GL-reported usage
        long untracked = 0;

        // Reset recent counters
        int recentAlloc = recentAllocCount.getAndSet(0);
        int recentFree = recentFreeCount.getAndSet(0);
        long recentAB = recentAllocBytes.getAndSet(0);
        long recentFB = recentFreeBytes.getAndSet(0);

        return new VramAllocSummary(
                (int) totalAllocs.get(),
                aliveList.size(),
                totalBytes,
                byCategory,
                bySource,
                top,
                untracked,
                System.currentTimeMillis()
        );
    }

    /**
     * Get the N largest alive allocations, descending by estimated bytes.
     */
    public List<VramAllocationRecord> getTopAllocations(int n) {
        return aliveById.values().stream()
                .sorted(Comparator.comparingLong(VramAllocationRecord::getEstimatedBytes).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    /** Clear all tracked data (for testing or /vramtweak reset). */
    public void reset() {
        aliveById.clear();
        history.clear();
        totalAllocs.set(0);
        totalFrees.set(0);
        recentAllocCount.set(0);
        recentFreeCount.set(0);
        recentAllocBytes.set(0);
        recentFreeBytes.set(0);
    }
}
