package test.vram.tweak.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.config.VRAMConfig;

/**
 * Draws a red "restart required" banner under the VRAM Tweak config screen title
 * when the "Enable VRAM Optimization" toggle has been flipped since game launch.
 *
 * <p>Injected directly into {@link Screen} — {@code this} <em>is</em> the currently
 * displayed screen, so no accessor for the private {@code Minecraft.screen} field is
 * needed (that field does not exist under that name in 26.2, which caused a previous
 * accessor-based approach to crash at startup).</p>
 *
 * <p>Three candidate render entrypoints are declared with {@code require = 0}. They
 * are not redundancy — they are a deliberate compatibility layer so the warning keeps
 * rendering when Mojang reshuffles the Screen API between snapshots.</p>
 */
@Mixin(Screen.class)
public abstract class MixinScreen_RestartWarning {

    private static final String TITLE_KEY = "vramtweak.gui.title";
    private static final String WARNING_KEY = "vramtweak.gui.restartRequired";
    private static final int WARN_COLOR = 0xFFFF5555;
    private static final int WARN_Y = 27;

    @Shadow protected int width;

    @Shadow public abstract Component getTitle();

    // ---- Render hook candidates (require = 0 → silently skip if 26.2 removed them) -

    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
            at = @At("TAIL"), require = 0, remap = true)
    private void vramTweak_onExtractRenderState(DeltaTracker deltaTracker, boolean par2,
                                                 boolean par3, CallbackInfo ci,
                                                 @Local GuiGraphicsExtractor guiGraphics) {
        drawIfNeeded(guiGraphics);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL"), require = 0, remap = true)
    private void vramTweak_onRender(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                     float partialTick, CallbackInfo ci) {
        drawIfNeeded(g);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0, remap = true)
    private void vramTweak_onExtractRenderStateLoose(CallbackInfo ci,
                                                      @Local GuiGraphicsExtractor guiGraphics) {
        drawIfNeeded(guiGraphics);
    }

    // ---- Drawing logic, shared by all candidate hooks -----------------------------

    private void drawIfNeeded(GuiGraphicsExtractor g) {
        try {
            if (g == null || !VRAMConfig.isVramRestartRequired()) return;

            // `this` IS the Screen currently being rendered. Gate by title so we
            // only draw on the VRAM Tweak config screen and nowhere else.
            Component title = getTitle();
            String expectedTitle = Component.translatable(TITLE_KEY).getString();
            if (title == null || !expectedTitle.equals(title.getString())) return;

            var font = Minecraft.getInstance().font;
            Component warning = Component.translatable(WARNING_KEY);

            int textWidth = font.width(warning.getString());
            int x = (this.width - textWidth) / 2;

            g.text(font, warning, x, WARN_Y, WARN_COLOR);
        } catch (Throwable t) {
            // Never crash the game over a cosmetic warning.
        }
    }
}