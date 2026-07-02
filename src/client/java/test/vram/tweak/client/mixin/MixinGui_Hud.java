package test.vram.tweak.client.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.hud.VramTweakHud;

/**
 * Renders vram-tweak HUD overlay after vanilla GUI render.
 * Pattern aligned with Sodium-Extra: Gui.extractRenderState TAIL + @Local GuiGraphicsExtractor.
 */
@Mixin(Gui.class)
public class MixinGui_Hud {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/hud");
    private static int renderTraceCount;

    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
            at = @At("TAIL"))
    private void onExtractRenderState(DeltaTracker deltaTracker, boolean shouldRenderLevel,
                                      boolean resourcesLoaded, CallbackInfo ci,
                                      @Local GuiGraphicsExtractor guiGraphics) {
        if (!shouldRenderLevel) return; // skip menus / loading screens

        VramTweakHud hud = VramTweakHud.getInstance();
        hud.render(guiGraphics);

        // Trace: log first render call, then every 200th
        renderTraceCount++;
        if (renderTraceCount == 1) {
            LOG.info("[HUD Render Init] first render call OK, textLines={}",
                    hud.getTextLineCount());
        }
        if (renderTraceCount % 200 == 0) {
            LOG.info("[HUD Render Trace] call#{} textLines={}",
                    renderTraceCount, hud.getTextLineCount());
        }
    }
}
