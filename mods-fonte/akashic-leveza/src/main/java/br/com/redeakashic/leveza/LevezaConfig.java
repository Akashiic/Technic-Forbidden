package br.com.redeakashic.leveza;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Configuração em config/akashic-leveza.properties. Lida uma única vez, no primeiro uso.
 * Também aceita -Dakashic.leveza.perfil=... na linha de comando (tem prioridade sobre o arquivo).
 */
final class LevezaConfig {

    static final String FILE_NAME = "akashic-leveza.properties";

    /** {anim, frames, estatico, modelo, outros} */
    private static final int[][] PROFILES = {
        {128, Integer.MAX_VALUE, 128, 1024, 2048}, // qualidade
        {64, Integer.MAX_VALUE, 64, 512, 2048},    // equilibrado
        {64, 64, 64, 256, 1024},                   // leve
        {32, 32, 32, 128, 512},                    // batata
    };
    private static final String[] PROFILE_NAMES = {"qualidade", "equilibrado", "leve", "batata"};

    private static volatile File minecraftHome;
    private static volatile LevezaConfig instance;

    final boolean enabled;
    final String profile;
    final int animMax;
    final int framesMax;
    final int staticMax;
    final int modelMax;
    final int otherMax;
    final boolean reduceAtlas;
    final boolean onlyVanillaAtlas;
    final boolean reduceLargeTextures;
    final boolean reduceReika;
    final boolean verbose;
    final boolean decodeCache;
    final int decodeCacheMinKb;
    final List<String> ignoredDomains;
    final List<String> ignoredPrefixes;

    private LevezaConfig(Properties p) {
        String prof = System.getProperty("akashic.leveza.perfil", p.getProperty("perfil", "leve"))
                .trim().toLowerCase(Locale.ROOT);
        int idx = 2;
        boolean on = !"desligado".equals(prof);
        for (int i = 0; i < PROFILE_NAMES.length; i++) {
            if (PROFILE_NAMES[i].equals(prof)) {
                idx = i;
            }
        }
        int[] v = PROFILES[idx];
        this.profile = on ? PROFILE_NAMES[idx] : "desligado";
        this.enabled = on && bool(p, "ativo", true);
        this.animMax = pow2(intOr(p, "anim.largura.max", v[0]));
        int frames = intOr(p, "anim.frames.max", v[1]);
        this.framesMax = frames <= 0 ? Integer.MAX_VALUE : frames;
        this.staticMax = pow2(intOr(p, "estatico.max", v[2]));
        this.modelMax = pow2(intOr(p, "modelo.max", v[3]));
        this.otherMax = pow2(intOr(p, "outros.max", v[4]));
        this.reduceAtlas = bool(p, "reduzir.atlas", true);
        this.onlyVanillaAtlas = bool(p, "atlas.somente.vanilla", true);
        this.reduceLargeTextures = bool(p, "reduzir.texturas.grandes", true);
        this.reduceReika = bool(p, "reduzir.reika", true);
        this.verbose = bool(p, "log.detalhado", false);
        String cacheProp = System.getProperty("akashic.leveza.cache");
        this.decodeCache = cacheProp != null ? Boolean.parseBoolean(cacheProp) : bool(p, "cache.decodificacao", false);
        this.decodeCacheMinKb = Math.max(1, intOr(p, "cache.minimo.kb", 32));
        this.ignoredDomains = list(p, "ignorar.dominios", "minecraft,akashiccinematic,distanthorizons");
        this.ignoredPrefixes = list(p, "ignorar.texturas", "");
    }

    static void setMinecraftHome(File home) {
        minecraftHome = home;
    }

    static File minecraftHomeOrNull() {
        File home = minecraftHome;
        if (home == null) {
            try {
                home = net.minecraft.launchwrapper.Launch.minecraftHome;
            } catch (Throwable ignored) {
                // fora do LaunchWrapper
            }
        }
        return home;
    }

    static LevezaConfig get() {
        LevezaConfig c = instance;
        if (c == null) {
            synchronized (LevezaConfig.class) {
                c = instance;
                if (c == null) {
                    c = load();
                    instance = c;
                }
            }
        }
        return c;
    }

    /** Só para testes. */
    static void reset(Properties p) {
        instance = p == null ? null : new LevezaConfig(p);
    }

    private static LevezaConfig load() {
        Properties p = new Properties();
        File file = configFile();
        if (file != null) {
            try {
                if (file.isFile()) {
                    InputStream in = new FileInputStream(file);
                    try {
                        p.load(new java.io.InputStreamReader(in, Charset.forName("UTF-8")));
                    } finally {
                        in.close();
                    }
                } else {
                    writeDefault(file);
                }
            } catch (Throwable t) {
                LevezaLog.error("Não consegui ler " + file + "; usando o perfil padrão (leve).", t);
            }
        }
        LevezaConfig c = new LevezaConfig(p);
        LevezaLog.info("Perfil " + c.profile + (c.enabled ? "" : " (desativado)")
                + ": anim<=" + c.animMax + "px, frames<=" + (c.framesMax == Integer.MAX_VALUE ? "todos" : c.framesMax)
                + ", estatico<=" + c.staticMax + "px, modelo<=" + c.modelMax + "px, outros<=" + c.otherMax + "px");
        return c;
    }

    private static File configFile() {
        File home = minecraftHome;
        if (home == null) {
            try {
                home = net.minecraft.launchwrapper.Launch.minecraftHome;
            } catch (Throwable ignored) {
                // fora do LaunchWrapper (testes)
            }
        }
        String override = System.getProperty("akashic.leveza.config");
        if (override != null) {
            return new File(override);
        }
        return home == null ? null : new File(new File(home, "config"), FILE_NAME);
    }

    private static void writeDefault(File file) throws Exception {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
        try {
            w.write(DEFAULT_FILE);
        } finally {
            w.close();
        }
    }

    private static final String DEFAULT_FILE = ""
            + "# Akashic Leveza - reduz RAM e VRAM das texturas no carregamento (nenhum arquivo e alterado).\n"
            + "#\n"
            + "# perfil: qualidade | equilibrado | leve | batata | desligado\n"
            + "#   qualidade   : anim 128px, todos os frames, icones 128px, modelos 1024px, outras 2048px\n"
            + "#   equilibrado : anim 64px,  todos os frames, icones 64px,  modelos 512px,  outras 2048px\n"
            + "#   leve        : anim 64px,  ate 64 frames,   icones 64px,  modelos 256px,  outras 1024px\n"
            + "#   batata      : anim 32px,  ate 32 frames,   icones 32px,  modelos 128px,  outras 512px\n"
            + "# Tambem pode ser trocado sem editar o arquivo: -Dakashic.leveza.perfil=batata\n"
            + "perfil=leve\n"
            + "\n"
            + "# Ajustes finos (apague a linha ou deixe vazio para usar o valor do perfil).\n"
            + "#   anim.largura.max  : largura maxima de cada frame de textura animada (blocos/itens)\n"
            + "#   anim.frames.max   : maximo de frames por animacao; o frametime e multiplicado para manter a duracao (0 = todos)\n"
            + "#   estatico.max      : lado maximo de icone estatico de bloco/item (ate 128px)\n"
            + "#   modelo.max        : lado maximo de textura >128px dentro do atlas (texturas de modelo 3D)\n"
            + "#   outros.max        : lado maximo de texturas avulsas (entidades, modelos, efeitos, Reika)\n"
            + "anim.largura.max=\n"
            + "anim.frames.max=\n"
            + "estatico.max=\n"
            + "modelo.max=\n"
            + "outros.max=\n"
            + "\n"
            + "# Liga/desliga cada parte.\n"
            + "reduzir.atlas=true\n"
            + "reduzir.texturas.grandes=true\n"
            + "reduzir.reika=true\n"
            + "# Reduz so os atlas de blocos e itens do jogo (textures/blocks, textures/items). Atlas proprios de\n"
            + "# mods (MatterOverdrive, Immersive Engineering, DragonAPI...) ficam intactos; a economia neles e minima.\n"
            + "atlas.somente.vanilla=true\n"
            + "\n"
            + "# Dominios (modid) e prefixos de textura que nunca sao reduzidos, separados por virgula.\n"
            + "# Exemplo de prefixo: chromaticraft:icons/portalring  ou  jurassicraft:textures/entity/\n"
            + "ignorar.dominios=minecraft,akashiccinematic,distanthorizons\n"
            + "ignorar.texturas=\n"
            + "\n"
            + "# EXPERIMENTAL - cache em disco (cache/akashic-leveza/) das texturas de atlas ja reduzidas: do 2o boot\n"
            + "# em diante o jogo le a versao pequena em vez de decodificar o PNG grande. No teste com 4 CPUs o ganho\n"
            + "# ficou dentro do ruido (o Angelica ja decodifica em paralelo); pode ajudar em CPU de 2 nucleos.\n"
            + "# Ligar sem editar: -Dakashic.leveza.cache=true\n"
            + "cache.decodificacao=false\n"
            + "# So entram no cache PNGs maiores que isto (KB).\n"
            + "cache.minimo.kb=32\n"
            + "\n"
            + "# Lista no log cada textura reduzida.\n"
            + "log.detalhado=false\n";

    private static int intOr(Properties p, String key, int def) {
        String s = p.getProperty(key);
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String s = p.getProperty(key);
        return s == null || s.trim().isEmpty() ? def : Boolean.parseBoolean(s.trim());
    }

    private static List<String> list(Properties p, String key, String def) {
        String s = p.getProperty(key);
        if (s == null) {
            s = def;
        }
        List<String> out = new ArrayList<String>();
        for (String part : s.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Arredonda para baixo até potência de 2, mínimo 1. */
    private static int pow2(int v) {
        if (v <= 0) {
            return Integer.MAX_VALUE;
        }
        return Integer.highestOneBit(v);
    }
}
