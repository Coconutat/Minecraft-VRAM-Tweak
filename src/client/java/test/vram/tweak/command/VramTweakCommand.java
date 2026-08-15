package test.vram.tweak.command;

import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.allocation.AllocationCategory;
import test.vram.tweak.allocation.VramAllocSummary;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.gpu.GPUDetector;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * /vramtweak <stats|dump|hud|benchmark> commands.
 */
public class VramTweakCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> disp) {
        disp.register(literal("vramtweak")
            .then(literal("stats").executes(ctx -> stats(ctx.getSource())))
            .then(literal("dump").executes(ctx -> dump(ctx.getSource())))
            .then(literal("hud").executes(ctx -> toggleHud(ctx.getSource())))
            .then(literal("benchmark")
                .then(argument("seconds", IntegerArgumentType.integer(5, 120))
                    .executes(ctx -> benchmark(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "seconds"))))
                .executes(ctx -> benchmark(ctx.getSource(), 30)))
            .then(literal("allocreport").executes(ctx -> allocReport(ctx.getSource())))
        );
    }

    private static int stats(FabricClientCommandSource src) {
        float fps = MetricsEngine.getFps();
        float ft = MetricsEngine.getFrameTimeMs();
        long vram = MetricsEngine.getVramUsedMB();
        long vramTotal = MetricsEngine.getVramTotalMB();
        long allocs = MetricsEngine.getTextureAllocs();
        long frees = MetricsEngine.getTextureFrees();
        long frames = MetricsEngine.getFrameCount();
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);

        String gpuLine = "§bGPU:§f " + GPUDetector.getRenderer()
                + " §7(" + GPUDetector.getVendor() + ")";
        src.sendFeedback(Component.literal(gpuLine));
        if (GPUDetector.getGPU().isAMD()) {
            src.sendFeedback(Component.translatable(
                    "vramtweak.command.stats.amd",
                    fps, ft, frames, vram,
                    vramTotal > 0 ? vram * 100f / vramTotal : 0,
                    allocs, frees, heapUsed, heapMax));
        } else {
            src.sendFeedback(Component.translatable(
                    "vramtweak.command.stats",
                    fps, ft, frames, vram, vramTotal,
                    vramTotal > 0 ? vram * 100f / vramTotal : 0,
                    allocs, frees, heapUsed, heapMax));
        }
        return 1;
    }

    private static int dump(FabricClientCommandSource src) {
        var samples = MetricsEngine.snapshot();
        var sb = new StringBuilder();
        sb.append("=== vram-tweak Ring Buffer Dump ===\n");
        sb.append("Time,FPS,MinFPS,MaxFPS,FrameTimeMs,FrameTimeMaxMs,VRAM_MB,VRAMTotal_MB,TexAlloc,TexFree,HeapMB,HeapMaxMB,Threads\n");
        for (var s : samples) {
            if (s.getTimestamp() == 0) continue;
            sb.append(String.format("%d,%.1f,%.1f,%.1f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d\n",
                    s.getTimestamp(), s.getFps(), s.getFpsMin(), s.getFpsMax(),
                    s.getFrameTimeMs(), s.getFrameTimeMaxMs(),
                    s.getVramUsedMB(), s.getVramTotalMB(),
                    s.getTextureAllocCount(), s.getTextureFreeCount(),
                    s.getHeapUsedMB(), s.getHeapMaxMB(), s.getThreadCount()));
        }

        var cfg = VRAMConfig.getInstance().diagnostic;
        try {
            var dir = java.nio.file.Path.of(cfg.logDirectory);
            java.nio.file.Files.createDirectories(dir);
            var file = dir.resolve("metrics-dump-" + System.currentTimeMillis() + ".csv");
            java.nio.file.Files.writeString(file, sb.toString());
            src.sendFeedback(Component.translatable("vramtweak.command.dump.success", file.toAbsolutePath()));
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("Dump failed", e);
            src.sendFeedback(Component.translatable("vramtweak.command.dump.failed", e.getMessage()));
        }
        return 1;
    }

    private static int toggleHud(FabricClientCommandSource src) {
        var hud = VRAMConfig.getInstance().hud;
        hud.enabled = !hud.enabled;
        VRAMConfig.save();
        src.sendFeedback(Component.translatable(
                hud.enabled ? "vramtweak.command.hud.toggle" : "vramtweak.command.hud.toggle.off"));
        return 1;
    }

    private static int benchmark(FabricClientCommandSource src, int seconds) {
        src.sendFeedback(Component.translatable("vramtweak.command.benchmark.start", seconds));
        BenchmarkRunner.start(seconds, src);
        return 1;
    }

    private static int allocReport(FabricClientCommandSource src) {
        var tracker = VramAllocationTracker.getInstance();
        if (!tracker.isActive()) {
            src.sendFeedback(Component.translatable("vramtweak.command.allocreport.disabled"));
            return 1;
        }

        var summary = tracker.computeSummary();
        long glUsed = MetricsEngine.getVramUsedMB();
        long glTotal = MetricsEngine.getVramTotalMB();
        long trackedMB = summary.aliveEstimatedBytes() / (1024 * 1024);

        src.sendFeedback(Component.literal(
                "§b=== VRAM Allocation Report ===§f  §7(" + glUsed + "/" + glTotal + "MB GL, tracked " + trackedMB + "MB)"));
        if (test.vram.tweak.gpu.VoxyMemoryProbe.isAvailable()) {
            src.sendFeedback(Component.literal("§7Voxy: buffers=" + test.vram.tweak.gpu.VoxyMemoryProbe.getBufferCount()
                    + " (" + test.vram.tweak.gpu.VoxyMemoryProbe.getBufferBytes() / (1024 * 1024) + "MB)"
                    + " textures=" + test.vram.tweak.gpu.VoxyMemoryProbe.getTextureCount()
                    + " (" + test.vram.tweak.gpu.VoxyMemoryProbe.getTextureBytes() / (1024 * 1024) + "MB)"));
        }
        src.sendFeedback(Component.literal(
                "§7Allocs: " + summary.totalAllocations() + " total, " + summary.aliveAllocations() + " alive"));

        var sortedCats = summary.byCategory().entrySet().stream()
                .sorted(Map.Entry.<AllocationCategory, Long>comparingByValue().reversed())
                .toList();
        src.sendFeedback(Component.literal("§6── By Category ──"));
        for (var e : sortedCats) {
            long mb = e.getValue() / (1024 * 1024);
            if (mb == 0) continue;
            String pct = trackedMB > 0 ? String.format("(%.0f%%)", e.getValue() * 100f / summary.aliveEstimatedBytes()) : "";
            src.sendFeedback(Component.literal(
                    "  §e" + e.getKey().getDisplayName() + "§f: " + mb + "MB " + pct));
        }

        var top = summary.topAllocations();
        if (!top.isEmpty()) {
            src.sendFeedback(Component.literal("§6── Top " + Math.min(top.size(), 10) + " Largest ──"));
            int shown = 0;
            for (var r : top) {
                if (shown++ >= 10) break;
                String size = VramAllocSummary.toMB(r.getEstimatedBytes());
                String dim = r.getWidth() > 0 ? r.getWidth() + "x" + r.getHeight() : "?";
                src.sendFeedback(Component.literal(
                        "  §e#" + r.getGlObjectId() + "§f: " + size + " §7" + dim
                        + " " + r.getCategory().getDisplayName()
                        + " §8[" + r.getSource().getDisplayName() + "]"));
            }
        }

        return 1;
    }
}
