package br.com.redeakashic.leveza;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Separado do LevezaMod para que a classe de evento só de cliente nunca carregue no servidor.
 * PRECISA ser public (classe, construtor e método): o EventBus do Forge 1.7.10 gera uma classe
 * (ASMEventHandler_*) que chama o handler diretamente e dá IllegalAccessError se não for.
 */
public final class ClientEvents {

    public ClientEvents() {
    }

    static void register() {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStitchPost(TextureStitchEvent.Post event) {
        try {
            LevezaHooks.logSummary();
        } catch (Throwable t) {
            LevezaLog.error("Falha ao gerar o resumo do atlas (ignorado).", t);
        }
    }
}
