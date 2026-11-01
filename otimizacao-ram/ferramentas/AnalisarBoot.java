import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê uma gravação JFR do boot do Minecraft (thread "Client thread") e mostra para onde foi o tempo.
 *
 *   java AnalisarBoot.java boot.jfr [quantidade]
 *   java AnalisarBoot.java boot.jfr quem <regex do método no topo ou em qualquer frame>
 *        mostra quem chama (primeiros frames fora do JDK) os trechos que casam com a regex
 *
 * Gravação: no Java 8 (8u262+) adicione aos argumentos da JVM
 *   -XX:StartFlightRecording=name=boot,settings=profile,maxsize=2g
 * e, com o jogo no menu, rode:  jcmd <pid> JFR.dump name=boot filename=boot.jfr
 * Requer JDK 11+ para rodar este analisador (o arquivo do Java 8 é compatível).
 */
public class AnalisarBoot {

    static final boolean INCLUIR_NATIVO = Boolean.getBoolean("nativo");

    static final String[] FRAMEWORK = {"java.", "javax.", "sun.", "jdk.", "com.sun.", "cpw.mods.fml.", "net.minecraft.",
            "net.minecraftforge.", "com.google.", "org.apache.", "org.objectweb.", "org.spongepowered.", "com.llamalad7.",
            "scala.", "kotlin.", "io.netty.", "gnu.trove.", "it.unimi.", "org.lwjgl."};

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        if (args.length > 2 && args[1].equals("quem")) {
            quem(file, java.util.regex.Pattern.compile(args[2]));
            return;
        }
        int top = args.length > 1 ? Integer.parseInt(args[1]) : 25;
        Map<String, Long> byMod = new HashMap<>();
        Map<String, Long> byCause = new HashMap<>();
        Map<String, Long> byTransformer = new HashMap<>();
        Map<String, Long> byLeafMethod = new HashMap<>();
        Map<String, Map<String, Long>> modCause = new HashMap<>();
        long total = 0;
        long first = Long.MAX_VALUE;
        long last = 0;

        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                // Só amostras de execução Java: no JFR do Java 8 as amostras nativas usam outra frequência e
                // outra regra de seleção de threads, então misturar as duas distorce as porcentagens.
                if (!type.equals("jdk.ExecutionSample")
                        && !(INCLUIR_NATIVO && type.equals("jdk.NativeMethodSample"))) {
                    continue;
                }
                RecordedThread t = e.getThread("sampledThread");
                if (t == null || !"Client thread".equals(t.getJavaName())) {
                    continue;
                }
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) {
                    continue;
                }
                List<RecordedFrame> frames = st.getFrames(); // [0] = topo
                long ts = e.getStartTime().toEpochMilli();
                first = Math.min(first, ts);
                last = Math.max(last, ts);
                total++;
                String mod = modOf(frames);
                String cause = causeOf(frames, byTransformer);
                byMod.merge(mod, 1L, Long::sum);
                byCause.merge(cause, 1L, Long::sum);
                modCause.computeIfAbsent(mod, k -> new HashMap<>()).merge(cause, 1L, Long::sum);
                if (!frames.isEmpty()) {
                    byLeafMethod.merge(name(frames.get(0)), 1L, Long::sum);
                }
            }
        }
        System.out.printf("Amostras da thread principal: %d (janela de %.0f s)%n%n", total, (last - first) / 1000.0);
        print("POR MOD (quem estava inicializando)", byMod, total, top);
        print("POR CAUSA (o que o código estava fazendo)", byCause, total, top);
        print("TRANSFORMERS mais caros (dentro de carregamento de classe)", byTransformer, total, top);
        print("MÉTODOS NO TOPO DA PILHA", byLeafMethod, total, top);
        final long totalF = total;
        System.out.println("== CAUSAS DENTRO DOS 10 MODS MAIS CAROS");
        byMod.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10).forEach(m -> {
            System.out.printf("  %-28s %5.1f%%  ", m.getKey(), 100.0 * m.getValue() / totalF);
            List<Map.Entry<String, Long>> c = new ArrayList<>(modCause.get(m.getKey()).entrySet());
            c.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, c.size()); i++) {
                sb.append(String.format("%s %.0f%%; ", c.get(i).getKey(), 100.0 * c.get(i).getValue() / m.getValue()));
            }
            System.out.println(sb);
        });
    }

    static void quem(Path file, java.util.regex.Pattern pat) throws Exception {
        Map<String, Long> callers = new HashMap<>();
        Map<String, Long> chains = new HashMap<>();
        Map<String, Long> roots = new HashMap<>();
        long total = 0;
        long hits = 0;
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                if (!type.equals("jdk.ExecutionSample") && !(INCLUIR_NATIVO && type.equals("jdk.NativeMethodSample"))) continue;
                RecordedThread t = e.getThread("sampledThread");
                if (t == null || !"Client thread".equals(t.getJavaName()) || e.getStackTrace() == null) continue;
                total++;
                List<RecordedFrame> fr = e.getStackTrace().getFrames();
                int idx = -1;
                for (int i = 0; i < fr.size(); i++) {
                    if (pat.matcher(name(fr.get(i))).find()) { idx = i; break; }
                }
                if (idx < 0) continue;
                hits++;
                // primeiros 3 frames abaixo do trecho que não são do JDK
                List<String> out = new ArrayList<>();
                for (int i = idx + 1; i < fr.size() && out.size() < 3; i++) {
                    String n = name(fr.get(i));
                    if (n.startsWith("java.") || n.startsWith("sun.") || n.startsWith("jdk.")) continue;
                    out.add(n);
                }
                callers.merge(out.isEmpty() ? "?" : out.get(0), 1L, Long::sum);
                roots.merge(modOf(fr) + "  (" + rootHandler(fr) + ")", 1L, Long::sum);
                chains.merge(String.join("  <-  ", out), 1L, Long::sum);
            }
        }
        System.out.printf("Amostras com %s: %d de %d (%.1f%% da thread principal)%n%n", pat, hits, total, 100.0 * hits / total);
        print("MOD DE ORIGEM (handler do FML ou listener)", roots, total, 15);
        print("CHAMADOR DIRETO", callers, total, 20);
        print("CADEIA (3 níveis)", chains, total, 15);
    }

    /** Primeiro método de mod logo acima do despacho do FML/EventBus/recarga de recursos. */
    static String rootHandler(List<RecordedFrame> fr) {
        for (int i = fr.size() - 1; i >= 0; i--) {
            String cls = fr.get(i).getMethod().getType().getName();
            if (!isFramework(cls)) {
                return cls.substring(cls.lastIndexOf('.') + 1) + "." + fr.get(i).getMethod().getName();
            }
        }
        return "?";
    }

    /** Mod = primeiro frame fora dos frameworks, procurando da base (raiz) para o topo. */
    static String modOf(List<RecordedFrame> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            String cls = frames.get(i).getMethod().getType().getName();
            if (!isFramework(cls)) {
                return pkg(cls);
            }
        }
        // só framework: separa as fases do próprio FML/Minecraft
        for (RecordedFrame f : frames) {
            String n = name(f);
            if (n.contains("TextureMap") || n.contains("TextureAtlasSprite")) return "(minecraft: texturas)";
            if (n.contains("SoundManager") || n.contains("SoundHandler")) return "(minecraft: sons)";
            if (n.contains("ModDiscoverer") || n.contains("JarDiscoverer") || n.contains("ASMModParser")) return "(fml: descoberta de mods)";
            if (n.contains("LaunchClassLoader")) return "(fml: carregamento de classes)";
        }
        return "(framework)";
    }

    static String causeOf(List<RecordedFrame> frames, Map<String, Long> byTransformer) {
        boolean classLoad = false;
        String transformer = null;
        for (RecordedFrame f : frames) { // do topo para a base
            String cls = f.getMethod().getType().getName();
            String m = f.getMethod().getName();
            if (transformer == null && m.equals("transform") && !cls.startsWith("net.minecraft.launchwrapper.")
                    && !cls.contains("TransformerWrapper") && !cls.contains("CachedTransformer")) {
                transformer = cls;
            }
            if (cls.equals("net.minecraft.launchwrapper.LaunchClassLoader")
                    && (m.equals("findClass") || m.equals("runTransformers"))) {
                classLoad = true;
                break;
            }
        }
        if (classLoad) {
            if (transformer != null) {
                byTransformer.merge(transformer, 1L, Long::sum);
            }
            return "carregar/transformar classes";
        }
        RecordedFrame topFrame = frames.isEmpty() ? null : frames.get(0);
        for (RecordedFrame f : frames) {
            String cls = f.getMethod().getType().getName();
            if (cls.startsWith("java.util.zip.") || cls.contains("ZipFile")) return "ler zip/jar";
            if (cls.startsWith("java.util.regex.")) return "regex";
            if (cls.startsWith("com.google.gson.") || cls.contains("fasterxml") || cls.contains("JsonParser")) return "JSON";
            if (cls.startsWith("javax.imageio.") || cls.contains("PNG")) return "decodificar imagem";
            if (cls.startsWith("org.lwjgl.") || cls.contains("GL11")) return "OpenGL";
            if (cls.startsWith("java.io.") || cls.startsWith("java.nio.")) return "E/S de arquivo";
            if (cls.contains("Reflection") || cls.startsWith("java.lang.reflect.") || cls.startsWith("sun.reflect.")) {
                if (f == topFrame) return "reflexão";
            }
        }
        return topFrame == null ? "?" : "código: " + pkg(topFrame.getMethod().getType().getName());
    }

    static boolean isFramework(String cls) {
        for (String p : FRAMEWORK) {
            if (cls.startsWith(p)) return true;
        }
        return cls.startsWith("$Proxy") || cls.startsWith("ASMEventHandler") || cls.contains("ASMEventHandler_");
    }

    static String pkg(String cls) {
        String[] p = cls.split("\\.");
        if (p.length <= 2) return cls;
        int n = (p[0].equals("com") || p[0].equals("net") || p[0].equals("org") || p[0].equals("br") || p[0].equals("io")) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, p.length - 1); i++) {
            if (i > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    static String name(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName();
    }

    static void print(String title, Map<String, Long> m, long total, int top) {
        System.out.println("== " + title);
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(top)
                .forEach(x -> System.out.printf("  %5.1f%%  %s%n", 100.0 * x.getValue() / total, x.getKey()));
        System.out.println();
    }
}
