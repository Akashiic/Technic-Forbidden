package br.com.redeakashic.leveza;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Injeta chamadas no INÍCIO de quatro métodos (sem mexer em call sites, para não brigar com mixins):
 *
 * <ul>
 *   <li>TextureAtlasSprite.loadSprite: troca images[0] e o AnimationMetadataSection por versões reduzidas.</li>
 *   <li>SimpleTexture.loadTexture: registra qual ResourceLocation está carregando (contexto do upload).</li>
 *   <li>TextureUtil.uploadTextureImageAllocate: limita o tamanho da imagem enviada à GPU.</li>
 *   <li>Reika ReikaTextureBinder: idem, para as texturas do Reika fora de assets/.</li>
 * </ul>
 *
 * Só são inseridas instruções lineares antes da primeira instrução original, gravando nos slots dos
 * próprios parâmetros valores do mesmo tipo; por isso os stack map frames existentes continuam válidos.
 */
public final class LevezaTransformer implements IClassTransformer, Opcodes {

    private static final String HOOKS = "br/com/redeakashic/leveza/LevezaHooks";
    private static final String IMG = "java/awt/image/BufferedImage";
    private static final String META = "net/minecraft/client/resources/data/AnimationMetadataSection";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || transformedName == null) {
            return bytes;
        }
        try {
            if ("net.minecraft.client.renderer.texture.TextureAtlasSprite".equals(transformedName)) {
                return patch(bytes, transformedName, Kind.SPRITE);
            }
            if ("net.minecraft.client.renderer.texture.TextureUtil".equals(transformedName)) {
                return patch(bytes, transformedName, Kind.TEXTURE_UTIL);
            }
            if ("net.minecraft.client.renderer.texture.SimpleTexture".equals(transformedName)) {
                return patch(bytes, transformedName, Kind.SIMPLE_TEXTURE);
            }
            if ("Reika.DragonAPI.IO.ReikaTextureBinder".equals(transformedName)) {
                return patch(bytes, transformedName, Kind.REIKA);
            }
        } catch (Throwable t) {
            LevezaLog.error("Falha ao transformar " + transformedName + "; classe mantida original.", t);
        }
        return bytes;
    }

    enum Kind { SPRITE, TEXTURE_UTIL, SIMPLE_TEXTURE, REIKA }

    static byte[] patch(byte[] bytes, String className, Kind kind) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int patched = 0;
        for (Object o : node.methods) {
            MethodNode m = (MethodNode) o;
            InsnList code = hookFor(kind, m);
            if (code != null) {
                AbstractInsnNode first = m.instructions.getFirst();
                if (first == null) {
                    continue;
                }
                m.instructions.insertBefore(first, code);
                patched++;
            }
        }
        if (patched == 0) {
            LevezaLog.warn("Nenhum método-alvo encontrado em " + className + " (" + kind + "); nada alterado.");
            return bytes;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        LevezaLog.info("Gancho instalado em " + className + " (" + patched + " método(s)).");
        return cw.toByteArray();
    }

    private static InsnList hookFor(Kind kind, MethodNode m) {
        InsnList l = new InsnList();
        switch (kind) {
            case SPRITE:
                if (isAny(m.name, "func_147964_a", "loadSprite")
                        && m.desc.equals("([L" + IMG + ";L" + META + ";Z)V")) {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(ALOAD, 1));
                    l.add(new VarInsnNode(ALOAD, 2));
                    l.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "preLoadSprite",
                            "(Ljava/lang/Object;[L" + IMG + ";Ljava/lang/Object;)Ljava/lang/Object;", false));
                    l.add(new TypeInsnNode(CHECKCAST, META));
                    l.add(new VarInsnNode(ASTORE, 2));
                    return l;
                }
                return null;
            case TEXTURE_UTIL:
                if (isAny(m.name, "func_110989_a", "uploadTextureImageAllocate")
                        && m.desc.equals("(IL" + IMG + ";ZZ)I") && (m.access & ACC_STATIC) != 0) {
                    l.add(new VarInsnNode(ALOAD, 1));
                    l.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "preUploadImage",
                            "(L" + IMG + ";)L" + IMG + ";", false));
                    l.add(new VarInsnNode(ASTORE, 1));
                    return l;
                }
                return null;
            case SIMPLE_TEXTURE:
                if (isAny(m.name, "func_110551_a", "loadTexture")
                        && m.desc.equals("(Lnet/minecraft/client/resources/IResourceManager;)V")) {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "enterSimpleTexture", "(Ljava/lang/Object;)V", false));
                    return l;
                }
                return null;
            case REIKA:
                if ((m.name.equals("allocateAndSetupTexture") && m.desc.equals("(L" + IMG + ";)I"))
                        || (m.name.equals("loadImageOntoTexture") && m.desc.equals("(L" + IMG + ";I)V"))) {
                    l.add(new VarInsnNode(ALOAD, 1));
                    l.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "preUploadReika",
                            "(L" + IMG + ";)L" + IMG + ";", false));
                    l.add(new VarInsnNode(ASTORE, 1));
                    return l;
                }
                return null;
            default:
                return null;
        }
    }

    private static boolean isAny(String name, String a, String b) {
        return name.equals(a) || name.equals(b);
    }
}
