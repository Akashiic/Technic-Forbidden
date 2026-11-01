package br.com.redeakashic.leveza;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.util.CheckClassAdapter;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Teste offline contra o bytecode REAL do jogo:
 * remapeia o minecraft.jar (obf) para nomes SRG com o deobfuscation_data do FML (já extraído em texto),
 * aplica o LevezaTransformer, verifica o bytecode e executa TextureAtlasSprite.loadSprite.
 *
 * Uso: LevezaSelfTest minecraft.jar srg.txt [DragonAPI.jar]
 */
public final class LevezaSelfTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        String mcJar = args[0];
        String srg = args[1];
        String dragon = args.length > 2 ? args[2] : null;

        ClassLoader mc = gameLoader(mcJar, srg);
        final LevezaTransformer tx = new LevezaTransformer();
        for (Map.Entry<String, byte[]> e : lastOriginal.entrySet()) {
            check(transformed.get(e.getKey()) != e.getValue(), e.getKey() + " foi transformada");
        }

        for (Map.Entry<String, byte[]> e : transformed.entrySet()) {
            verify(e.getKey(), e.getValue(), mc);
        }
        if (dragon != null) {
            byte[] reika = readEntry(dragon, "Reika/DragonAPI/IO/ReikaTextureBinder.class");
            byte[] after = tx.transform("Reika.DragonAPI.IO.ReikaTextureBinder", "Reika.DragonAPI.IO.ReikaTextureBinder", reika);
            check(after != reika, "ReikaTextureBinder foi transformada");
            verify("Reika.DragonAPI.IO.ReikaTextureBinder", after, mc);
        }

        // o EventBus do Forge chama o handler a partir de uma classe gerada: tudo precisa ser public
        Class<?> ev = ClientEvents.class;
        Method handler = ev.getMethod("onStitchPost", net.minecraftforge.client.event.TextureStitchEvent.Post.class);
        check(java.lang.reflect.Modifier.isPublic(ev.getModifiers())
                && java.lang.reflect.Modifier.isPublic(ev.getConstructor().getModifiers())
                && java.lang.reflect.Modifier.isPublic(handler.getModifiers()),
                "handler de evento público (classe, construtor e método)");

        Properties p = new Properties();
        p.setProperty("perfil", "leve");
        LevezaConfig.reset(p);

        Class<?> spriteClass = mc.loadClass("net.minecraft.client.renderer.texture.TextureAtlasSprite");
        Class<?> metaClass = mc.loadClass("net.minecraft.client.resources.data.AnimationMetadataSection");
        Constructor<?> spriteCtor = spriteClass.getDeclaredConstructor(String.class);
        spriteCtor.setAccessible(true);
        Constructor<?> metaCtor = metaClass.getDeclaredConstructor(List.class, int.class, int.class, int.class);
        metaCtor.setAccessible(true);
        Method load = spriteClass.getDeclaredMethod("func_147964_a", BufferedImage[].class, metaClass, boolean.class);
        load.setAccessible(true);

        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/blocks"));

        // 1) animação estilo roundflare: 256x(256*180), frametime 1 -> 64px, 1 a cada 3 frames, frametime 3
        Object sprite = spriteCtor.newInstance("chromaticraft:icons/roundflare");
        BufferedImage anim = strip(256, 180);
        load.invoke(sprite, new BufferedImage[] {anim, null, null, null, null},
                metaCtor.newInstance(new ArrayList<Object>(), -1, -1, 1), false);
        List<?> frames = (List<?>) field(spriteClass, "field_110976_a").get(sprite);
        int width = field(spriteClass, "field_130223_c").getInt(sprite);
        int height = field(spriteClass, "field_130224_d").getInt(sprite);
        Object newMeta = field(spriteClass, "field_110982_k").get(sprite);
        int frameTime = field(metaClass, "field_110475_d").getInt(newMeta);
        int metaFrames = ((List<?>) field(metaClass, "field_110478_a").get(newMeta)).size();
        check(width == 64 && height == 64, "animação: frame 64x64 (veio " + width + "x" + height + ")");
        check(frames.size() == 60, "animação: 60 frames (veio " + frames.size() + ")");
        check(metaFrames == 60, "animação: metadata com 60 frames (veio " + metaFrames + ")");
        check(frameTime == 3, "animação: frametime 3 (veio " + frameTime + ")");
        int[][] f10 = (int[][]) frames.get(10);
        check(f10[0].length == 64 * 64, "animação: frame com 64*64 pixels");
        check(f10[0][0] == color(30), "animação: frame 10 = frame original 30 (cor preservada)");
        long before = 256L * 256 * 180;
        long after = 0;
        for (Object o : frames) {
            after += ((int[][]) o)[0].length;
        }
        System.out.printf("   heap da animação: %.1f MiB -> %.2f MiB%n", before * 4 / 1048576.0, after * 4 / 1048576.0);

        // 2) animação com lista explícita de frames: só reduz resolução, nunca corta frames
        Object s2 = spriteCtor.newInstance("thaumcraft:items/lightningring");
        List<Object> explicit = new ArrayList<Object>();
        Class<?> frameClass = mc.loadClass("net.minecraft.client.resources.data.AnimationFrame");
        Constructor<?> frameCtor = frameClass.getDeclaredConstructor(int.class, int.class);
        frameCtor.setAccessible(true);
        for (int i = 79; i >= 0; i--) {
            explicit.add(frameCtor.newInstance(i, -1));
        }
        load.invoke(s2, new BufferedImage[] {strip(256, 80)}, metaCtor.newInstance(explicit, -1, -1, 2), false);
        check(((List<?>) field(spriteClass, "field_110976_a").get(s2)).size() == 80, "frames explícitos: 80 mantidos");
        check(field(spriteClass, "field_130223_c").getInt(s2) == 64, "frames explícitos: 64px");

        // 3) ícone estático 64 -> 32, textura de modelo 1024 -> 256, ícone 16 intacto, domínio ignorado intacto
        check(staticResult(spriteCtor, load, "industrialupgrade:blocks/x", 128) == 64, "ícone 128 -> 64");
        check(staticResult(spriteCtor, load, "industrialupgrade:blocks/y", 64) == 64, "ícone 64 intacto no leve");
        check(staticResult(spriteCtor, load, "mo:items/ion_sniper", 1024) == 256, "modelo 1024 -> 256");
        check(staticResult(spriteCtor, load, "chisel:blocks/y", 16) == 16, "ícone 16 intacto");
        check(staticResult(spriteCtor, load, "minecraft:blocks/stone", 64) == 64, "domínio minecraft intacto");
        check(staticResult(spriteCtor, load, "stone_no_domain", 64) == 64, "sem domínio = minecraft, intacto");

        // 3b) atlas próprio de mod (ex.: MatterOverdrive HoloIcons) fica intacto por padrão
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/gui/holo"));
        check(staticResult(spriteCtor, load, "mo:holo_icon", 128) == 128, "atlas de mod intacto (atlas.somente.vanilla)");
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/items"));
        check(staticResult(spriteCtor, load, "mo:items/ion_sniper", 1024) == 256, "atlas de itens reduzido");
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/blocks"));

        // 4) uploads avulsos: só reduz dentro de loadTexture de SimpleTexture
        BufferedImage big = solid(4096, 2048, 0xFF336699);
        LevezaHooks.enterSimpleTexture(new Object());
        BufferedImage r = loadTexture(big);
        check(r.getWidth() == 1024 && r.getHeight() == 512, "upload 4096x2048 -> 1024x512 dentro de loadTexture");
        check(r.getRGB(5, 5) == 0xFF336699, "upload: cor preservada");
        check(LevezaHooks.preUploadImage(big) == big, "upload sem SimpleTexture: intacto");
        LevezaHooks.enterSimpleTexture(new Object());
        check(notLoadTexture(big) == big, "upload fora de loadTexture: intacto");
        check(LevezaHooks.preUploadReika(big).getWidth() == 1024, "Reika 4096 -> 1024");
        check(LevezaHooks.preUploadReika(solid(512, 512, 0)).getWidth() == 512, "Reika 512 intacto");

        // 5) perfil batata: CTM 64 -> 32 (sub-bloco 8px aceito), mas 48 (grade 3x3) nunca vira 24
        Properties batata = new Properties();
        batata.setProperty("perfil", "batata");
        LevezaConfig.reset(batata);
        check(staticResult(spriteCtor, load, "chisel:blocks/antiblock-ctm", 64) == 32, "batata: 64 -> 32");
        check(staticResult(spriteCtor, load, "chisel:blocks/arcaneMatrix-v9", 48) == 48, "batata: 48 intacto (24 não é múltiplo de 16)");
        check(staticResult(spriteCtor, load, "chisel:blocks/paver-ctm", 32) == 32, "batata: 32 intacto");

        // 6) perfil desligado: nada muda
        Properties off = new Properties();
        off.setProperty("perfil", "desligado");
        LevezaConfig.reset(off);
        check(staticResult(spriteCtor, load, "mo:items/ion_sniper", 1024) == 1024, "perfil desligado: intacto");

        LevezaHooks.logSummary();

        // 7) cache de decodificação com o SimpleResource real, em três "boots"
        cacheTests(mc, spriteCtor, load, metaCtor, spriteClass, metaClass);

        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Map<String, byte[]> transformed = new HashMap<String, byte[]>();
    static Map<String, byte[]> lastOriginal = new HashMap<String, byte[]>();

    /** ClassLoader com o minecraft.jar remapeado para SRG e as classes-alvo já transformadas. */
    static ClassLoader gameLoader(String mcJar, String srg) throws Exception {
        final Map<String, byte[]> classes = remap(mcJar, srg);
        System.out.println("Classes remapeadas: " + classes.size());
        LevezaTransformer tx = new LevezaTransformer();
        final Map<String, byte[]> tr = new HashMap<String, byte[]>();
        for (String n : new String[] {"net.minecraft.client.renderer.texture.TextureAtlasSprite",
                "net.minecraft.client.renderer.texture.TextureUtil",
                "net.minecraft.client.renderer.texture.SimpleTexture",
                "net.minecraft.client.resources.SimpleResource",
                "net.minecraft.client.renderer.texture.TextureMap"}) {
            lastOriginal.put(n, classes.get(n));
            tr.put(n, tx.transform(n, n, classes.get(n)));
        }
        transformed = tr;
        final ClassLoader parent = LevezaSelfTest.class.getClassLoader();
        return new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        byte[] b = tr.get(name);
                        if (b == null) {
                            b = classes.get(name);
                        }
                        c = b != null ? defineClass(name, b, 0, b.length) : parent.loadClass(name);
                    }
                    return c;
                }
            }
        };
    }

    /** Objeto com o mesmo campo que o TextureMap usa para o basePath. */
    static final class FakeTextureMap {
        final String basePath;

        FakeTextureMap(String basePath) {
            this.basePath = basePath;
        }
    }

    private static void cacheTests(ClassLoader mc, Constructor<?> spriteCtor, Method load, Constructor<?> metaCtor,
                                   Class<?> spriteClass, Class<?> metaClass) throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("leveza-cache").toFile();
        System.setProperty("akashic.leveza.cacheDir", dir.getAbsolutePath());
        Properties p = new Properties();
        p.setProperty("perfil", "leve");
        p.setProperty("cache.decodificacao", "true");
        LevezaConfig.reset(p);
        LevezaCache.resetForTests();

        Class<?> rlClass = mc.loadClass("net.minecraft.util.ResourceLocation");
        Class<?> resClass = mc.loadClass("net.minecraft.client.resources.SimpleResource");
        Constructor<?> rlCtor = rlClass.getConstructor(String.class);
        Constructor<?> resCtor = resClass.getDeclaredConstructors()[0];
        resCtor.setAccessible(true);
        Method getStream = resClass.getDeclaredMethod("func_110527_b");
        Object loc = rlCtor.newInstance("ChromatiCraft:textures/blocks/icons/roundflare.png");
        byte[] png = noisyStripPng(256, 180, 1);

        // boot 1: miss, reduz e grava
        BufferedImage[] r1 = bootOnce(load, spriteCtor, metaCtor, resCtor, getStream, loc, png, 1);
        LevezaCache.resetForTests(); // espera a gravação e grava o índice
        java.io.File[] files = new java.io.File(dir, dir.list()[0]).listFiles();
        check(files != null && files.length == 2, "cache: boot 1 gravou PNG reduzido + índice");
        check(r1[0].getWidth() == 64 && r1[0].getHeight() == 64 * 60, "cache: boot 1 reduziu normalmente");

        // boot 2: acerto, já chega pequeno, mesmo resultado
        Object[] out2 = new Object[1];
        BufferedImage decoded2 = decodeVia(resCtor, getStream, loc, png);
        check(decoded2.getWidth() == 64 && decoded2.getHeight() == 64 * 60, "cache: boot 2 entrega o PNG já reduzido ao decodificador");
        Object s2 = runSprite(load, spriteCtor, metaCtor, decoded2, 1, out2);
        checkSprite(spriteClass, metaClass, s2, 64, 60, 3, "cache: boot 2 (acerto)");

        // PNG diferente no mesmo caminho (outro resource pack): erro de cache, reduz do zero
        byte[] other = noisyStripPng(256, 180, 2);
        BufferedImage decoded3 = decodeVia(resCtor, getStream, loc, other);
        check(decoded3.getWidth() == 256, "cache: PNG diferente não usa o cache");
        Object s3 = runSprite(load, spriteCtor, metaCtor, decoded3, 1, new Object[1]);
        checkSprite(spriteClass, metaClass, s3, 64, 60, 3, "cache: PNG diferente reduzido normalmente");

        // mesmo PNG, mcmeta com outro frametime: volta para a original e recalcula
        BufferedImage decoded4 = decodeVia(resCtor, getStream, loc, png);
        Object s4 = runSprite(load, spriteCtor, metaCtor, decoded4, 2, new Object[1]);
        checkSprite(spriteClass, metaClass, s4, 64, 60, 6, "cache: mcmeta alterado cai para a original");
        LevezaCache.resetForTests();
        System.clearProperty("akashic.leveza.cacheDir");
    }

    private static BufferedImage[] bootOnce(Method load, Constructor<?> spriteCtor, Constructor<?> metaCtor,
                                            Constructor<?> resCtor, Method getStream, Object loc, byte[] png,
                                            int frameTime) throws Exception {
        BufferedImage img = decodeVia(resCtor, getStream, loc, png);
        BufferedImage[] arr = new BufferedImage[] {img};
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/blocks"));
        Object sprite = spriteCtor.newInstance("chromaticraft:icons/roundflare");
        load.invoke(sprite, arr, metaCtor.newInstance(new ArrayList<Object>(), -1, -1, frameTime), false);
        LevezaHooks.logSummary();
        return arr;
    }

    private static BufferedImage decodeVia(Constructor<?> resCtor, Method getStream, Object loc, byte[] png) throws Exception {
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/blocks"));
        Object res = resCtor.newInstance(loc, new java.io.ByteArrayInputStream(png), null, null);
        java.io.InputStream in = (java.io.InputStream) getStream.invoke(res);
        return javax.imageio.ImageIO.read(in);
    }

    private static Object runSprite(Method load, Constructor<?> spriteCtor, Constructor<?> metaCtor, BufferedImage img,
                                    int frameTime, Object[] unused) throws Exception {
        LevezaHooks.enterTextureMap(new FakeTextureMap("textures/blocks"));
        Object sprite = spriteCtor.newInstance("chromaticraft:icons/roundflare");
        load.invoke(sprite, new BufferedImage[] {img}, metaCtor.newInstance(new ArrayList<Object>(), -1, -1, frameTime), false);
        LevezaHooks.logSummary();
        return sprite;
    }

    private static void checkSprite(Class<?> spriteClass, Class<?> metaClass, Object sprite, int w, int frames,
                                    int frameTime, String what) throws Exception {
        int width = field(spriteClass, "field_130223_c").getInt(sprite);
        int n = ((List<?>) field(spriteClass, "field_110976_a").get(sprite)).size();
        Object meta = field(spriteClass, "field_110982_k").get(sprite);
        int ft = field(metaClass, "field_110475_d").getInt(meta);
        check(width == w && n == frames && ft == frameTime,
                what + " (frame " + width + "px, " + n + " frames, frametime " + ft + ")");
    }

    private static byte[] noisyStripPng(int w, int frames, long seed) throws Exception {
        BufferedImage img = new BufferedImage(w, w * frames, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        int[] row = new int[w * w];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < row.length; i++) {
                row[i] = 0xFF000000 | rnd.nextInt(0x1000000);
            }
            img.setRGB(0, f * w, w, w, row, 0, w);
        }
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    private static BufferedImage loadTexture(BufferedImage img) {
        return LevezaHooks.preUploadImage(img);
    }

    private static BufferedImage notLoadTexture(BufferedImage img) {
        return LevezaHooks.preUploadImage(img);
    }

    private static int staticResult(Constructor<?> ctor, Method load, String name, int side) throws Exception {
        Object s = ctor.newInstance(name);
        load.invoke(s, new BufferedImage[] {solid(side, side, 0xFF00FF00)}, null, false);
        return field(s.getClass(), "field_130223_c").getInt(s);
    }

    private static int color(int frame) {
        return 0xFF000000 | (frame * 1103515245 & 0xFFFFFF);
    }

    private static BufferedImage strip(int w, int frames) {
        BufferedImage img = new BufferedImage(w, w * frames, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w * w];
        for (int f = 0; f < frames; f++) {
            java.util.Arrays.fill(row, color(f));
            img.setRGB(0, f * w, w, w, row, 0, w);
        }
        return img;
    }

    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, argb);
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FALHA ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void verify(String name, byte[] bytes, ClassLoader loader) {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(sw));
        String out = sw.toString().trim();
        check(out.isEmpty(), "bytecode verificado: " + name + (out.isEmpty() ? "" : "\n" + out));
    }

    private static Map<String, byte[]> remap(String mcJar, String srgFile) throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        BufferedReader br = new BufferedReader(new FileReader(srgFile));
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(" ");
            if (line.startsWith("CL: ")) {
                map.put(p[1], p[2]);
            } else if (line.startsWith("FD: ")) {
                int i = p[1].lastIndexOf('/');
                map.put(p[1].substring(0, i) + "." + p[1].substring(i + 1), p[2].substring(p[2].lastIndexOf('/') + 1));
            } else if (line.startsWith("MD: ")) {
                int i = p[1].lastIndexOf('/');
                map.put(p[1].substring(0, i) + "." + p[1].substring(i + 1) + p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
            }
        }
        br.close();
        SimpleRemapper remapper = new SimpleRemapper(map);
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        ZipFile zip = new ZipFile(mcJar);
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (!e.getName().endsWith(".class")) {
                continue;
            }
            byte[] b = readAll(zip.getInputStream(e));
            ClassReader cr = new ClassReader(b);
            ClassWriter cw = new ClassWriter(0);
            cr.accept(new RemappingClassAdapter(cw, remapper), ClassReader.EXPAND_FRAMES);
            String obf = e.getName().substring(0, e.getName().length() - 6);
            String deobf = map.containsKey(obf) ? map.get(obf) : obf;
            out.put(deobf.replace('/', '.'), cw.toByteArray());
        }
        zip.close();
        return out;
    }

    private static byte[] readEntry(String jar, String entry) throws Exception {
        ZipFile z = new ZipFile(jar);
        try {
            return readAll(z.getInputStream(z.getEntry(entry)));
        } finally {
            z.close();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }
}
