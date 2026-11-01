package br.com.redeakashic.leveza;

import net.minecraft.launchwrapper.IClassTransformer;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pré-filtro para os transformers ASM do Reika (DragonAPI / RotaryCraft).
 *
 * <p>Esses transformers fazem o parse completo (ClassReader -> ClassNode) de TODA classe carregada só para
 * descobrir se ela tem uma anotação ou um nome específico; no boot do TEOF isso é ~20% do tempo Java da
 * thread principal. O pré-filtro procura nos bytes da classe as mesmas strings que cada transformer compara
 * e só chama o transformer original quando alguma está presente. Para as demais classes o resultado é o
 * mesmo do transformer original: ou ele devolve os bytes intactos, ou os reescreve com
 * ClassWriter(COMPUTE_MAXS) sem nenhuma alteração (semanticamente idêntico). Auditoria em
 * otimizacao-ram/BOOT.md.
 *
 * <p>Não altera o bytecode do Reika: troca, na lista de transformers do LaunchClassLoader, o elemento do
 * transformer (ou o proxy do CoreTweaks que o envolve) por um embrulho que delega a ele.
 */
final class ReikaPrefilter {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** Regra: o transformer só é chamado se a classe contém TODOS os grupos (cada grupo: QUALQUER marcador). */
    static final class Rule {
        final String transformerClass;
        final byte[][][] groups;

        Rule(String transformerClass, String[]... groups) {
            this.transformerClass = transformerClass;
            this.groups = new byte[groups.length][][];
            for (int g = 0; g < groups.length; g++) {
                this.groups[g] = new byte[groups[g].length][];
                for (int i = 0; i < groups[g].length; i++) {
                    this.groups[g][i] = groups[g][i].getBytes(ASCII);
                }
            }
        }

        boolean mayChange(byte[] bytes) {
            for (byte[][] group : groups) {
                boolean any = false;
                for (byte[] marker : group) {
                    if (indexOf(bytes, marker) >= 0) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            return true;
        }
    }

    static final Rule[] RULES = {
        new Rule("Reika.DragonAPI.ASM.DependentMethodStripper",
                new String[] {"Reika/DragonAPI/ASM/DependentMethodStripper$"}),
        new Rule("Reika.DragonAPI.ASM.ClassReparenter",
                new String[] {"Reika/DragonAPI/ASM/ClassReparenter$Reparent"}),
        new Rule("Reika.DragonAPI.ASM.InterfaceInjector",
                new String[] {"Reika/DragonAPI/ASM/InterfaceInjector$Injectable"}),
        new Rule("Reika.DragonAPI.ASM.SpecialBiomePlacement",
                new String[] {"net/minecraft/world/gen/layer/GenLayerBiome"}),
        new Rule("Reika.DragonAPI.ASM.StructureLootHooks",
                new String[] {"net/minecraft/world/gen/structure/StructureComponent",
                        "net/minecraft/world/gen/structure/ComponentScatteredFeaturePieces$Feature",
                        "net/minecraft/world/gen/structure/StructureNetherBridgePieces$Piece",
                        "net/minecraft/world/gen/structure/StructureStrongholdPieces$Stronghold"}),
        new Rule("Reika.RotaryCraft.Auxiliary.RotaryASMHandler$InterfaceVerifier",
                new String[] {"Reika/RotaryCraft/"},
                new String[] {"cofh/api/energy/IEnergyHandler", "cofh/api/energy/IEnergyReceiver"}),
    };

    static final AtomicLong classesSeen = new AtomicLong();
    static final AtomicLong callsSkipped = new AtomicLong();
    static final AtomicLong callsForwarded = new AtomicLong();

    private static volatile int lastSize = -1;
    private static volatile boolean broken;
    private static volatile int wrapped;

    private ReikaPrefilter() {
    }

    /** Embrulho colocado na lista do LaunchClassLoader. */
    static class Wrapper implements IClassTransformer {
        final IClassTransformer delegate;
        final Rule rule;

        Wrapper(IClassTransformer delegate, Rule rule) {
            this.delegate = delegate;
            this.rule = rule;
        }

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (bytes == null || rule.mayChange(bytes)) {
                callsForwarded.incrementAndGet();
                return delegate.transform(name, transformedName, bytes);
            }
            callsSkipped.incrementAndGet();
            return bytes;
        }

        @Override
        public String toString() {
            return "AkashicLeveza-Prefiltro[" + rule.transformerClass + "] -> " + delegate;
        }
    }

    /** Chamado a cada classe pelo LevezaTransformer; só reexamina a lista quando ela muda de tamanho. */
    @SuppressWarnings("unchecked")
    static void maybeInstall() {
        if (broken) {
            return;
        }
        try {
            Object loader = net.minecraft.launchwrapper.Launch.classLoader;
            if (loader == null) {
                return;
            }
            Field f = transformersField(loader.getClass());
            List<IClassTransformer> list = (List<IClassTransformer>) f.get(loader);
            int size = list.size();
            classesSeen.incrementAndGet();
            if (size == lastSize) {
                return;
            }
            synchronized (ReikaPrefilter.class) {
                if (size == lastSize) {
                    return;
                }
                for (int i = 0; i < list.size(); i++) {
                    IClassTransformer t = list.get(i);
                    if (isOurs(t)) {
                        continue;
                    }
                    Rule r = ruleFor(t);
                    if (r != null) {
                        list.set(i, newWrapper(t, r)); // set() não altera modCount: seguro durante a iteração
                        wrapped++;
                        LevezaLog.info("Pre-filtro instalado para " + r.transformerClass + " (" + t.getClass().getName() + ")");
                    }
                }
                lastSize = list.size();
            }
        } catch (Throwable t) {
            broken = true;
            LevezaLog.error("Pre-filtro dos transformers do Reika desativado (erro ao instalar).", t);
        }
    }

    private static volatile boolean mixinAware = true;

    /** Usa o embrulho que fala com o Mixin; se o Mixin não existir em tempo de execução, o simples. */
    static Wrapper newWrapper(IClassTransformer t, Rule r) {
        if (mixinAware) {
            try {
                return new MixinAwareWrapper(t, r);
            } catch (Throwable e) {
                mixinAware = false;
                LevezaLog.warn("Mixin ausente: pre-filtro usa o embrulho simples.");
            }
        }
        return new Wrapper(t, r);
    }

    private static Field cachedField;

    private static Field transformersField(Class<?> c) throws NoSuchFieldException {
        if (cachedField != null) {
            return cachedField;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField("transformers");
                f.setAccessible(true);
                cachedField = f;
                return f;
            } catch (NoSuchFieldException ignored) {
                // superclasse
            }
        }
        throw new NoSuchFieldException("LaunchClassLoader.transformers");
    }

    /*
     * IMPORTANTE: nada de reflexão genérica (getMethods/getDeclaredFields) em transformers desconhecidos.
     * A reflexão carrega as classes dos tipos dos campos/métodos; um transformer com campo do tipo de uma
     * classe do Minecraft a carregaria antes dos mixins (visto no cliente real: MixinChunk "loaded too
     * early"). Só nomes de classe (getName/getSuperclass) e o campo "original" do proxy do CoreTweaks.
     */
    private static final String CORETWEAKS_PROXY = "makamys.coretweaks.optimization.transformerproxy.TransformerProxy";

    /** O elemento (ou algum transformer que ele envolve) já é um embrulho nosso? */
    static boolean isOurs(Object t) {
        for (int depth = 0; t != null && depth < 6; depth++) {
            if (t instanceof Wrapper) {
                return true;
            }
            t = unwrap(t);
        }
        return false;
    }

    /** Regra do transformer do Reika contido no elemento (desembrulhando o proxy do CoreTweaks), ou null. */
    static Rule ruleFor(Object t) {
        for (int depth = 0; t != null && depth < 6; depth++) {
            for (Class<?> k = t.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Rule r : RULES) {
                    if (r.transformerClass.equals(k.getName())) {
                        return r;
                    }
                }
            }
            t = unwrap(t);
        }
        return null;
    }

    /** Transformer envolvido pelo proxy do CoreTweaks (campo "original"), ou null para qualquer outra classe. */
    static Object unwrap(Object t) {
        if (t instanceof Wrapper) {
            return ((Wrapper) t).delegate;
        }
        for (Class<?> k = t.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (CORETWEAKS_PROXY.equals(k.getName())) {
                try {
                    Field f = k.getDeclaredField("original"); // campos do proxy: IClassTransformer, List, int
                    f.setAccessible(true);
                    Object o = f.get(t);
                    return o != t ? o : null;
                } catch (Throwable e) {
                    return null;
                }
            }
        }
        return null;
    }

    static String summary() {
        long fwd = callsForwarded.get();
        long skip = callsSkipped.get();
        if (wrapped == 0) {
            return "pre-filtro Reika: inativo";
        }
        return "pre-filtro Reika: " + wrapped + " transformers, " + skip + " chamadas evitadas, " + fwd + " encaminhadas";
    }

    static int indexOf(byte[] hay, byte[] needle) {
        int n = needle.length;
        int last = hay.length - n;
        byte first = needle[0];
        outer:
        for (int i = 0; i <= last; i++) {
            if (hay[i] != first) {
                continue;
            }
            for (int j = 1; j < n; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
