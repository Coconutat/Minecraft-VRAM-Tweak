package test.vram.tweak.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.gpu.AMDPerfMonitor;

/**
 * Wraps GameRenderer.render() with AMD performance monitor Begin/End.
 *
 * Injects at HEAD to begin monitoring and at RETURN to end + read results.
 * Only active on AMD GPUs with GL_AMD_performance_monitor support.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_PerfMonitor {

    @Unique
    private static boolean perfMonInitialized;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void beginPerfMonitor(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        if (!perfMonInitialized) {
            AMDPerfMonitor.initialize();
            perfMonInitialized = true;
        }
        AMDPerfMonitor.beginFrame();
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void endPerfMonitor(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        AMDPerfMonitor.endFrame();
    }
}
