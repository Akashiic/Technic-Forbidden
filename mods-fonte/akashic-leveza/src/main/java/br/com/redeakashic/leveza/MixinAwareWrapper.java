package br.com.redeakashic.leveza;

import net.minecraft.launchwrapper.IClassTransformer;
import org.spongepowered.asm.service.ILegacyClassTransformer;
import org.spongepowered.asm.service.ITransformer;

import java.lang.annotation.Annotation;

/**
 * Embrulho do pré-filtro que se apresenta ao Mixin com a identidade do transformer original.
 *
 * <p>O Mixin, ao reler uma classe-alvo (getClassBytes), roda de novo uma "lista de delegação" de transformers
 * e exclui alguns pelo NOME (lista de exclusões) ou pela anotação javax.annotation.Resource. Sem isto, o
 * embrulho entraria na lista com o próprio nome e os transformers do DragonAPI, que o Mixin exclui
 * normalmente, rodariam duas vezes (visto no cliente real: alterações do Reika registradas 2x).
 * Respondendo com o nome do original, a decisão do Mixin fica idêntica à do pack sem o Leveza.
 *
 * <p>Detalhe medido no cliente real: quem põe os transformers do Reika na lista de exclusões é o
 * Mixingasm (UniMixins). Ele registra o NOME DA CLASSE de cada elemento da lista do LaunchClassLoader
 * (com '$' trocado por '.') que não é "confiável". Se o embrulho já estava na lista quando ele rodou,
 * ele registra o nome do embrulho, e não o do Reika. Por isso isDelegationExcluded() reproduz a regra:
 * excluído se o getName() do original contiver o nome que o Mixingasm teria registrado para ele.
 * Resultado igual ao do pack sem o Leveza: os 5 do DragonAPI excluídos; o InterfaceVerifier do
 * RotaryCraft incluído (o nome registrado tem '.', o getName() tem '$', e o Mixin compara com contains()).
 */
final class MixinAwareWrapper extends ReikaPrefilter.Wrapper implements ILegacyClassTransformer {

    MixinAwareWrapper(IClassTransformer delegate, ReikaPrefilter.Rule rule) {
        super(delegate, rule);
    }

    /** Mesmo nome que o Mixin calcularia para o elemento original (LegacyTransformerHandle usa getClass().getName()). */
    @Override
    public String getName() {
        if (delegate instanceof ITransformer) {
            return ((ITransformer) delegate).getName();
        }
        return delegate.getClass().getName();
    }

    /** Mesma regra do LegacyTransformerHandle: excluído se a classe do original tiver @javax.annotation.Resource. */
    @Override
    public boolean isDelegationExcluded() {
        if (originalExcludedByAnnotation()) {
            return true;
        }
        // Regra do Mixingasm: os 6 transformers do Reika não são "confiáveis" (fora da lista de inclusão)
        if (MIXINGASM_PRESENT) {
            String mixingasmName = delegate.getClass().getName().replace('$', '.');
            return getName().contains(mixingasmName);
        }
        return false;
    }

    private static final boolean MIXINGASM_PRESENT = classExists("makamys.mixingasm.Mixingasm");

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, MixinAwareWrapper.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean originalExcludedByAnnotation() {
        if (delegate instanceof ITransformer) {
            return ((ITransformer) delegate).isDelegationExcluded();
        }
        try {
            Class<? extends Annotation> resource =
                    (Class<? extends Annotation>) Class.forName("javax.annotation.Resource");
            return delegate.getClass().getAnnotation(resource) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        return transform(name, transformedName, basicClass);
    }
}
