package test.vram.tweak.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
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
 *
 * <p>Also triggers {@code ClientChunkCache.updateViewRadius()} when governor changes
 * the render distance cap — this is the active enforcement path (Bug #1 fix).</p>
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Metrics {

    /** Cooldown counter for periodic alloc snapshot. */
    private static int allocSnapshotTicks = 0;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void onFrameEnd(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        VRAMOptimizer.onFrameEnd();
        VRAMGovernor.onFrameEnd();
        applyGovernorCap();   // Bug #1: actively push cap to ClientChunkCache
        MetricsEngine.onFrame(delta.getGameTimeDeltaTicks());
        VramFrameCounter.getInstance().onFrame();

        // Periodic alloc snapshot (every N seconds, based on config)
        var tracker = VramAllocationTracker.getInstance();
        if (tracker.isActive()) {
            var cfg = VRAMConfig.getInstance().diagnostic;
            int intervalTicks = cfg.allocSnapshotInterval * 20; // seconds → ticks
            allocSnapshotTicks++;
            if (allocSnapshotTicks >= intervalTicks) {
                long snapshotTicks = allocSnapshotTicks; // capture before reset
                allocSnapshotTicks = 0;
                var summary = tracker.computeSummary();
                VramAllocLogger.logSnapshot(summary,
                        MetricsEngine.getVramUsedMB(), MetricsEngine.getVramTotalMB(),
                        snapshotTicks / 20);

                // Level 3: 高水位告警 — 超过预算阈值时输出 TOP5 最大分配
                long usedMB = MetricsEngine.getVramUsedMB();
                long totalMB = MetricsEngine.getVramTotalMB();
                if (totalMB > 0 && usedMB * 100 / totalMB
                        >= VRAMConfig.getInstance().vram.budgetWarningPercent) {
                    VramAllocLogger.logHighWater(summary, usedMB, totalMB, 5);
                }
            }
        }
    }

    /**
     * Actively applies governor cap by calling {@code updateViewRadius}.
     * Previously the governor only set internal state and waited for the server
     * to send a render-distance packet — which rarely happens in normal gameplay.
     */
    private static void applyGovernorCap() {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            var source = level.getChunkSource();
            if (!(source instanceof ClientChunkCache cache)) return;

            int newRadius = VRAMGovernor.consumeCapChange();
            if (newRadius < 2) return;  // 2 is absolute minimum (calculateStorageRange floors at 2)
            cache.updateViewRadius(newRadius);
        } catch (Exception e) {
            // Safe — this runs every frame, don't spam
        }
    }
}
