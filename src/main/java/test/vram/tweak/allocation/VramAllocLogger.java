package test.vram.tweak.allocation;

import java.util.Map;

import test.vram.tweak.diagnostic.VramModLog;

/**
 * Three-level logging for the allocation tracker.
 *
 * <h3>Level 1: Per-allocation log (DEBUG)</h3>
 * One line per ALLOC/FREE event. Format:
 * <pre>[AllocTracker] ALLOC tex=123 cat=TEXTURE_ATLAS src=ATLAS_BLOCKS 4096×4096 mip=4 fmt=0x8058 est=64MB caller=GlDevice</pre>
 *
 * <h3>Level 2: Periodic snapshot (INFO)</h3>
 * Aggregated summary every N seconds. Shows category/source breakdown.
 *
 * <h3>Level 3: High-water alert (WARN)</h3>
 * Triggered when VRAM usage exceeds threshold. Shows TOP 5 largest alive allocations.
 */
public final class VramAllocLogger {

    private VramAllocLogger() { /* utility class */ }

    // ---- Level 1: Per-allocation ----

    public static void logAlloc(VramAllocationRecord rec) {
        VramModLog.debug(String.format(
                "[AllocTracker] ALLOC  tex=%d  cat=%s  src=%s  %d×%d  mip=%d  fmt=0x%s  est=%s  caller=%s",
                rec.getGlObjectId(),
                rec.getCategory(),
                rec.getSource(),
                rec.getWidth(), rec.getHeight(),
                rec.getMipLevels(),
                Integer.toHexString(rec.getGlInternalFormat()).toUpperCase(),
                formatBytes(rec.getEstimatedBytes()),
                rec.getCallerClass()));
    }

    public static void logFree(int glObjectId, VramAllocationRecord rec) {
        if (rec != null) {
            VramModLog.debug(String.format(
                    "[AllocTracker] FREE   tex=%d  cat=%s  src=%s  %d×%d  est=%s  lifetime=%ds",
                    glObjectId,
                    rec.getCategory(),
                    rec.getSource(),
                    rec.getWidth(), rec.getHeight(),
                    formatBytes(rec.getEstimatedBytes()),
                    rec.getLifetimeMs() / 1000));
        } else {
            VramModLog.debug(String.format("[AllocTracker] FREE   tex=%d  (unknown)", glObjectId));
        }
    }

    // ---- Helpers ----

    /** Format bytes as MB (≥1MB) or KB (<1MB). */
    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)) + "MB";
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }

    // ---- Level 2: Periodic snapshot ----

    /**
     * Output a structured snapshot report.
     *
     * @param summary the computed snapshot
     * @param glReportedUsedMB VRAM used as reported by GL query (ATI_meminfo or NVX)
     * @param glReportedTotalMB VRAM total as reported by GL query
     * @param gameTick current game tick (0 if unknown)
     */
    public static void logSnapshot(VramAllocSummary summary,
                                    long glReportedUsedMB, long glReportedTotalMB,
                                    long gameTick) {
        long trackedMB = summary.aliveEstimatedBytes() / (1024 * 1024);
        long untrackedMB = Math.max(0, glReportedUsedMB - trackedMB);

        StringBuilder sb = new StringBuilder(1024);
        sb.append("\n");
        sb.append("[AllocTracker] ===== Snapshot t=").append(gameTick).append("s =====\n");
        sb.append(String.format("  GL 已用: %d/%d MB (%d%%)\n",
                glReportedUsedMB, glReportedTotalMB,
                glReportedTotalMB > 0 ? (glReportedUsedMB * 100 / glReportedTotalMB) : 0));
        sb.append(String.format("  追踪已用: %d MB  |  未追踪: %d MB\n", trackedMB, untrackedMB));
        sb.append(String.format("  总分配: %d 笔  |  活跃: %d 笔  |  已释放: %d 笔\n",
                summary.totalAllocations(), summary.aliveAllocations(),
                summary.totalAllocations() - summary.aliveAllocations()));

        // By category
        sb.append("  ── 按类别 ──\n");
        appendSortedMap(sb, summary.byCategory(), summary.aliveEstimatedBytes());

        // By source
        sb.append("  ── 按来源 ──\n");
        appendSortedMap(sb, summary.bySource(), summary.aliveEstimatedBytes());

        VramModLog.info(sb.toString());
    }

    private static void appendSortedMap(StringBuilder sb,
                                         Map<? extends Enum<?>, Long> map,
                                         long totalBytes) {
        map.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    long mb = e.getValue() / (1024 * 1024);
                    double pct = totalBytes > 0 ? (e.getValue() * 100.0 / totalBytes) : 0;
                    sb.append(String.format("    %-30s %4d MB  (%5.1f%%)\n",
                            e.getKey(), mb, pct));
                });
    }

    // ---- Level 3: High-water alert ----

    /**
     * Output a high-water alert when VRAM usage exceeds warning threshold.
     */
    public static void logHighWater(VramAllocSummary summary,
                                     long glReportedUsedMB, long glReportedTotalMB,
                                     int topN) {
        long pct = glReportedTotalMB > 0 ? (glReportedUsedMB * 100 / glReportedTotalMB) : 0;

        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format("\n[AllocTracker] ⚠ VRAM 高水位 %d%% (%d/%d MB)\n",
                pct, glReportedUsedMB, glReportedTotalMB));

        var top = summary.topAllocations();
        int count = Math.min(topN, top.size());
        sb.append(String.format("  TOP %d 最大活跃分配:\n", count));
        for (int i = 0; i < count; i++) {
            var r = top.get(i);
            sb.append(String.format("  %d. %-20s %-20s %d×%d %s est=%s alive=%ds\n",
                    i + 1,
                    r.getCategory(),
                    r.getSource(),
                    r.getWidth(), r.getHeight(),
                    "0x" + Integer.toHexString(r.getGlInternalFormat()).toUpperCase(),
                    VramAllocSummary.toMB(r.getEstimatedBytes()),
                    r.getLifetimeMs() / 1000));
        }

        VramModLog.warn(sb.toString());
    }

    // ---- Activation message ----

    public static void logTrackerActivated() {
        VramModLog.info("[AllocTracker] 显存分配追踪器已激活！开启逐笔拦截...");
    }
}
