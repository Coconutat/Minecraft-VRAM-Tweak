package test.vram.tweak.diagnostic;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;

/**
 * Verification logger — proves the mod is actually doing something.
 *
 * Dual output:
 * 1. SLF4J → latest.log (grep "[VRAM-Tweak Verify]")
 * 2. Dedicated file → logs/vram-tweak/verify-<timestamp>.log
 *
 * Enabled via config: diagnostic.verificationLog = true
 */
public class VerificationLogger {
    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/verify");
    private static final String PFX = "[VRAM-Tweak Verify]";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int FULL_LOG = 20;
    private static final int SAMPLE_EVERY = 50;

    private static BufferedWriter fileWriter;
    private static Path filePath;

    // Counters
    private static final AtomicInteger formatDownscales = new AtomicInteger();
    private static final AtomicInteger depthDownscales = new AtomicInteger();
    private static final AtomicInteger governorActions = new AtomicInteger();
    private static final AtomicInteger budgetWarnings = new AtomicInteger();
    private static final AtomicInteger atlasTracked = new AtomicInteger();
    private static final AtomicInteger atlasCaps = new AtomicInteger();


    private static boolean enabled() {
        return VRAMConfig.getInstance().diagnostic.verificationLog;
    }

    // ---- File init ----

    private static synchronized void ensureFile() {
        if (fileWriter != null) return;
        try {
            var cfg = VRAMConfig.getInstance().diagnostic;
            Path dir = Path.of(cfg.logDirectory);
            Files.createDirectories(dir);
            filePath = dir.resolve("verify-" + VramModLog.getSessionId() + ".log");
            fileWriter = Files.newBufferedWriter(filePath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writeHeader();
        } catch (IOException e) {
            LOG.error("Cannot create verification log file", e);
            fileWriter = null;
        }
    }

    private static void writeHeader() throws IOException {
        var c = VRAMConfig.getInstance();
        writeln("=== vram-tweak Verification Log ===");
        writeln("Started: " + LocalDateTime.now().format(FMT));
        writeln("");
        writeln("[Config]");
        writeln("  VRAM: enabled=" + c.vram.enabled
                + " fmtDown=" + c.vram.formatDownscale
                + " depthDown=" + c.vram.depthDownscale
                + " budgetTracking=" + c.vram.budgetTracking
                + " budget=" + c.vram.budgetWarningPercent + "%");
        writeln("  Texture: atlasLimit=" + c.texture.atlasSizeLimit
                + "(" + c.texture.maxAtlasSize + ")");
        writeln("  Governor: enabled=" + c.governor.enabled
                + " hyst=" + c.governor.hysteresis + "%"
                + " minDist=" + c.governor.minDistance
                + " cooldown=" + c.governor.cooldownTicks + "t");
        writeln("");
        writeln("[Events]");
        fileWriter.flush();
    }

    private static synchronized void writeln(String line) {
        if (fileWriter == null) return;
        try {
            fileWriter.write(line);
            fileWriter.newLine();
        } catch (IOException ignored) {
            // ponytail: if file write fails, SLF4J still works
        }
    }

    private static void logBoth(String msg) {
        LOG.info("{} {}", PFX, msg);
        if (fileWriter == null) ensureFile();
        writeln("[" + LocalDateTime.now().format(TS) + "] " + msg);
    }

    // ---- Format downscale ----

    public static void logFormatDownscale(String source, String from, String to) {
        if (!enabled()) return;
        int n = formatDownscales.incrementAndGet();
        if (shouldLog(n)) {
            logBoth(String.format("Format downscale #%d: %s %s → %s", n, source, from, to));
        }
    }

    // ---- Depth downscale ----

    public static void logDepthDownscale(String from, String to) {
        if (!enabled()) return;
        int n = depthDownscales.incrementAndGet();
        if (shouldLog(n)) {
            logBoth(String.format("Depth downscale #%d: %s → %s", n, from, to));
        }
    }

    // ---- Governor ----

    public static void logGovernorAction(String direction, int oldDist, int newDist,
            long vramUsedMB, long vramTotalMB) {
        if (!enabled()) return;
        int n = governorActions.incrementAndGet();
        logBoth(String.format("Governor #%d: %s render distance %d→%d (VRAM %d/%dMB)",
                n, direction, oldDist, newDist, vramUsedMB, vramTotalMB));
    }

    // ---- Phase marker (P2 test tooling) ----

    public static void logPhase(String name) {
        if (!enabled()) return;
        logBoth("Phase: " + name);
    }

    // ---- VRAM budget ----

    public static void logBudgetWarning(long usedMB, long totalMB, int percent) {
        if (!enabled()) return;
        int n = budgetWarnings.incrementAndGet();
        if (n <= FULL_LOG || n % SAMPLE_EVERY == 0) {
            logBoth(String.format("Budget warn #%d: %dMB/%dMB (%d%%)",
                    n, usedMB, totalMB, percent));
        }
    }

    // ---- Atlas tracking ----

    public static void logAtlasTracked(String name, int width, int height, String format) {
        if (!enabled()) return;
        int n = atlasTracked.incrementAndGet();
        logBoth(String.format("Atlas #%d: %s %d×%d %s", n, name, width, height, format));
    }

    // ---- Atlas cap ----

    public static void logAtlasCap(String name, String dim, int orig, int capped) {
        if (!enabled()) return;
        int n = atlasCaps.incrementAndGet();
        if (shouldLog(n)) {
            logBoth(String.format("Atlas %s cap #%d: %s %d→%d", dim, n, name, orig, capped));
        }
    }

    // ---- Config snapshot (SLF4J only — file header already has it) ----

    public static void logConfigSnapshot() {
        if (!enabled()) return;
        var c = VRAMConfig.getInstance();
        LOG.info("{} === Config Snapshot ===", PFX);
        LOG.info("{} VRAM: enabled={} fmtDown={} depthDown={} budget={}%",
                PFX, c.vram.enabled,
                c.vram.formatDownscale, c.vram.depthDownscale, c.vram.budgetWarningPercent);
        LOG.info("{} Texture: atlasLimit={}({})",
                PFX, c.texture.atlasSizeLimit, c.texture.maxAtlasSize);
        LOG.info("{} Governor: enabled={} hyst={}% minDist={} cooldown={}t",
                PFX, c.governor.enabled, c.governor.hysteresis,
                c.governor.minDistance, c.governor.cooldownTicks);
        LOG.info("{} Full log → {}", PFX, filePath != null ? filePath.toAbsolutePath() : "pending...");
    }

    // ---- Summary ----

    public static String getSummary() {
        return String.format(
                "Format downscales:%d | Depth downscales:%d | "
                + "Governor actions:%d | Budget warns:%d | "
                + "Atlas tracked:%d | Atlas caps:%d",
                formatDownscales.get(), depthDownscales.get(),
                governorActions.get(),
                budgetWarnings.get(), atlasTracked.get(), atlasCaps.get());
    }

    public static synchronized void shutdown() {
        if (fileWriter == null) return;
        try {
            writeln("");
            writeln("=== Session Summary ===");
            writeln(getSummary());
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException ignored) {
        }
        fileWriter = null;
    }

    public static void reset() {
        formatDownscales.set(0);
        depthDownscales.set(0);
        governorActions.set(0);
        budgetWarnings.set(0);
        atlasTracked.set(0);
        atlasCaps.set(0);
    }

    // ---- Public getters (for HUD) ----

    public static int getAtlasTracked() { return atlasTracked.get(); }
    public static int getAtlasCaps() { return atlasCaps.get(); }
    public static int getDepthDownscales() { return depthDownscales.get(); }
    public static int getFormatDownscales() { return formatDownscales.get(); }
    public static int getBudgetWarnings() { return budgetWarnings.get(); }

    private static boolean shouldLog(int n) {
        return n <= FULL_LOG || n % SAMPLE_EVERY == 0;
    }
}
