// src/client/java/test/vram/tweak/client/mixin/MixinScreen_RestartWarning.java
package test.vram.tweak.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * needed.</p>
 *
 * <p>Two candidate render entrypoints are declared with {@code require = 0} as a
 * compatibility layer so the warning keeps rendering across minor Minecraft updates.</p>
 */
@Mixin(Screen.class)
public abstract class MixinScreen_RestartWarning {

    private static final String TITLE_KEY = "vramtweak.gui.title";
    private static final String WARNING_KEY = "vramtweak.gui.restartRequired";
    private static final int WARN_COLOR = 0xFFFF5555;
    private static final int WARN_Y = 27;

    @Shadow protected int width;
    @Shadow public abstract Component getTitle();

    // ---- Primary hook: Screen.render(GuiGraphics, int, int, float) on 1.21.11 ----

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("TAIL"),
            require = 0,
            remap = true)
    private void vramTweak_onScreenRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        drawIfNeeded(guiGraphics);
    }

    // ---- Secondary hook: Screen.render with a relaxed method name -------------

    @Inject(method = "render",
            at = @At("TAIL"),
            require = 0,
            remap = true)
    private void vramTweak_onScreenRenderLoose(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                                float partialTick, CallbackInfo ci) {
        drawIfNeeded(guiGraphics);
    }

    // ---- Drawing logic, shared by both candidate hooks --------------------------

    private void drawIfNeeded(GuiGraphics g) {
        try {
            if (g == null || !VRAMConfig.isVramRestartRequired()) return;

            // `this` IS the Screen currently being rendered. Gate by title so we
            // only draw on the VRAM Tweak config screen and nowhere else.
            Component title = getTitle();
            String expectedTitle = Component.translatable(TITLE_KEY).getString();
            if (title == null || !expectedTitle.equals(title.getString())) return;

            var font = Minecraft.getInstance().font;
            Component warning = Component.translatable(WARNING_KEY);

            int textWidth = font.width(warning);
            int x = (this.width - textWidth) / 2;

            g.drawString(font, warning, x, WARN_Y, WARN_COLOR);
        } catch (Throwable t) {
            // Never crash the game over a cosmetic warning.
        }
    }
}