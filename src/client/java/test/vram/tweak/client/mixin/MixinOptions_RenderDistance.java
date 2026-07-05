package test.vram.tweak.client.mixin;

import net.minecraft.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import test.vram.tweak.vram.VRAMGovernor;

/**
 * Caps effective render distance when VRAM pressure detected.
 * Uses Inject RETURN with CallbackInfoReturnable to modify the int return value.
 */
@Mixin(Options.class)
public class MixinOptions_RenderDistance {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/governor");
    private static boolean traceInit;

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void capEffectiveDistance(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        try {
            int capped = VRAMGovernor.capRenderDistance(original);
            if (capped != original) {
                LOG.warn("[Governor Mixin] getEffectiveRenderDistance: {} → {} (cap active)", original, capped);
            } else if (!traceInit) {
                traceInit = true;
                LOG.info("[Governor Mixin] getEffectiveRenderDistance={} (no cap, governor enabled={})",
                        original, VRAMGovernor.isEnabled());
            }
            cir.setReturnValue(capped);
        } catch (Exception e) {
            LOG.error("[Governor Mixin] capEffectiveDistance failed", e);
            // ponytail: return original on failure
        }
    }
}
