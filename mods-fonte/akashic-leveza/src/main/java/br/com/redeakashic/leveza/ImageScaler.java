package br.com.redeakashic.leveza;

import java.awt.image.BufferedImage;

/** Redução por fator inteiro (média por área com alfa pré-multiplicado), sem dependências do Minecraft. */
final class ImageScaler {

    private ImageScaler() {
    }

    /**
     * Maior fator 2^n que deixa {@code side} <= {@code max}, mantendo divisão exata e o resultado >= minSide.
     * Um lado múltiplo de 16 nunca vira um lado que não seja múltiplo de 16: folhas de CTM (grades de
     * sub-blocos de 16px) continuam íntegras e o nível de mipmap do atlas não é rebaixado.
     */
    static int factor(int side, int max, int minSide) {
        int f = 1;
        int floor = Math.min(minSide, max);
        boolean aligned16 = side % 16 == 0;
        while (side / f > max && side % (f * 2) == 0 && side / (f * 2) >= floor
                && (!aligned16 || (side / (f * 2)) % 16 == 0 || side / (f * 2) < 16 && floor < 16)) {
            f *= 2;
        }
        return f;
    }

    /** Fator 2^n para uma imagem retangular cujo maior lado deve ficar <= max. */
    static int factor2d(int w, int h, int max) {
        int f = 1;
        while (Math.max(w, h) / f > max && w % (f * 2) == 0 && h % (f * 2) == 0 && Math.min(w, h) / (f * 2) >= 1) {
            f *= 2;
        }
        return f;
    }

    static BufferedImage scale(BufferedImage src, int f) {
        int w = src.getWidth();
        int h = src.getHeight();
        int nw = w / f;
        int nh = h / f;
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        // processa em faixas para não alocar a imagem inteira em int[] de uma vez
        int bandRows = Math.max(f, (1 << 20) / Math.max(1, w) / f * f);
        int[] in = new int[w * Math.min(h, bandRows)];
        int[] out = new int[nw * (Math.min(h, bandRows) / f)];
        for (int y0 = 0; y0 < h; y0 += bandRows) {
            int rows = Math.min(bandRows, h - y0);
            src.getRGB(0, y0, w, rows, in, 0, w);
            box(in, w, rows, f, out);
            dst.setRGB(0, y0 / f, nw, rows / f, out, 0, nw);
        }
        return dst;
    }

    /**
     * Monta uma tira de animação: mantém 1 a cada {@code step} frames (quadrados, lado {@code w})
     * e reduz cada frame pelo fator {@code f}.
     */
    static BufferedImage animation(BufferedImage src, int w, int frames, int step, int f) {
        int nw = w / f;
        int kept = (frames + step - 1) / step;
        BufferedImage dst = new BufferedImage(nw, nw * kept, BufferedImage.TYPE_INT_ARGB);
        int[] in = new int[w * w];
        int[] out = new int[nw * nw];
        int i = 0;
        for (int idx = 0; idx < frames; idx += step) {
            src.getRGB(0, idx * w, w, w, in, 0, w);
            if (f == 1) {
                dst.setRGB(0, i * nw, nw, nw, in, 0, nw);
            } else {
                box(in, w, w, f, out);
                dst.setRGB(0, i * nw, nw, nw, out, 0, nw);
            }
            i++;
        }
        return dst;
    }

    private static void box(int[] in, int w, int h, int f, int[] out) {
        int nw = w / f;
        int nh = h / f;
        int n = f * f;
        for (int oy = 0; oy < nh; oy++) {
            for (int ox = 0; ox < nw; ox++) {
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int dy = 0; dy < f; dy++) {
                    int row = (oy * f + dy) * w + ox * f;
                    for (int dx = 0; dx < f; dx++) {
                        int p = in[row + dx];
                        int pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                    }
                }
                int oa = (int) ((a + n / 2) / n);
                int or = 0;
                int og = 0;
                int ob = 0;
                if (a > 0) {
                    or = (int) ((r + a / 2) / a);
                    og = (int) ((g + a / 2) / a);
                    ob = (int) ((b + a / 2) / a);
                }
                out[oy * nw + ox] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        }
    }
}
