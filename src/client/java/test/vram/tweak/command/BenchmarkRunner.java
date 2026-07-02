package test.vram.tweak.command;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Dual-phase benchmark: OFF (warmup+collect) → ON (warmup+collect) → restore.
 * Prints comparison table on completion.
 */
public class BenchmarkRunner {
    private static volatile boolean running;
    private static Thread worker;

    public static void start(int secondsPerPhase, FabricClientCommandSource src) {
        if (running) {
            src.sendFeedback(Component.translatable("vramtweak.command.benchmark.running"));
            return;
        }
        running = true;

        worker = Thread.ofVirtual().start(() -> {
            try {
                int warmup = 5;
                int collect = Math.max(secondsPerPhase - warmup, 5);
                var cfg = VRAMConfig.getInstance().vram;

                // --- Phase 1: OFF ---
                boolean wasEnabled = cfg.enabled;
                cfg.enabled = false;
                VRAMOptimizer.reload();
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.phase1.warmup", warmup));
                Thread.sleep(warmup * 1000L);
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.phase1.collect", collect));
                var offStats = collectPhase(collect);

                // --- Phase 2: ON ---
                cfg.enabled = true;
                VRAMOptimizer.reload();
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.phase2.warmup", warmup));
                Thread.sleep(warmup * 1000L);
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.phase2.collect", collect));
                var onStats = collectPhase(collect);

                // --- Restore ---
                cfg.enabled = wasEnabled;
                VRAMOptimizer.reload();

                // --- Report ---
                src.sendFeedback(Component.translatable(
                        "vramtweak.command.benchmark.results",
                        offStats.avgFps, onStats.avgFps,
                        offStats.minFps, onStats.minFps,
                        offStats.maxFps, onStats.maxFps,
                        offStats.avgVram, onStats.avgVram));

            } catch (InterruptedException e) {
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.interrupted"));
            } catch (Exception e) {
                VRAMTweak.LOGGER.error("Benchmark failed", e);
                src.sendFeedback(Component.translatable("vramtweak.command.benchmark.failed", e.getMessage()));
            } finally {
                running = false;
            }
        });
    }

    private static PhaseStats collectPhase(int seconds) throws InterruptedException {
        float sumFps = 0, minFps = Float.MAX_VALUE, maxFps = 0;
        long sumVram = 0;
        int samples = 0;

        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500); // sample every 500ms
            float fps = MetricsEngine.getFps();
            if (fps > 0) { // ponytail: skip 0 (engine not yet sampled)
                sumFps += fps;
                if (fps < minFps) minFps = fps;
                if (fps > maxFps) maxFps = fps;
                sumVram += MetricsEngine.getVramUsedMB();
                samples++;
            }
        }

        var ps = new PhaseStats();
        ps.avgFps = samples > 0 ? sumFps / samples : 0;
        ps.minFps = samples > 0 ? minFps : 0;
        ps.maxFps = maxFps;
        ps.avgVram = samples > 0 ? sumVram / samples : 0;
        return ps;
    }

    private static class PhaseStats {
        float avgFps, minFps, maxFps;
        long avgVram;
    }
}
