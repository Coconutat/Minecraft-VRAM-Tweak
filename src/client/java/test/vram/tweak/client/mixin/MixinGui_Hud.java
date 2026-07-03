package test.vram.tweak.client.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.hud.VramTweakHud;

/**
 * Renders vram-tweak HUD overlay after vanilla GUI render.
 * 1.21.11: injects Gui.render() RETURN with frame guard (no extractRenderState in 1.21.11).
 */
@Mixin(Gui.class)
public class MixinGui_Hud {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/hud");
    private static int renderTraceCount;
    private static int lastFrameRendered = -1;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void onRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        // ponytail: frame guard — Gui.render() may be called multiple times (sub-pass), draw only once
        int currentFrame = (int) System.nanoTime();
        if (currentFrame == lastFrameRendered) return;
        lastFrameRendered = currentFrame;

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
