package org.spongepowered.asm.service;

/** Stub só para compilar (a classe real vem do Mixin/UniMixins em tempo de execução; não vai no jar). */
public interface ITransformer {
    String getName();

    boolean isDelegationExcluded();
}
