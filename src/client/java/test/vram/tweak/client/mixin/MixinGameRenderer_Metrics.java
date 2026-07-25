package test.vram.tweak.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.allocation.VramAllocLogger;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.diagnostic.VramFrameCounter;
import test.vram.tweak.vram.VRAMGovernor;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Hook GameRenderer.render(DeltaTracker, boolean) for per-frame metrics + VRAM budget + governor.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Metrics {

    /** Cooldown counter for periodic alloc snapshot. */
    private static int allocSnapshotTicks = 0;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void onFrameEnd(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        VRAMOptimizer.onFrameEnd();
        VRAMGovernor.onFrameEnd();
        MetricsEngine.onFrame(delta.getGameTimeDeltaTicks());
        VramFrameCounter.getInstance().onFrame();

        // Periodic alloc snapshot (every N seconds, based on config)
        var tracker = VramAllocationTracker.getInstance();
        if (tracker.isActive()) {
            var cfg = VRAMConfig.getInstance().diagnostic;
            int intervalTicks = cfg.allocSnapshotInterval * 20; // seconds → ticks
            allocSnapshotTicks++;
            if (allocSnapshotTicks >= intervalTicks) {
                allocSnapshotTicks = 0;
                var summary = tracker.computeSummary();
                VramAllocLogger.logSnapshot(summary,
                        MetricsEngine.getVramUsedMB(), MetricsEngine.getVramTotalMB(),
                        allocSnapshotTicks / 20);
            }
        }
    }
}
