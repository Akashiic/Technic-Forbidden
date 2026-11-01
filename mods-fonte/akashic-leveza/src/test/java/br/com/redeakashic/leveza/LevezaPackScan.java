package br.com.redeakashic.leveza;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Passa TODAS as texturas de blocos/itens dos jars de uma pasta mods/ pelo TextureAtlasSprite.loadSprite
 * real (remapeado para SRG e transformado), uma vez sem o mod (perfil desligado) e uma vez com o perfil
 * pedido. Confere que nenhuma textura que carregava passou a falhar e soma os pixels que ficam no heap.
 *
 * Uso: LevezaPackScan minecraft.jar srg.txt pasta-mods [perfil]
 */
public final class LevezaPackScan {

    private static final Pattern ATLAS = Pattern.compile("^assets/([^/]+)/textures/(blocks|items)/(.+)\\.png$");
    private static final Pattern FRAMETIME = Pattern.compile("\"frametime\"\\s*:\\s*(\\d+)");

    public static void main(String[] args) throws Exception {
        String profile = args.length > 3 ? args[3] : "leve";
        ClassLoader mc = LevezaSelfTest.gameLoader(args[0], args[1]);
        Class<?> spriteClass = mc.loadClass("net.minecraft.client.renderer.texture.TextureAtlasSprite");
        Class<?> metaClass = mc.loadClass("net.minecraft.client.resources.data.AnimationMetadataSection");
        Constructor<?> spriteCtor = spriteClass.getDeclaredConstructor(String.class);
        spriteCtor.setAccessible(true);
        Constructor<?> metaCtor = metaClass.getDeclaredConstructor(List.class, int.class, int.class, int.class);
        metaCtor.setAccessible(true);
        Method load = spriteClass.getDeclaredMethod("func_147964_a", BufferedImage[].class, metaClass, boolean.class);
        load.setAccessible(true);
        Field framesField = field(spriteClass, "field_110976_a");
        mipLevels = Integer.getInteger("leveza.mip", 4);
        widthField = field(spriteClass, "field_130223_c");

        long[] off = new long[4];
        long[] on = new long[4];
        int total = 0;
        int newFailures = 0;
        Set<String> seen = new HashSet<String>();
        File[] jars = listJars(new File(args[2]));
        for (File jar : jars) {
            ZipFile z;
            try {
                z = new ZipFile(jar);
            } catch (Exception e) {
                continue;
            }
            Map<String, ZipEntry> entries = new HashMap<String, ZipEntry>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                entries.put(e.getName(), e);
            }
            for (ZipEntry e : entries.values()) {
                Matcher m = ATLAS.matcher(e.getName());
                if (!m.matches() || !seen.add(e.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String name = m.group(1) + ":" + m.group(3);
                BufferedImage img;
                try {
                    long d0 = System.nanoTime();
                    img = ImageIO.read(z.getInputStream(e));
                    decodeNanos += System.nanoTime() - d0;
                } catch (Exception ex) {
                    continue;
                }
                if (img == null) {
                    continue;
                }
                Object meta = null;
                ZipEntry mcmeta = entries.get(e.getName() + ".mcmeta");
                if (mcmeta != null) {
                    String json = new String(LevezaSelfTest.readAll(z.getInputStream(mcmeta)), "UTF-8");
                    if (json.contains("\"animation\"")) {
                        if (json.contains("\"frames\"")) {
                            continue; // lista explícita: o parser real é necessário; o caso é coberto no SelfTest
                        }
                        Matcher ft = FRAMETIME.matcher(json);
                        meta = metaCtor.newInstance(new ArrayList<Object>(), -1, -1, ft.find() ? Integer.parseInt(ft.group(1)) : 1);
                    }
                }
                total++;
                LevezaHooks.enterTextureMap(new LevezaSelfTest.FakeTextureMap("textures/" + m.group(2)));
                String only = System.getProperty("leveza.only");
                boolean okOff = "on".equals(only) || run(profile("desligado"), spriteCtor, load, framesField, name, img, meta, off);
                boolean okOn = "off".equals(only) || run(profile(profile), spriteCtor, load, framesField, name, img, meta, on);
                if (okOff && !okOn) {
                    newFailures++;
                    System.out.println("  NOVA FALHA: " + name);
                }
            }
            z.close();
        }
        System.out.printf(Locale.ROOT, "Texturas testadas: %d | falhas novas causadas pelo mod: %d%n", total, newFailures);
        System.out.printf(Locale.ROOT, "Que já falhavam sem o mod (vanilla): %d%n", off[2]);
        System.out.printf(Locale.ROOT, "Pixels no heap após loadSprite (sem mipmaps): %.0f MiB -> %.0f MiB (perfil %s)%n",
                off[0] * 4 / 1048576.0, on[0] * 4 / 1048576.0, profile);
        System.out.printf(Locale.ROOT, "  dos quais animações (residentes a sessão toda): %.0f MiB -> %.0f MiB%n",
                off[1] * 4 / 1048576.0, on[1] * 4 / 1048576.0);
        System.out.printf(Locale.ROOT, "Decodificação dos PNGs (igual com e sem o mod): %.1f s%n", decodeNanos / 1e9);
        System.out.printf(Locale.ROOT, "loadSprite + redução + mipmaps nível %d (1 thread): %.1f s -> %.1f s%n",
                mipLevels, off[3] / 1e9, on[3] / 1e9);
        System.exit(newFailures == 0 ? 0 : 1);
    }

    private static final List<Object> RETAINED = new ArrayList<Object>();
    private static long decodeNanos;
    private static int mipLevels;
    private static Field widthField;
    static long sink;

    private static void mip(int[] px, int w, int levels) {
        int[] cur = px;
        for (int l = 0; l < levels && w > 1; l++) {
            int nw = w / 2;
            int h = cur.length / w;
            int nh = Math.max(1, h / 2);
            int[] next = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    int a = cur[(2 * y) * w + 2 * x], b = cur[(2 * y) * w + 2 * x + 1];
                    int c = cur[(2 * y + 1) * w + 2 * x], d = cur[(2 * y + 1) * w + 2 * x + 1];
                    int r = 0;
                    for (int sh = 0; sh < 32; sh += 8) {
                        r |= ((((a >>> sh) & 255) + ((b >>> sh) & 255) + ((c >>> sh) & 255) + ((d >>> sh) & 255)) >> 2) << sh;
                    }
                    next[y * nw + x] = r;
                }
            }
            sink += next.length;
            cur = next;
            w = nw;
        }
    }

    private static Properties profile(String name) {
        Properties p = new Properties();
        p.setProperty("perfil", name);
        return p;
    }

    private static boolean run(Properties cfg, Constructor<?> ctor, Method load, Field framesField, String name,
                               BufferedImage img, Object meta, long[] acc) throws Exception {
        LevezaConfig.reset(cfg);
        Object sprite = ctor.newInstance(name);
        long t0 = System.nanoTime();
        try {
            load.invoke(sprite, new BufferedImage[] {img}, meta, false);
            // o TextureUtil real exige contexto OpenGL; geração equivalente: média 2x2 por nível, por frame
            int w = widthField.getInt(sprite);
            for (Object frame : (List<?>) framesField.get(sprite)) {
                mip(((int[][]) frame)[0], w, mipLevels);
            }
        } catch (InvocationTargetException ex) {
            if (acc[2]++ == 0 && Boolean.getBoolean("leveza.debug")) {
                ex.getCause().printStackTrace();
            }
            return false;
        } finally {
            acc[3] += System.nanoTime() - t0;
        }
        if (Boolean.getBoolean("leveza.retain")) {
            RETAINED.add(sprite); // no jogo todos os sprites ficam vivos até o atlas ser montado
        }
        long px = 0;
        for (Object frame : (List<?>) framesField.get(sprite)) {
            px += ((int[][]) frame)[0].length;
        }
        acc[0] += px;
        if (meta != null) {
            acc[1] += px;
        }
        return true;
    }

    private static File[] listJars(File dir) {
        List<File> out = new ArrayList<File>();
        collect(dir, out);
        return out.toArray(new File[0]);
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".jar")) {
                out.add(f);
            }
        }
    }

    private static Field field(Class<?> c, String n) throws Exception {
        Field f = c.getDeclaredField(n);
        f.setAccessible(true);
        return f;
    }
}
