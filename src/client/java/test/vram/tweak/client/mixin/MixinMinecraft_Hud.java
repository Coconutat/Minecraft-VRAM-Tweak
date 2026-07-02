package test.vram.tweak.client.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import test.vram.tweak.client.hud.VramTweakHud;

/**
 * Builds HUD text list each tick, before rendering.
 * Injects Minecraft.tick() HEAD.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_Hud {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/hud");
    private static int tickTraceCount;
    private static boolean initLogged;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (!initLogged) {
            initLogged = true;
            LOG.info("[HUD Tick Init] tick hook active, target=Minecraft.tick() HEAD");
        }

        VramTweakHud.getInstance().onClientTick();

        // Trace: log every 200th tick
        tickTraceCount++;
        if (tickTraceCount % 200 == 0) {
            VramTweakHud hud = VramTweakHud.getInstance();
            LOG.info("[HUD Tick Trace] tick#{} textLines={} fps={} vram={}MB",
                    tickTraceCount, hud.getTextLineCount(),
                    hud.getCurrentSmoothFps(), hud.getCurrentVramMB());
        }
    }
}
