package br.com.redeakashic.leveza;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

/** Separado do LevezaMod para que a classe de evento só de cliente nunca carregue no servidor. */
final class ClientEvents {

    private ClientEvents() {
    }

    static void register() {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStitchPost(TextureStitchEvent.Post event) {
        LevezaHooks.logSummary();
    }
}
