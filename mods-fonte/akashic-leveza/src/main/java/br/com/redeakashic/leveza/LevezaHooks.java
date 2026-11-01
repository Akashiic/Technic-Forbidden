package br.com.redeakashic.leveza;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chamado pelo bytecode injetado. Não referencia classes do Minecraft diretamente (só por reflexão
 * com nomes SRG e, de reserva, MCP), então funciona com qualquer mapeamento. Qualquer erro aqui é
 * engolido e a textura original segue sem alteração: o mod nunca deve derrubar o jogo.
 */
public final class LevezaHooks {

    private static final int MIN_SIDE = 16;

    // estatísticas desde o último resumo (um resumo por stitch de atlas)
    static final AtomicLong spritesSeen = new AtomicLong();
    static final AtomicLong spritesReduced = new AtomicLong();
    static final AtomicLong animBefore = new AtomicLong();
    static final AtomicLong animAfter = new AtomicLong();
    static final AtomicLong staticBefore = new AtomicLong();
    static final AtomicLong staticAfter = new AtomicLong();
    // acumulado da sessão
    static final AtomicLong uploadsReduced = new AtomicLong();
    static final AtomicLong uploadBefore = new AtomicLong();
    static final AtomicLong uploadAfter = new AtomicLong();

    private static final ThreadLocal<Object> CURRENT_SIMPLE_TEXTURE = new ThreadLocal<Object>();
    private static final ThreadLocal<String> CURRENT_BASE_PATH = new ThreadLocal<String>();
    private static Field textureMapBasePathField;

    private static volatile boolean reflectionBroken;
    private static Field spriteNameField;
    private static Field metaFramesField;
    private static Field metaWidthField;
    private static Field metaHeightField;
    private static Field metaFrameTimeField;
    private static Constructor<?> metaCtor;
    private static Field simpleTextureLocationField;

    private LevezaHooks() {
    }

    // ------------------------------------------------------------------ atlas

    /**
     * Início de TextureAtlasSprite.loadSprite(BufferedImage[], AnimationMetadataSection, boolean).
     * Pode trocar images[0] e devolve o AnimationMetadataSection a usar.
     */
    public static Object preLoadSprite(Object sprite, BufferedImage[] images, Object meta) {
        try {
            LevezaConfig cfg = LevezaConfig.get();
            if (!cfg.enabled || !cfg.reduceAtlas || reflectionBroken
                    || images == null || images.length == 0 || images[0] == null) {
                return meta;
            }
            BufferedImage img = images[0];
            String base = CURRENT_BASE_PATH.get();
            if (cfg.onlyVanillaAtlas && !"textures/blocks".equals(base) && !"textures/items".equals(base)) {
                return meta; // atlas próprio de mod, ou loadSprite chamado fora do TextureMap: não mexer
            }
            spritesSeen.incrementAndGet();
            String name = spriteName(sprite);

            // cache de decodificação: a imagem pode já ter chegado reduzida do disco
            String loc = LevezaCache.active() ? location(name) : null;
            LevezaCache.Seen seen = LevezaCache.takeSeen(loc);
            LevezaCache.Hit hit = LevezaCache.takeHit(loc);
            if (hit != null) {
                Object fromCache = applyHit(cfg, hit, name, img, meta);
                if (fromCache != NO_HIT) {
                    return fromCache;
                }
                // divergência: volta para a imagem original e segue o caminho normal (e regrava o cache)
                images[0] = img = LevezaCache.decodeOriginal(hit);
                seen = new LevezaCache.Seen(hit.entry.sha, hit.original.length);
            }

            int w = img.getWidth();
            int h = img.getHeight();
            if (isIgnored(cfg, name)) {
                count(meta != null, (long) w * h, (long) w * h);
                return meta;
            }

            if (meta == null) {
                if (w != h) {
                    return null; // o vanilla recusa; não interferir
                }
                int f = ImageScaler.factor(w, w <= 128 ? cfg.staticMax : cfg.modelMax, MIN_SIDE);
                if (f == 1) {
                    count(false, (long) w * h, (long) w * h);
                    return null;
                }
                images[0] = ImageScaler.scale(img, f);
                dropMips(images);
                count(false, (long) w * h, (long) (w / f) * (h / f));
                reduced(cfg, name, w, h, images[0], 1);
                LevezaCache.store(loc, seen, images[0], 1, -1);
                return null;
            }

            // animação: frames quadrados w x w empilhados na vertical (regra do 1.7.10)
            if (w <= 0 || h % w != 0) {
                count(true, (long) w * h, (long) w * h);
                return meta;
            }
            int frames = h / w;
            List<?> explicitFrames = (List<?>) metaFramesField.get(meta);
            int f = ImageScaler.factor(w, cfg.animMax, MIN_SIDE);
            int step = 1;
            if (frames > cfg.framesMax && (explicitFrames == null || explicitFrames.isEmpty())) {
                step = (frames + cfg.framesMax - 1) / cfg.framesMax;
            }
            if (f == 1 && step == 1) {
                count(true, (long) w * h, (long) w * h);
                return meta;
            }
            Object newMeta = meta;
            if (step > 1) {
                int frameTime = Math.max(1, metaFrameTimeField.getInt(meta));
                newMeta = metaCtor.newInstance(new ArrayList<Object>(),
                        metaWidthField.getInt(meta), metaHeightField.getInt(meta), frameTime * step);
            }
            images[0] = ImageScaler.animation(img, w, frames, step, f);
            dropMips(images);
            count(true, (long) w * h, (long) images[0].getWidth() * images[0].getHeight());
            reduced(cfg, name, w, h, images[0], step);
            LevezaCache.store(loc, seen, images[0], step, metaFrameTimeField.getInt(meta));
            return newMeta;
        } catch (Throwable t) {
            failOnce(t);
            return meta;
        }
    }

    private static final Object NO_HIT = new Object();
    static final AtomicLong cacheHitsApplied = new AtomicLong();

    /** Valida uma textura vinda do cache e devolve o metadata a usar, ou NO_HIT se precisar da original. */
    private static Object applyHit(LevezaConfig cfg, LevezaCache.Hit hit, String name, BufferedImage img, Object meta)
            throws Exception {
        LevezaCache.Entry e = hit.entry;
        if (isIgnored(cfg, name) || img.getWidth() != e.w || img.getHeight() != e.h) {
            return NO_HIT;
        }
        if (meta == null) {
            if (e.frameTime != -1) {
                return NO_HIT;
            }
            count(false, (long) e.w * e.h, (long) e.w * e.h);
            cacheHitsApplied.incrementAndGet();
            return null;
        }
        List<?> explicitFrames = (List<?>) metaFramesField.get(meta);
        int frameTime = metaFrameTimeField.getInt(meta);
        boolean sequential = explicitFrames == null || explicitFrames.isEmpty();
        if (e.frameTime != frameTime || (e.step > 1 && !sequential)) {
            return NO_HIT;
        }
        count(true, (long) e.w * e.h, (long) e.w * e.h);
        cacheHitsApplied.incrementAndGet();
        if (e.step <= 1) {
            return meta;
        }
        return metaCtor.newInstance(new ArrayList<Object>(),
                metaWidthField.getInt(meta), metaHeightField.getInt(meta), Math.max(1, frameTime) * e.step);
    }

    /** Início de TextureMap.loadTextureAtlas: guarda o basePath (textures/blocks ou textures/items). */
    public static void enterTextureMap(Object textureMap) {
        try {
            if (textureMapBasePathField == null) {
                textureMapBasePathField = findField(textureMap.getClass(), "field_94254_c", "basePath");
            }
            Object v = textureMapBasePathField.get(textureMap);
            CURRENT_BASE_PATH.set(v == null ? null : v.toString());
        } catch (Throwable t) {
            CURRENT_BASE_PATH.remove();
        }
    }

    /** Início de SimpleResource.getInputStream. */
    public static void onGetInputStream(Object resource) {
        LevezaCache.onGetInputStream(resource);
    }

    /** ResourceLocation do PNG do sprite no atlas atual, no mesmo formato de ResourceLocation.toString(). */
    static String location(String spriteName) {
        String base = CURRENT_BASE_PATH.get();
        if (base == null || spriteName == null) {
            return null;
        }
        int c = spriteName.indexOf(':');
        String domain = c < 0 ? "minecraft" : spriteName.substring(0, c).toLowerCase(Locale.ROOT);
        String path = c < 0 ? spriteName : spriteName.substring(c + 1);
        return domain + ":" + base + "/" + path + ".png";
    }

    private static void count(boolean animated, long before, long after) {
        if (animated) {
            animBefore.addAndGet(before);
            animAfter.addAndGet(after);
        } else {
            staticBefore.addAndGet(before);
            staticAfter.addAndGet(after);
        }
    }

    private static void reduced(LevezaConfig cfg, String name, int w, int h, BufferedImage out, int step) {
        spritesReduced.incrementAndGet();
        if (cfg.verbose) {
            LevezaLog.info("atlas " + name + " " + w + "x" + h + " -> " + out.getWidth() + "x" + out.getHeight()
                    + (step > 1 ? " (1 a cada " + step + " frames)" : ""));
        }
    }

    private static void dropMips(BufferedImage[] images) {
        // mipmaps pré-fornecidos teriam o tamanho antigo; o jogo regenera a partir de images[0]
        for (int i = 1; i < images.length; i++) {
            images[i] = null;
        }
    }

    // ------------------------------------------------------------------ texturas avulsas

    /** Início de SimpleTexture.loadTexture: guarda qual textura está carregando nesta thread. */
    public static void enterSimpleTexture(Object simpleTexture) {
        CURRENT_SIMPLE_TEXTURE.set(simpleTexture);
    }

    /** Início de TextureUtil.uploadTextureImageAllocate(int, BufferedImage, boolean, boolean). */
    public static BufferedImage preUploadImage(BufferedImage img) {
        Object owner = CURRENT_SIMPLE_TEXTURE.get();
        CURRENT_SIMPLE_TEXTURE.remove();
        try {
            LevezaConfig cfg = LevezaConfig.get();
            if (!cfg.enabled || !cfg.reduceLargeTextures || img == null || owner == null) {
                return img;
            }
            if (Math.max(img.getWidth(), img.getHeight()) <= cfg.otherMax || !calledFromLoadTexture()) {
                return img;
            }
            String name = simpleTextureName(owner);
            if (isIgnored(cfg, name)) {
                return img;
            }
            return reduceUpload(cfg, img, name);
        } catch (Throwable t) {
            failOnce(t);
            return img;
        }
    }

    /** Início de ReikaTextureBinder.allocateAndSetupTexture / loadImageOntoTexture. */
    public static BufferedImage preUploadReika(BufferedImage img) {
        try {
            LevezaConfig cfg = LevezaConfig.get();
            if (!cfg.enabled || !cfg.reduceReika || img == null
                    || Math.max(img.getWidth(), img.getHeight()) <= cfg.otherMax) {
                return img;
            }
            return reduceUpload(cfg, img, "reika");
        } catch (Throwable t) {
            failOnce(t);
            return img;
        }
    }

    private static BufferedImage reduceUpload(LevezaConfig cfg, BufferedImage img, String name) {
        int w = img.getWidth();
        int h = img.getHeight();
        int f = ImageScaler.factor2d(w, h, cfg.otherMax);
        if (f == 1) {
            return img;
        }
        BufferedImage out = ImageScaler.scale(img, f);
        uploadsReduced.incrementAndGet();
        uploadBefore.addAndGet((long) w * h);
        uploadAfter.addAndGet((long) out.getWidth() * out.getHeight());
        if (cfg.verbose) {
            LevezaLog.info("textura " + name + " " + w + "x" + h + " -> " + out.getWidth() + "x" + out.getHeight());
        }
        return out;
    }

    /** Garante que o upload vem de um loadTexture (ITextureObject), não de código que depois faz glTexSubImage. */
    private static boolean calledFromLoadTexture() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(st.length, 8); i++) {
            String m = st[i].getMethodName();
            if (m.equals("func_110551_a") || m.equals("loadTexture") || m.contains("loadTexture")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ resumo

    /** Chamado pelo LevezaMod em TextureStitchEvent.Post. */
    public static void logSummary() {
        long seen = spritesSeen.getAndSet(0);
        long red = spritesReduced.getAndSet(0);
        long ab = animBefore.getAndSet(0);
        long aa = animAfter.getAndSet(0);
        long sb = staticBefore.getAndSet(0);
        long sa = staticAfter.getAndSet(0);
        long fromCache = cacheHitsApplied.getAndSet(0);
        LevezaCache.endOfStitch();
        String base = CURRENT_BASE_PATH.get();
        CURRENT_BASE_PATH.remove();
        if (seen == 0) {
            return;
        }
        LevezaLog.info(String.format(Locale.ROOT,
                "Atlas " + base + ": %d de %d sprites reduzidos | animações %.0f -> %.0f MiB (heap residente, sem mipmaps)"
                        + " | estáticos %.0f -> %.0f MiB | texturas avulsas na sessão: %d reduzidas, %.0f -> %.0f MiB",
                red, seen, mib(ab), mib(aa), mib(sb), mib(sa),
                uploadsReduced.get(), mib(uploadBefore.get()), mib(uploadAfter.get()))
                + " | " + fromCache + " vindos do " + LevezaCache.summary() + " | " + ReikaPrefilter.summary());
    }

    private static double mib(long px) {
        return px * 4.0 / (1024 * 1024);
    }

    // ------------------------------------------------------------------ utilidades

    private static boolean isIgnored(LevezaConfig cfg, String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        int colon = n.indexOf(':');
        String domain = colon < 0 ? "minecraft" : n.substring(0, colon);
        if (cfg.ignoredDomains.contains(domain)) {
            return true;
        }
        for (String p : cfg.ignoredPrefixes) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String spriteName(Object sprite) throws Exception {
        initReflection(sprite);
        Object v = spriteNameField.get(sprite);
        return v == null ? null : v.toString();
    }

    private static String simpleTextureName(Object tex) {
        try {
            if (simpleTextureLocationField == null) {
                simpleTextureLocationField = findField(tex.getClass(), "field_110568_b", "textureLocation");
            }
            Object loc = simpleTextureLocationField.get(tex);
            return loc == null ? null : loc.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void initReflection(Object sprite) throws Exception {
        if (spriteNameField != null) {
            return;
        }
        Field name = findField(sprite.getClass(), "field_110984_i", "iconName");
        Class<?> metaClass = Class.forName("net.minecraft.client.resources.data.AnimationMetadataSection",
                false, sprite.getClass().getClassLoader());
        metaFramesField = findField(metaClass, "field_110478_a", "animationFrames");
        metaWidthField = findField(metaClass, "field_110476_b", "frameWidth");
        metaHeightField = findField(metaClass, "field_110477_c", "frameHeight");
        metaFrameTimeField = findField(metaClass, "field_110475_d", "frameTime");
        metaCtor = metaClass.getDeclaredConstructor(List.class, int.class, int.class, int.class);
        metaCtor.setAccessible(true);
        spriteNameField = name;
    }

    private static Field findField(Class<?> c, String srg, String mcp) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (String n : new String[] {srg, mcp}) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // tenta o próximo
                }
            }
        }
        throw new NoSuchFieldException(srg + "/" + mcp + " em " + c.getName());
    }

    private static volatile boolean warned;

    private static void failOnce(Throwable t) {
        if (t instanceof NoSuchFieldException || t instanceof NoSuchMethodException || t instanceof ClassNotFoundException) {
            reflectionBroken = true;
        }
        if (!warned) {
            warned = true;
            LevezaLog.error("Erro ao reduzir uma textura; ela segue original. (aviso mostrado só uma vez)", t);
        }
    }
}
