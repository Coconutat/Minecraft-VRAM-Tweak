package test.vram.tweak.client.hud;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import test.vram.tweak.allocation.AllocationCategory;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.client.gpu.AMDPerfMonitor;
import test.vram.tweak.diagnostic.VramFrameCounter;
import test.vram.tweak.gpu.AmdVramLookup;
import test.vram.tweak.gpu.GPUDetector;
import test.vram.tweak.gpu.GPUInfo;
import test.vram.tweak.util.ModCompat;

/**
 * HUD overlay for vram-tweak. Singleton — shared by tick and render mixins.
 * Text list built in onClientTick(), rendered in render() via GuiGraphicsExtractor.
 */
public class VramTweakHud {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/hud");
    private static final VramTweakHud INSTANCE = new VramTweakHud();

    public static VramTweakHud getInstance() { return INSTANCE; }

    private final List<Component> textList = new ArrayList<>();
    private long peakVramMB;
    private boolean traceOnce;

    // Snapshot values for Mixin trace logging
    private int currentSmoothFps;
    private long currentVramMB;

    public void onClientTick() {
        textList.clear();
        var hud = VRAMConfig.getInstance().hud;
        var fc = VramFrameCounter.getInstance();

        // Snapshot for trace
        currentSmoothFps = fc.getSmoothFps();
        currentVramMB = MetricsEngine.getVramUsedMB();

        // Track VRAM peak
        if (currentVramMB > peakVramMB) peakVramMB = currentVramMB;

        // ---- FPS line ----
        if (hud.showFps || hud.showFpsAvg || hud.showFps1Percent || hud.showFps01Percent) {
            var label = Component.translatable("vramtweak.hud.fps").getString();
            StringBuilder sb = new StringBuilder(label);
            if (hud.showFps) sb.append(" §f").append(fc.getSmoothFps());
            if (hud.showFpsAvg) sb.append(" §7avg§f").append(fc.getAvgFps());
            if (hud.showFps1Percent) sb.append(" §71%§f").append(fc.getOnePercentLowFps());
            if (hud.showFps01Percent) sb.append(" §70.1%§f").append(fc.getPointOnePercentLowFps());
            textList.add(Component.literal(sb.toString()));
        }

        // ---- FrameTime line ----
        if (hud.showFrameTime) {
            var label = Component.translatable("vramtweak.hud.frametime").getString();
            textList.add(Component.literal(label + " §f" + String.format("%.1fms", fc.getAvgFrameTimeMs())));
        }

        // ---- GPU line ----
        if (hud.showGpu) {
            StringBuilder gpuLine = new StringBuilder("§bGPU:§f " + GPUDetector.getRenderer());
            String arch = GPUDetector.getAmdArchDisplay();
            if (!"N/A".equals(arch)) {
                gpuLine.append(" §7(").append(arch).append(")§f");
            }
            textList.add(Component.literal(gpuLine.toString()));
        }

        // ---- GPU Clocks (AMD performance monitor) ----
        if (hud.showGpuClocks && AMDPerfMonitor.isAvailable()) {
            long core = AMDPerfMonitor.getCoreClockMHz();
            long mem  = AMDPerfMonitor.getMemClockMHz();
            float busy = AMDPerfMonitor.getGpuBusyPct();
            StringBuilder sb = new StringBuilder("§bClk:§f ");
            if (core > 0) sb.append(core).append("MHz");
            if (mem > 0)  sb.append(" §bM:§f").append(mem).append("MHz");
            if (busy >= 0) sb.append(" §bBusy:§f").append(String.format("%.0f%%", busy));
            textList.add(Component.literal(sb.toString()));
        }

        // ---- VRAM line ----
        if (hud.showVram) {
            long total = MetricsEngine.getVramTotalMB();
            int pct = total > 0 ? (int)(currentVramMB * 100 / total) : 0;
            String color = pct >= 80 ? "§c" : pct >= 60 ? "§e" : "§a";
            var label = Component.translatable("vramtweak.hud.vram").getString();
            boolean reliableTotal = isVramTotalReliable();
            if (GPUDetector.getGPU().isAMD() && total > 0 && !reliableTotal) {
                // AMD, total approximated: show only used MB + percentage
                textList.add(Component.literal(label + " " + color + currentVramMB + "MB §f(" + color + pct + "%§f)"));
            } else {
                // NVIDIA / Intel / AMD with reliable total: show used/totalMB (percentage)
                textList.add(Component.literal(label + " " + color + currentVramMB + "§f/§b" + total + "MB §f(" + color + pct + "%§f)"));
            }
        }

        // ---- Atlas stats ----
        if (hud.showAtlas) {
            var label = Component.translatable("vramtweak.hud.atlas").getString();
            textList.add(Component.literal(label + " §f" + VerificationLogger.getAtlasTracked() + " §a" + VerificationLogger.getAtlasCaps()));
        }

        // ---- Texture allocations ----
        if (hud.showAllocations) {
            var label = Component.translatable("vramtweak.hud.textures").getString();
            textList.add(Component.literal(label + " §a+" + MetricsEngine.getTextureAllocs() + " §c-" + MetricsEngine.getTextureFrees()));
        }

        // ---- Downscale counts ----
        if (hud.showDownscales) {
            int depth = VerificationLogger.getDepthDownscales();
            int fmt = VerificationLogger.getFormatDownscales();
            String depthColor = depth > 0 ? "§a" : "§7";
            String fmtColor = fmt > 0 ? "§a" : "§7";
            var label = Component.translatable("vramtweak.hud.downscale").getString();
            textList.add(Component.literal(label + " " + depthColor + "D→D16×" + depth + "§f, " + fmtColor + "Fmt×" + fmt));
        }

        // ---- Governor status ----
        if (hud.showGovernor) {
            boolean capping = test.vram.tweak.vram.VRAMGovernor.isCapping();
            int cap = test.vram.tweak.vram.VRAMGovernor.getCurrentCap();
            var label = Component.translatable("vramtweak.hud.governor").getString();
            if (capping) {
                textList.add(Component.literal(label + " §eRD≤" + cap));
            } else {
                textList.add(Component.literal(label + " §aOK"));
            }
        }

        // ---- Alloc breakdown ----
        if (hud.showAllocBreakdown) {
            var tracker = VramAllocationTracker.getInstance();
            if (tracker.isActive()) {
                var summary = tracker.computeSummary();
                long texMB = (summary.getBytesFor(AllocationCategory.TEXTURE_ATLAS)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_BLOCK)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_ENTITY)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_ITEM)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_ENVIRONMENT)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_FONT)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_GUI)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_PAINTING)
                        + summary.getBytesFor(AllocationCategory.TEXTURE_MISC)) / (1024 * 1024);
                long rtMB = (summary.getBytesFor(AllocationCategory.RENDER_TARGET_COLOR)
                        + summary.getBytesFor(AllocationCategory.RENDER_TARGET_DEPTH)
                        + summary.getBytesFor(AllocationCategory.RENDER_TARGET_SHADOW)
                        + summary.getBytesFor(AllocationCategory.RENDER_TARGET_MULTISAMPLE)) / (1024 * 1024);
                var label = Component.translatable("vramtweak.hud.alloc").getString();
                StringBuilder sb = new StringBuilder(label);
                if (texMB > 0) {
                    var texLabel = Component.translatable("vramtweak.hud.alloc.tex").getString();
                    sb.append(" ").append(texLabel).append(":").append(texMB).append("MB");
                }
                if (rtMB > 0) {
                    var rtLabel = Component.translatable("vramtweak.hud.alloc.rt").getString();
                    sb.append(" ").append(rtLabel).append(":").append(rtMB).append("MB");
                }
                if (texMB == 0 && rtMB == 0) {
                    var unknownLabel = Component.translatable("vramtweak.hud.alloc.unknown").getString();
                    sb.append(" ").append(unknownLabel).append(":...MB");
                }
                textList.add(Component.literal(sb.toString()));
            }
        }

        // ---- Budget status ----
        if (hud.showBudget) {
            long total = MetricsEngine.getVramTotalMB();
            int peakPct = total > 0 ? (int)(peakVramMB * 100 / total) : 0;
            int warns = VerificationLogger.getBudgetWarnings();
            String irisTag = ModCompat.isIrisLoaded() ? " §d[Iris]" : "";
            String status = warns > 0 ? "§cWARN×" + warns : Component.translatable("vramtweak.hud.budget.ok").getString();
            var label = Component.translatable("vramtweak.hud.budget").getString();
            String peakColor = peakPct >= 80 ? "§c" : "§a";
            textList.add(Component.literal(label + " " + status + irisTag + " §f(peak " + peakColor + peakPct + "%§f)"));
        }

        // ---- Compression status removed 2026-08-09: RGB5A1 atlas compression did not
        // fire on 26.2 (writeToTexture never sees atlas label/whole-image) — feature deleted.

        // One-shot trace: log HUD content after first 5 ticks
        if (!traceOnce && VramFrameCounter.getInstance().getSampleCount() > 20) {
            traceOnce = true;
            LOG.info("[HUD Content Trace] lines={} fps={} avgFps={} 1%Low={} 0.1%Low={} vram={}MB",
                    textList.size(), fc.getSmoothFps(), fc.getAvgFps(),
                    fc.getOnePercentLowFps(), fc.getPointOnePercentLowFps(),
                    currentVramMB);
        }
    }

    /** Render using GuiGraphicsExtractor.text(). Called from MixinGui_Hud. */
    public void render(GuiGraphicsExtractor g) {
        var hud = VRAMConfig.getInstance().hud;
        if (!hud.enabled || textList.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        int x = hud.offsetX;
        int y = hud.offsetY;

        for (Component line : textList) {
            g.text(font, line, x, y, 0xFFFFFFFF);
            y += font.lineHeight + 2;
        }
    }

    // ---- Diagnostic accessors for Mixin trace logging ----

    public int getTextLineCount() { return textList.size(); }
    public int getCurrentSmoothFps() { return currentSmoothFps; }
    public long getCurrentVramMB() { return currentVramMB; }

    /**
     * Check if the VRAM total is from a reliable source (model lookup or NVX).
     * On AMD, calibration-based totals are approximated; model-based are exact.
     */
    private static boolean isVramTotalReliable() {
        if (!GPUDetector.getGPU().isAMD()) return true; // NVX is exact
        var info = GPUDetector.getGPUInfo();
        if (info == null) return false;
        long known = AmdVramLookup.lookup(info.getAmdArch(), info.getAmdModelName());
        return known > 0;
    }
}
