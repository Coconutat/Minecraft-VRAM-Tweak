package test.vram.tweak.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.mixin.accessor.GameRendererAccessor;
import test.vram.tweak.client.mixin.accessor.GlTextureAccessor;
import test.vram.tweak.client.shader.CasShader;
import test.vram.tweak.diagnostic.VerificationLogger;

/**
 * Apply FSR CAS sharpening after 3D world render, before HUD overlay.
 *
 * 26.2: injects at GameRenderer.renderLevel() RETURN — Gui no longer has render().
 * ponytail: single-pass sharpen, 1 float param, zero config complexity.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_CAS {

    private static boolean casInitialized;
    private static int casFrameCount;

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void applyCasBeforeHud(CallbackInfo ci) {
        try {
            if (!casInitialized) { CasShader.init(); casInitialized = true; VerificationLogger.logCasInit(1); }
            var mc = Minecraft.getInstance();
            var mainTarget = ((GameRendererAccessor) mc.gameRenderer).getMainRenderTarget();
            if (mainTarget == null) return;
            var cfg = test.vram.tweak.config.VRAMConfig.getInstance().cas;
            if (!cfg.enabled || cfg.sharpness <= 0f) return;
            int colorTexId = ((GlTextureAccessor) mainTarget.getColorTexture()).getId();
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            CasShader.apply(colorTexId, w, h);
            casFrameCount++;
            VerificationLogger.logCasFrame(casFrameCount, w, h, cfg.sharpness);
        } catch (Exception e) { /* CAS is cosmetic */ }
    }
}
