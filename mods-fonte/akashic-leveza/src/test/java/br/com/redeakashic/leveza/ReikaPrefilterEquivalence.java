package br.com.redeakashic.leveza;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prova de equivalência do pré-filtro: roda os transformers ORIGINAIS do Reika sobre todas as classes de
 * todos os jars de uma pasta mods/ que o pré-filtro pularia, e confere que a saída é semanticamente idêntica
 * à entrada (as duas normalizadas por ClassReader -> ClassNode -> ClassWriter(COMPUTE_MAXS)).
 *
 * Uso: ReikaPrefilterEquivalence pasta-mods
 * (classpath com Forge, DragonAPI e RotaryCraft do pack)
 */
public final class ReikaPrefilterEquivalence {

    public static void main(String[] args) throws Exception {
        // os transformers do DragonAPI só agem em ambiente obfuscado (RUNTIME_DEOBF); força o modo de jogo
        Class<?> plugin = Class.forName("net.minecraftforge.classloading.FMLForgePlugin");
        Field deobf = plugin.getField("RUNTIME_DEOBF");
        Field mods = Field.class.getDeclaredField("modifiers");
        mods.setAccessible(true);
        mods.setInt(deobf, deobf.getModifiers() & ~Modifier.FINAL);
        deobf.setBoolean(null, true);
        // o log do FML exige o lado definido (no jogo o FML faz isso no boot)
        Class<?> side = Class.forName("cpw.mods.fml.relauncher.Side");
        Field logSide = Class.forName("cpw.mods.fml.relauncher.FMLRelaunchLog").getDeclaredField("side");
        logSide.setAccessible(true);
        logSide.set(null, side.getField("CLIENT").get(null));
        // versão do Forge que o FML injeta no boot (o ReikaASMHelper lê na inicialização): 10.13.4.1614
        Class<?> inj = Class.forName("cpw.mods.fml.relauncher.FMLInjectionData");
        String[][] ver = {{"major", "10"}, {"minor", "13"}, {"rev", "4"}, {"build", "1614"}, {"mccversion", "1.7.10"}, {"mcpversion", "9.05"}};
        for (String[] kv : ver) {
            try {
                Field fv = inj.getDeclaredField(kv[0]);
                fv.setAccessible(true);
                fv.set(null, kv[1]);
            } catch (NoSuchFieldException ignored) {
                // campo opcional
            }
        }

        List<IClassTransformer> tx = new ArrayList<>();
        List<ReikaPrefilter.Rule> rules = new ArrayList<>();
        for (ReikaPrefilter.Rule r : ReikaPrefilter.RULES) {
            tx.add((IClassTransformer) Class.forName(r.transformerClass).getDeclaredConstructor().newInstance());
            rules.add(r);
        }

        long classes = 0;
        long[] skipped = new long[rules.size()];
        long[] forwarded = new long[rules.size()];
        long[] mismatch = new long[rules.size()];
        long[] errors = new long[rules.size()];
        List<String> samples = new ArrayList<>();
        File[] jars = new File(args[0]).listFiles((d, n) -> n.endsWith(".jar"));
        Arrays.sort(jars);
        for (File jar : jars) {
            try (ZipFile z = new ZipFile(jar)) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().contains("META-INF/versions/")) {
                        continue;
                    }
                    byte[] in = LevezaSelfTest.readAll(z.getInputStream(e));
                    String name = e.getName().substring(0, e.getName().length() - 6).replace('/', '.');
                    classes++;
                    byte[] normIn = null;
                    for (int i = 0; i < tx.size(); i++) {
                        if (rules.get(i).mayChange(in)) {
                            forwarded[i]++;
                            continue;
                        }
                        skipped[i]++;
                        byte[] out;
                        try {
                            out = tx.get(i).transform(name, name, in.clone());
                        } catch (Throwable t) {
                            if (errors[i]++ == 0) {
                                System.out.println("  primeiro erro de " + rules.get(i).transformerClass + " em " + name + ":");
                                t.printStackTrace(System.out);
                            }
                            continue;
                        }
                        if (out == null || Arrays.equals(out, in)) {
                            continue;
                        }
                        if (normIn == null) {
                            normIn = normalize(in);
                        }
                        if (normIn == null || !Arrays.equals(normalize(out), normIn)) {
                            mismatch[i]++;
                            if (samples.size() < 15) {
                                samples.add(rules.get(i).transformerClass + " alterou " + name + " (" + jar.getName() + ")");
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("Classes examinadas: %d em %d jars%n", classes, jars.length);
        long totalMismatch = 0;
        for (int i = 0; i < rules.size(); i++) {
            System.out.printf("  %-62s puladas=%7d encaminhadas=%5d divergencias=%d erros_do_original=%d%n",
                    rules.get(i).transformerClass, skipped[i], forwarded[i], mismatch[i], errors[i]);
            totalMismatch += mismatch[i];
        }
        samples.forEach(s -> System.out.println("  DIVERGENCIA: " + s));
        System.out.println(totalMismatch == 0 ? "EQUIVALENTE: nenhuma classe pulada seria alterada pelo transformer original"
                : "FALHA: " + totalMismatch + " classes puladas seriam alteradas");
        System.exit(totalMismatch == 0 ? 0 : 1);
    }

    static byte[] normalize(byte[] b) {
        try {
            ClassNode n = new ClassNode();
            new ClassReader(b).accept(n, 0);
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            n.accept(w);
            return w.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
