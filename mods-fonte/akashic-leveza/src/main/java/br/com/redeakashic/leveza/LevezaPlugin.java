package br.com.redeakashic.leveza;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Coremod do Akashic Leveza. Roda depois da deobfuscação do FML (SortingIndex 1001), então os
 * transformers enxergam os nomes SRG (func_xxx/field_xxx) usados em tempo de execução.
 */
@IFMLLoadingPlugin.Name("AkashicLeveza")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions("br.com.redeakashic.leveza.")
public final class LevezaPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {LevezaTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        Object mcLocation = data.get("mcLocation");
        if (mcLocation instanceof java.io.File) {
            LevezaConfig.setMinecraftHome((java.io.File) mcLocation);
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
