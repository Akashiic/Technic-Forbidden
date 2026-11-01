package org.spongepowered.asm.service;

/** Stub só para compilar (a classe real vem do Mixin/UniMixins em tempo de execução; não vai no jar). */
public interface ILegacyClassTransformer extends ITransformer {
    byte[] transformClassBytes(String name, String transformedName, byte[] basicClass);
}
