package test.vram.tweak.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.diagnostic.DiagnosticLogger;
import test.vram.tweak.diagnostic.VerificationLogger;
import test.vram.tweak.gpu.GPUDetector;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Init + shutdown hooks for vram-tweak.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftClient_Init {
    @Inject(at = @At("TAIL"), method = "<init>")
    private void onInit(CallbackInfo ci) {
        try {
            GPUDetector.initialize();
            DiagnosticLogger.run();
            VerificationLogger.logConfigSnapshot();

            var cfg = VRAMTweak.getConfig();
            if (!GPUDetector.shouldOptimize(cfg.vram.enabled)) {
                VRAMTweak.LOGGER.info("GPU not detected and force-enable off. Optimizations skipped.");
                return;
            }

            VRAMOptimizer.initialize();
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("Init failed", e);
        }
    }

    @Inject(at = @At("HEAD"), method = "close")
    private void onClose(CallbackInfo ci) {
        VerificationLogger.shutdown();
    }
}
