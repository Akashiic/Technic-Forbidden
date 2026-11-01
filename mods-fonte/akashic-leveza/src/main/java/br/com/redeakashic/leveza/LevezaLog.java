package br.com.redeakashic.leveza;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.Normalizer;

/** Log sempre em ASCII: o Java 8 com charset padrão (Windows/POSIX) troca acentos por '?'. */
final class LevezaLog {
    private static final Logger LOG = LogManager.getLogger("AkashicLeveza");

    private LevezaLog() {
    }

    static void info(String msg) {
        LOG.info(ascii(msg));
    }

    static void warn(String msg) {
        LOG.warn(ascii(msg));
    }

    static void error(String msg, Throwable t) {
        LOG.error(ascii(msg), t);
    }

    static String ascii(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
