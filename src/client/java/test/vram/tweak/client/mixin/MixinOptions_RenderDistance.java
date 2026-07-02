package test.vram.tweak.client.mixin;

import net.minecraft.client.Options;
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

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void capEffectiveDistance(CallbackInfoReturnable<Integer> cir) {
        try {
            int capped = VRAMGovernor.capRenderDistance(cir.getReturnValue());
            cir.setReturnValue(capped);
        } catch (Exception e) {
            // ponytail: return original on failure
        }
    }
}
