package br.com.redeakashic.leveza;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache em disco das texturas de atlas já reduzidas, para o boot pular a decodificação do PNG grande.
 *
 * <p>1º boot: ao ler um PNG de textures/blocks|items maior que o mínimo, guarda o SHA-1 dos bytes
 * originais. Se o loadSprite reduzir essa textura, a versão reduzida é gravada em disco (thread própria).
 *
 * <p>Boots seguintes: se o SHA-1 do original bate com o do índice, o stream do recurso passa a ser o PNG
 * pequeno; o decodificador (vanilla ou paralelo do Angelica) já recebe a imagem reduzida e o loadSprite só
 * reaplica o frametime. Qualquer divergência (outro resource pack, mcmeta diferente, textura passou a ser
 * ignorada, dimensões inesperadas) faz o loadSprite decodificar de novo a partir dos bytes originais.
 */
final class LevezaCache {

    private static final int FORMAT = 1;

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static File dir;
    private static File indexFile;
    private static int minBytes;

    /** índice persistido: location -> entrada */
    private static final Map<String, Entry> INDEX = new ConcurrentHashMap<String, Entry>();
    /** texturas servidas do cache neste carregamento, aguardando o loadSprite */
    private static final Map<String, Hit> HITS = new ConcurrentHashMap<String, Hit>();
    /** texturas grandes lidas sem cache neste carregamento (candidatas a gravar) */
    private static final Map<String, Seen> SEEN = new ConcurrentHashMap<String, Seen>();

    static final AtomicInteger hits = new AtomicInteger();
    static final AtomicInteger misses = new AtomicInteger();
    static final AtomicInteger written = new AtomicInteger();
    static final AtomicLong bytesSkipped = new AtomicLong();

    private static volatile boolean dirty;
    private static ExecutorService writer;

    private static Field resourceLocationField;
    private static Field resourceStreamField;

    private LevezaCache() {
    }

    static final class Entry {
        final String sha;
        final int len;
        final int w;
        final int h;
        final int step;
        final int frameTime; // frametime original (-1 = estático)
        final String file;

        Entry(String sha, int len, int w, int h, int step, int frameTime, String file) {
            this.sha = sha;
            this.len = len;
            this.w = w;
            this.h = h;
            this.step = step;
            this.frameTime = frameTime;
            this.file = file;
        }

        String encode() {
            return sha + ";" + len + ";" + w + ";" + h + ";" + step + ";" + frameTime + ";" + file;
        }

        static Entry decode(String s) {
            String[] p = s.split(";");
            return new Entry(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), p[6]);
        }
    }

    static final class Hit {
        final Entry entry;
        final byte[] original;

        Hit(Entry entry, byte[] original) {
            this.entry = entry;
            this.original = original;
        }
    }

    static final class Seen {
        final String sha;
        final int len;

        Seen(String sha, int len) {
            this.sha = sha;
            this.len = len;
        }
    }

    /** Stream marcado, para não processar o mesmo recurso duas vezes. */
    static final class MarkedStream extends ByteArrayInputStream {
        MarkedStream(byte[] b) {
            super(b);
        }
    }

    // ------------------------------------------------------------------ inicialização

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            LevezaConfig cfg = LevezaConfig.get();
            enabled = cfg.enabled && cfg.reduceAtlas && cfg.decodeCache;
            minBytes = cfg.decodeCacheMinKb * 1024;
            File home = LevezaConfig.minecraftHomeOrNull();
            String override = System.getProperty("akashic.leveza.cacheDir");
            if (enabled && (home != null || override != null)) {
                File base = override != null ? new File(override) : new File(new File(home, "cache"), "akashic-leveza");
                dir = new File(base, fingerprint(cfg));
                indexFile = new File(dir, "indice.properties");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    enabled = false;
                } else {
                    loadIndex();
                    cleanOtherProfiles(base, dir.getName());
                }
            } else {
                enabled = false;
            }
            if (enabled) {
                writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "AkashicLeveza-Cache");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    }
                });
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        shutdown();
                    }
                }, "AkashicLeveza-CacheFlush"));
                LevezaLog.info("Cache de decodificação em " + dir + " (" + INDEX.size() + " texturas)");
            }
        } catch (Throwable t) {
            enabled = false;
            LevezaLog.error("Cache de decodificação desativado por erro na inicialização.", t);
        }
        initialized = true;
    }

    private static String fingerprint(LevezaConfig cfg) throws Exception {
        String s = FORMAT + "|" + LevezaMod.VERSION + "|" + cfg.animMax + "|" + cfg.framesMax + "|"
                + cfg.staticMax + "|" + cfg.modelMax + "|" + cfg.ignoredDomains + "|" + cfg.ignoredPrefixes;
        return cfg.profile + "-" + hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"))).substring(0, 12);
    }

    private static void loadIndex() {
        if (!indexFile.isFile()) {
            return;
        }
        Properties p = new Properties();
        try {
            InputStream in = new FileInputStream(indexFile);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            for (String k : p.stringPropertyNames()) {
                try {
                    Entry e = Entry.decode(p.getProperty(k));
                    if (new File(dir, e.file).isFile()) {
                        INDEX.put(k, e);
                    }
                } catch (RuntimeException ignored) {
                    // linha corrompida: descartada
                }
            }
        } catch (Throwable t) {
            INDEX.clear();
            LevezaLog.error("Índice do cache ilegível; será recriado.", t);
        }
    }

    /** Remove caches de outros perfis/versões (só pastas criadas pelo próprio mod). */
    private static void cleanOtherProfiles(File base, String keep) {
        File[] others = base.listFiles();
        if (others == null) {
            return;
        }
        for (File d : others) {
            if (d.isDirectory() && !d.getName().equals(keep) && new File(d, "indice.properties").exists()) {
                File[] fs = d.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (f.getName().endsWith(".png") || f.getName().endsWith(".properties") || f.getName().endsWith(".tmp")) {
                            f.delete();
                        }
                    }
                }
                d.delete();
            }
        }
    }

    // ------------------------------------------------------------------ gancho do SimpleResource

    /** Início de SimpleResource.getInputStream. */
    static void onGetInputStream(Object resource) {
        if (!initialized) {
            init();
        }
        if (!enabled || resource == null) {
            return;
        }
        try {
            if (resourceStreamField == null) {
                resourceLocationField = findField(resource.getClass(), "field_110533_b", "srResourceLocation");
                resourceStreamField = findField(resource.getClass(), "field_110534_c", "resourceInputStream");
            }
            Object stream = resourceStreamField.get(resource);
            if (!(stream instanceof InputStream) || stream instanceof MarkedStream) {
                return;
            }
            Object locObj = resourceLocationField.get(resource);
            if (locObj == null) {
                return;
            }
            String loc = locObj.toString();
            if (!isAtlasTexture(loc)) {
                return;
            }
            InputStream in = (InputStream) stream;
            byte[] bytes = readUpTo(in, minBytes + 1);
            if (bytes.length > minBytes) {
                bytes = concat(bytes, readAll(in));
            }
            in.close();
            if (bytes.length <= minBytes) {
                resourceStreamField.set(resource, new MarkedStream(bytes));
                return;
            }
            String sha = hex(MessageDigest.getInstance("SHA-1").digest(bytes));
            Entry e = INDEX.get(loc);
            if (e != null && e.sha.equals(sha) && e.len == bytes.length) {
                File f = new File(dir, e.file);
                byte[] small = f.isFile() ? readAll(new FileInputStream(f)) : null;
                if (small != null) {
                    HITS.put(loc, new Hit(e, bytes));
                    resourceStreamField.set(resource, new MarkedStream(small));
                    hits.incrementAndGet();
                    bytesSkipped.addAndGet(bytes.length - small.length);
                    return;
                }
            }
            SEEN.put(loc, new Seen(sha, bytes.length));
            misses.incrementAndGet();
            resourceStreamField.set(resource, new MarkedStream(bytes));
        } catch (Throwable t) {
            enabled = false;
            LevezaLog.error("Cache de decodificação desativado nesta sessão (erro ao ler recurso).", t);
        }
    }

    private static boolean isAtlasTexture(String loc) {
        int c = loc.indexOf(':');
        String path = (c < 0 ? loc : loc.substring(c + 1)).toLowerCase(Locale.ROOT);
        return path.endsWith(".png") && (path.startsWith("textures/blocks/") || path.startsWith("textures/items/"));
    }

    // ------------------------------------------------------------------ chamado pelo loadSprite

    static boolean active() {
        return initialized && enabled;
    }

    static Hit takeHit(String loc) {
        return loc == null ? null : HITS.remove(loc);
    }

    static Seen takeSeen(String loc) {
        return loc == null ? null : SEEN.remove(loc);
    }

    static BufferedImage decodeOriginal(Hit hit) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(hit.original));
    }

    /** Agenda a gravação da textura reduzida (não bloqueia o boot). */
    static void store(final String loc, final Seen seen, final BufferedImage reduced, final int step, final int frameTime) {
        if (!enabled || writer == null || seen == null) {
            return;
        }
        writer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String file = seen.sha + "-" + reduced.getWidth() + "x" + reduced.getHeight() + ".png";
                    File out = new File(dir, file);
                    if (!out.isFile()) {
                        File tmp = new File(dir, file + ".tmp");
                        if (!ImageIO.write(reduced, "png", tmp)) {
                            tmp.delete();
                            return;
                        }
                        if (!tmp.renameTo(out)) {
                            tmp.delete();
                            return;
                        }
                    }
                    INDEX.put(loc, new Entry(seen.sha, seen.len, reduced.getWidth(), reduced.getHeight(), step, frameTime, file));
                    written.incrementAndGet();
                    dirty = true;
                } catch (Throwable t) {
                    LevezaLog.error("Falha ao gravar " + loc + " no cache (ignorado).", t);
                }
            }
        });
    }

    /** Fim de um stitch: descarta pendências e grava o índice assim que a fila esvaziar. */
    static void endOfStitch() {
        HITS.clear();
        SEEN.clear();
        if (writer != null) {
            writer.execute(new Runnable() {
                @Override
                public void run() {
                    flushIndex();
                }
            });
        }
    }

    private static void shutdown() {
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        flushIndex();
    }

    private static synchronized void flushIndex() {
        if (!dirty || indexFile == null) {
            return;
        }
        try {
            Properties p = new Properties();
            for (Map.Entry<String, Entry> e : INDEX.entrySet()) {
                p.setProperty(e.getKey(), e.getValue().encode());
            }
            File tmp = new File(dir, "indice.properties.tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                p.store(out, "Akashic Leveza - cache de texturas reduzidas (pode apagar a pasta a qualquer momento)");
            } finally {
                out.close();
            }
            if (indexFile.exists() && !indexFile.delete()) {
                tmp.delete();
                return;
            }
            if (tmp.renameTo(indexFile)) {
                dirty = false;
            }
        } catch (Throwable t) {
            LevezaLog.error("Falha ao gravar o índice do cache (ignorado).", t);
        }
    }

    static String summary() {
        if (!active()) {
            return "cache desligado";
        }
        return String.format(Locale.ROOT, "cache: %d acertos (%.1f MiB de PNG não decodificados), %d novas, %d gravadas",
                hits.getAndSet(0), bytesSkipped.getAndSet(0) / 1048576.0, misses.getAndSet(0), written.get());
    }

    // ------------------------------------------------------------------ utilidades

    private static byte[] readUpTo(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < max) {
            int n = in.read(buf, 0, Math.min(buf.length, max - total));
            if (n < 0) {
                break;
            }
            bo.write(buf, 0, n);
            total += n;
        }
        return bo.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    private static Field findField(Class<?> c, String srg, String mcp) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (String n : new String[] {srg, mcp}) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // próximo
                }
            }
        }
        throw new NoSuchFieldException(srg + "/" + mcp);
    }

    /** Só para testes. */
    static synchronized void resetForTests() {
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writer = null;
        }
        flushIndex();
        dirty = false;
        initialized = false;
        enabled = false;
        INDEX.clear();
        HITS.clear();
        SEEN.clear();
    }
}
