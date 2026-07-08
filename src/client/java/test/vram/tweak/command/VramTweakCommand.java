package test.vram.tweak.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.gpu.GPUDetector;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

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
}
