package test.vram.tweak.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;

/**
 * Caps animated texture frame count.
 * MC 26.2: getFrameCount() is private, called from createAnimatedTexture().
 */
@Mixin(SpriteContents.class)
public class MixinSpriteContents_AnimationLimit {

    @WrapOperation(
        method = "createAnimatedTexture",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/SpriteContents;getFrameCount()I"),
        require = 0
    )
    private static int capFrameCount(SpriteContents self, Operation<Integer> original) {
        int frameCount = original.call(self);
        var cfg = VRAMConfig.getInstance().texture;
        if (!cfg.animationLimit) return frameCount;
        int capped = Math.min(frameCount, cfg.maxAnimationFrames);
        if (capped < frameCount) VerificationLogger.logAnimationCap(frameCount, capped);
        return capped;
    }
}
