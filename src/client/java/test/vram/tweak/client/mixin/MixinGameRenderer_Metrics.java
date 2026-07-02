package test.vram.tweak.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.diagnostic.VramFrameCounter;
import test.vram.tweak.vram.VRAMGovernor;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Hook GameRenderer.render(DeltaTracker, boolean) for per-frame metrics + VRAM budget + governor.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Metrics {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void onFrameEnd(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        VRAMOptimizer.onFrameEnd();
        VRAMGovernor.onFrameEnd();
        MetricsEngine.onFrame(delta.getGameTimeDeltaTicks());
        VramFrameCounter.getInstance().onFrame();
    }
}
