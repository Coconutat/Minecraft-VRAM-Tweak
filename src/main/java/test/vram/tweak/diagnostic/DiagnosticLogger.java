package test.vram.tweak.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.gpu.GPUDetector;

/**
 * One-shot startup diagnostic. Writes a detailed, AI-analyzable report to
 * logs/vram-tweak/diagnostic-<timestamp>.txt.
 */
public class DiagnosticLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/diag");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static boolean done;

    public static void run() {
        if (done) return;
        done = true;
        var cfg = VRAMConfig.getInstance().diagnostic;
        if (!cfg.enabled || !cfg.startupReport) return;

        var sb = new StringBuilder();
        sb.append("=== vram-tweak Diagnostic ===\n");
        sb.append("Time: ").append(LocalDateTime.now().format(FMT)).append("\n\n");

        // System
        sb.append("[System]\n");
        sb.append("  OS: ").append(System.getProperty("os.name"))
          .append(" ").append(System.getProperty("os.version")).append("\n");
        sb.append("  Java: ").append(System.getProperty("java.version"))
          .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("  Max heap: ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n\n");

        // GPU
        sb.append("[GPU]\n");
        sb.append("  Detected: ").append(GPUDetector.getGPU()).append("\n");
        sb.append("  Vendor: ").append(GPUDetector.getVendor()).append("\n");
        sb.append("  Renderer: ").append(GPUDetector.getRenderer()).append("\n");
        sb.append("  GL Version: ").append(GL11.glGetString(GL11.GL_VERSION)).append("\n");
        sb.append("  GLSL: ").append(GL11.glGetString(GL30.GL_SHADING_LANGUAGE_VERSION)).append("\n");

        // VRAM query
        try {
            switch (GPUDetector.getGPU()) {
                case AMD -> {
                    int[] vals = new int[4];
                    GL11.glGetIntegerv(0x87FB, vals);
                    sb.append("  VRAM free (ATI_meminfo): ").append(vals[0] & 0xFFFFFFFFL).append(" KB\n");
                }
                case NVIDIA, INTEL -> {
                    int[] freeVal = new int[1], totalVal = new int[1];
                    GL11.glGetIntegerv(0x9049, freeVal);  // CURRENT_AVAILABLE_VIDMEM_NVX
                    GL11.glGetIntegerv(0x9047, totalVal); // DEDICATED_VIDMEM_NVX
                    sb.append("  VRAM free (NVX_meminfo): ").append(freeVal[0] & 0xFFFFFFFFL).append(" KB\n");
                    sb.append("  VRAM total (NVX_meminfo): ").append(totalVal[0] & 0xFFFFFFFFL).append(" KB\n");
                }
                default -> {
                    sb.append("  VRAM query: unavailable (GPU type not supported)\n");
                }
            }
        } catch (Exception e) {
            sb.append("  VRAM query: unavailable\n");
        }

        // GL extensions (use glGetStringi for core profile compatibility)
        sb.append("  GL Extensions: ");
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            sb.append(count).append(" loaded\n");
            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null && (ext.contains("ati") || ext.contains("amd")
                        || ext.contains("texture_compression") || ext.contains("direct_state")
                        || ext.contains("pinned_memory") || ext.contains("meminfo"))) {
                    sb.append("    ").append(ext).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("query failed\n");
        }
        sb.append("\n");

        // Mods
        sb.append("[Mods]\n");
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            sb.append("  ").append(mod.getMetadata().getId())
              .append(" v").append(mod.getMetadata().getVersion().getFriendlyString()).append("\n");
        }
        sb.append("\n");

        // Config
        sb.append("[Config]\n");
        var vram = VRAMConfig.getInstance().vram;
        sb.append("  VRAM enabled: ").append(vram.enabled).append("\n");
        sb.append("  Shadow cap: ").append(vram.shadowMapMaxSize).append("\n");
        sb.append("  Format downscale: ").append(vram.formatDownscale).append("\n");
        sb.append("  Budget tracking: ").append(vram.budgetTracking).append("\n");
        sb.append("  Budget threshold: ").append(vram.budgetWarningPercent).append("%\n");

        // Write
        try {
            Path dir = Path.of(cfg.logDirectory);
            Files.createDirectories(dir);
            Path file = dir.resolve("diagnostic-" + LocalDateTime.now().format(FMT) + ".txt");
            Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOGGER.info("Diagnostic written: {}", file.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write diagnostic", e);
        }
    }
}
