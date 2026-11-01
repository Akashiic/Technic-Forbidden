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
    @SuppressWarnings("unchecked")
    public boolean isDelegationExcluded() {
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
