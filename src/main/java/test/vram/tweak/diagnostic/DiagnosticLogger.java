package test.vram.tweak.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.opengl.ATIMeminfo;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.gpu.AmdVramLookup;
import test.vram.tweak.gpu.GPUDetector;
import test.vram.tweak.gpu.GPUType;

/**
 * One-shot startup diagnostic. Writes a detailed, AI-analyzable report to
 * logs/vram-tweak/diagnostic-<timestamp>.txt.
 */
public class DiagnosticLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/diag");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static boolean done;
    private static Set<String> checkedExtensions = new HashSet<>();
    private static Set<String> availableExtensions = new HashSet<>();

    /** Check GL extension availability, cached. */
    private static boolean hasExtension(String ext) {
        if (checkedExtensions.contains(ext)) return availableExtensions.contains(ext);
        checkedExtensions.add(ext);
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            if (ext.equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, i))) {
                availableExtensions.add(ext);
                return true;
            }
        }
        return false;
    }

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

        // AMD architecture
        if (GPUDetector.getGPU() == GPUType.AMD) {
            sb.append("  AMD Arch: ").append(GPUDetector.getAmdArchDisplay()).append("\n");
            sb.append("  AMD Model: ").append(GPUDetector.getAmdModel()).append("\n");
        }

        // VRAM query (GPU-aware)
        try {
            switch (GPUDetector.getGPU()) {
                case AMD -> {
                    int[] vbo = new int[4];
                    int[] tex = new int[4];
                    int[] rbo = new int[4];
                    GL11.glGetIntegerv(ATIMeminfo.GL_VBO_FREE_MEMORY_ATI, vbo);
                    GL11.glGetIntegerv(ATIMeminfo.GL_TEXTURE_FREE_MEMORY_ATI, tex);
                    GL11.glGetIntegerv(ATIMeminfo.GL_RENDERBUFFER_FREE_MEMORY_ATI, rbo);
                    long vboFree = vbo[0] & 0xFFFFFFFFL;
                    long texFree = tex[0] & 0xFFFFFFFFL;
                    long rboFree = rbo[0] & 0xFFFFFFFFL;
                    sb.append("  VRAM free (ATI_meminfo, VBO pool): ").append(vboFree).append(" KB\n");
                    sb.append("    Texture pool free (info only): ").append(texFree).append(" KB\n");
                    sb.append("    Renderbuffer pool free (info only): ").append(rboFree).append(" KB\n");
                    // Try NVX total if available
                    if (hasExtension("GL_NVX_gpu_memory_info")) {
                        int[] totalVal = new int[1];
                        GL11.glGetIntegerv(0x9047, totalVal);
                        sb.append("  VRAM total (NVX): ").append((totalVal[0] & 0xFFFFFFFFL) / 1024).append(" MB\n");
                    }
                    // Model-based total
                    long knownVram = AmdVramLookup.lookup(
                            GPUDetector.getGPUInfo() != null ? GPUDetector.getGPUInfo().getAmdArch() : null,
                            GPUDetector.getAmdModel());
                    if (knownVram > 0) {
                        sb.append("  VRAM known (model lookup): ").append(knownVram).append(" MB\n");
                    }
                }
                case NVIDIA -> {
                    int[] freeVal = new int[1], totalVal = new int[1];
                    GL11.glGetIntegerv(0x9049, freeVal);
                    GL11.glGetIntegerv(0x9047, totalVal);
                    sb.append("  VRAM free (NVX): ").append(freeVal[0] & 0xFFFFFFFFL).append(" KB\n");
                    sb.append("  VRAM total (NVX): ").append(totalVal[0] & 0xFFFFFFFFL).append(" KB\n");
                }
                case INTEL -> {
                    boolean queried = false;
                    // NVX first (modern Intel Arc DG2+)
                    if (hasExtension("GL_NVX_gpu_memory_info")) {
                        int[] freeVal = new int[1], totalVal = new int[1];
                        GL11.glGetIntegerv(0x9049, freeVal);
                        GL11.glGetIntegerv(0x9047, totalVal);
                        long freeKB = freeVal[0] & 0xFFFFFFFFL;
                        long totalKB = totalVal[0] & 0xFFFFFFFFL;
                        if (freeKB > 0 && totalKB > 0) {
                            sb.append("  VRAM free (NVX): ").append(freeKB).append(" KB\n");
                            sb.append("  VRAM total (NVX): ").append(totalKB).append(" KB\n");
                            queried = true;
                        }
                    }
                    // Fallback ATI (some older iGPUs)
                    if (!queried && hasExtension("GL_ATI_meminfo")) {
                        int[] vals = new int[4];
                        GL11.glGetIntegerv(0x87FB, vals);
                        long freeKB = vals[0] & 0xFFFFFFFFL;
                        if (freeKB > 0) {
                            sb.append("  VRAM free (ATI_meminfo): ").append(freeKB).append(" KB\n");
                            queried = true;
                        }
                    }
                    if (!queried) {
                        sb.append("  VRAM query: no supported extension (GL_NVX_gpu_memory_info / GL_ATI_meminfo)\n");
                    }
                }
                default -> {
                    sb.append("  VRAM query: GPU type not supported\n");
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
        boolean hasSodium = false;
        boolean hasIris = false;
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            sb.append("  ").append(id)
              .append(" v").append(mod.getMetadata().getVersion().getFriendlyString()).append("\n");
            if ("sodium".equals(id)) hasSodium = true;
            if ("iris".equals(id)) hasIris = true;
        }
        sb.append("\n");

        // Renderer detection
        sb.append("[Renderer]\n");
        sb.append("  Sodium: ").append(hasSodium ? "detected" : "not present").append("\n");
        sb.append("  Iris: ").append(hasIris ? "detected" : "not present").append("\n");
        sb.append("  Texture upload: ");
        if (hasSodium) {
            sb.append("Sodium uses vanilla GlCommandEncoder.writeToTexture()\n");
        } else {
            sb.append("Vanilla GlStateManager path\n");
        }
        sb.append("  Hooks: _texImage2D(ByteBuffer) + _texSubImage2D(ByteBuffer) + GlCommandEncoder.writeToTexture (26.2 main path)\n");
        if (test.vram.tweak.gpu.VoxyMemoryProbe.isAvailable()) {
            sb.append("  Voxy: buffers=").append(test.vram.tweak.gpu.VoxyMemoryProbe.getBufferCount())
                    .append(" (").append(test.vram.tweak.gpu.VoxyMemoryProbe.getBufferBytes() / (1024 * 1024)).append("MB)")
                    .append(" textures=").append(test.vram.tweak.gpu.VoxyMemoryProbe.getTextureCount())
                    .append(" (").append(test.vram.tweak.gpu.VoxyMemoryProbe.getTextureBytes() / (1024 * 1024)).append("MB)\n");
        } else {
            sb.append("  Voxy: not present\n");
        }
        sb.append("\n");

        // Config
        sb.append("[Config]\n");
        var c = VRAMConfig.getInstance();
        var vram = c.vram;
        sb.append("  VRAM enabled: ").append(vram.enabled).append("\n");
        sb.append("  Format downscale: ").append(vram.formatDownscale).append("\n");
        sb.append("  Budget tracking: ").append(vram.budgetTracking).append("\n");
        sb.append("  Budget threshold: ").append(vram.budgetWarningPercent).append("%\n");
        sb.append("  Voxy geometry limit: ").append(c.voxy.enabled ? c.voxy.geometryBufferLimitMB + "MB" : "off").append("\n");

        // Write
        try {
            Path dir = Path.of(cfg.logDirectory);
            Files.createDirectories(dir);
            Path file = dir.resolve("diagnostic-" + VramModLog.getSessionId() + ".txt");
            Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOGGER.info("Diagnostic written: {}", file.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write diagnostic", e);
        }
    }
}
