package test.vram.tweak.client.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import test.vram.tweak.vram.VRAMGovernor;

/**
 * Caps {@code Options.getEffectiveRenderDistance()} so the settings menu,
 * Sodium, and any other mod reading this method see the governor-capped value.
 *
 * <p>This is the UI/read-side companion to {@link MixinOptions_RenderDistance}
 * which caps the actual chunk-loading radius in {@code ClientChunkCache}.</p>
 */
@Mixin(Options.class)
public class MixinOptions_EffectiveRenderDistance {

    @Inject(method = "getEffectiveRenderDistance()I", at = @At("RETURN"), cancellable = true)
    private void capEffectiveRenderDistance(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        int capped = VRAMGovernor.capRenderDistance(original);
        if (capped != original) {
            cir.setReturnValue(capped);
        }
    }
}
