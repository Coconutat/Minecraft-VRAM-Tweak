package test.vram.tweak.diagnostic;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import test.vram.tweak.VRAMTweak;

/**
 * Central file-based logger for all vram-tweak mod output.
 *
 * <p>Writes to {@code logs/vram-tweak/mod.log} with automatic rotation at ~5 MB.
 * All mod informational/diagnostic output goes here — NOT to latest.log.
 * Only critical errors (crash-level) still go to SLF4J/latest.log.</p>
 *
 * <p>Thread-safe via class-level lock. Non-blocking: flush on every write with
 * minimal overhead since writes are already batched by the caller.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * VramModLog.info("[AllocTracker] Snapshot ...");
 * VramModLog.warn("[AllocTracker] High water ...");
 * VramModLog.debug("[AllocTracker] ALLOC tex=123 ...");
 * }</pre>
 */
public final class VramModLog {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Path LOG_FILE;
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5 MB rotation
    private static BufferedWriter writer;
    private static boolean enabled = true;

    static {
        Path logDir = Path.of("logs", "vram-tweak");
        LOG_FILE = logDir.resolve("mod.log");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            enabled = false;
            VRAMTweak.LOGGER.error("VramModLog: cannot create log directory {}", logDir, e);
        }
    }

    private VramModLog() { /* utility */ }

    // ---- Public API ----

    public static void info(String msg)    { log("INFO ", msg); }
    public static void warn(String msg)    { log("WARN ", msg); }
    public static void debug(String msg)   { log("DEBUG", msg); }
    public static void error(String msg)   { log("ERROR", msg); }

    // ---- Internal ----

    private static synchronized void log(String level, String msg) {
        if (!enabled) return;

        try {
            rotateIfNeeded();
            if (writer == null) {
                writer = Files.newBufferedWriter(LOG_FILE,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String ts = LocalDateTime.now().format(TS_FMT);
            writer.write(String.format("[%s] [%s] %s%n", ts, level, msg));
            writer.flush();
        } catch (IOException e) {
            // Degrade: fall back to SLF4J exactly once
            enabled = false;
            VRAMTweak.LOGGER.error("VramModLog failed, disabling file logging", e);
        }
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.exists(LOG_FILE)) return;
        long size = Files.size(LOG_FILE);
        if (size < MAX_SIZE) return;

        if (writer != null) {
            writer.close();
            writer = null;
        }

        // Rotate: mod.log → mod.1.log, mod.1.log → mod.2.log (keep 3)
        Path rot3 = LOG_FILE.resolveSibling("mod.3.log");
        Path rot2 = LOG_FILE.resolveSibling("mod.2.log");
        Path rot1 = LOG_FILE.resolveSibling("mod.1.log");
        Files.deleteIfExists(rot3);
        if (Files.exists(rot2)) Files.move(rot2, rot3);
        if (Files.exists(rot1)) Files.move(rot1, rot2);
        Files.move(LOG_FILE, rot1);
    }
}
