package test.vram.tweak.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import test.vram.tweak.VRAMTweak;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.command.VramTweakCommand;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.vram.VRAMGovernor;

public class VRAMTweakClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        VRAMConfig.load(FabricLoader.getInstance().getConfigDir());
        var cfg = VRAMConfig.getInstance();
        test.vram.tweak.gpu.VoxyTweak.applyGeometryLimit();
        VRAMTweak.LOGGER.info("VRAM optimizer. enabled={}, shadowCap={}, downscale={}, budget={}",
                cfg.vram.enabled, cfg.vram.shadowMapMaxSize,
                cfg.vram.formatDownscale, cfg.vram.budgetTracking);

        // AllocTracker: activate if config says so (no restart needed)
        if (cfg.diagnostic.allocTracker) {
            VramAllocationTracker.getInstance().activate();
        }

        VRAMGovernor.initialize();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                VramTweakCommand.register(dispatcher));
        VRAMTweak.LOGGER.info(Component.translatable("vramtweak.client.registered").getString());
    }
}
