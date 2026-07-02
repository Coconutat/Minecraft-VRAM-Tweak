package test.vram.tweak.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;

/**
 * Caps total particle count to reduce GPU fill-rate pressure on AMD.
 * Cancels add() when over limit. MC 26.2 already has per-type ParticleLimit,
 * this adds a global hard cap as safety net.
 */
@Mixin(ParticleEngine.class)
public class MixinParticleEngine_Cap {

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"), cancellable = true)
    private void beforeAdd(Particle particle, CallbackInfo ci) {
        try {
            var cfg = VRAMConfig.getInstance().particle;
            if (!cfg.enabled) return;

            // Count existing particles via the engine's own string report
            ParticleEngine self = (ParticleEngine) (Object) this;
            String report = self.countParticles();
            int count = parseCount(report);

            if (count >= cfg.maxParticles) {
                VerificationLogger.logParticleReject(count, cfg.maxParticles);
                ci.cancel();
            }
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("Particle cap failed", e);
            // ponytail: never crash on particle add failure
        }
    }

    /** Parse "Particles: 1234" → 1234. Returns 0 on failure. */
    private static int parseCount(String report) {
        if (report == null) return 0;
        int colon = report.lastIndexOf(':');
        if (colon < 0) return 0;
        try {
            return Integer.parseInt(report.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
