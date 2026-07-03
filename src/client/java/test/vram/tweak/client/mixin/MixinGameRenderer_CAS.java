package test.vram.tweak.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.mixin.accessor.GlTextureAccessor;
import test.vram.tweak.client.shader.CasShader;

/**
 * Apply FSR CAS sharpening after world render, before HUD overlay.
 *
 * Injects just before Gui.render() call in GameRenderer.render().
 * CAS reads main framebuffer color texture, writes sharpened result back.
 *
 * ponytail: single-pass sharpen, 1 float param, zero config complexity.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_CAS {

    private static boolean casInitialized;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void applyCasBeforeHud(CallbackInfo ci) {
        try {
            if (!casInitialized) {
                CasShader.init();
                casInitialized = true;
            }

            var mc = Minecraft.getInstance();
            var mainTarget = mc.getMainRenderTarget();
            if (mainTarget == null) return;

            int colorTexId = ((GlTextureAccessor) mainTarget.getColorTexture()).getId();
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();

            CasShader.apply(colorTexId, w, h);
        } catch (Exception e) {
            // ponytail: CAS is cosmetic, never crash the game
        }
    }
}
