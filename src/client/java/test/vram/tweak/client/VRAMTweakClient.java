package test.vram.tweak.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import test.vram.tweak.VRAMTweak;
import test.vram.tweak.command.VramTweakCommand;

public class VRAMTweakClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
                VramTweakCommand.register(dispatcher));
        VRAMTweak.LOGGER.info(Component.translatable("vramtweak.client.registered").getString());
    }
}
