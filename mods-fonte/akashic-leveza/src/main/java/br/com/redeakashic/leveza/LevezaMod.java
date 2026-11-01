package br.com.redeakashic.leveza;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Container do mod: aparece na lista de mods e registra o resumo por atlas (só no cliente). */
@Mod(modid = LevezaMod.MODID, name = "Akashic Leveza", version = LevezaMod.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.7.10]")
public final class LevezaMod {

    public static final String MODID = "akashicleveza";
    public static final String VERSION = "0.1.0-alpha.1";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientEvents.register();
        }
    }
}
