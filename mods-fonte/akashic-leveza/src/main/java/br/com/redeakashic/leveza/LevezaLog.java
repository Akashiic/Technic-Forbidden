package br.com.redeakashic.leveza;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class LevezaLog {
    private static final Logger LOG = LogManager.getLogger("AkashicLeveza");

    private LevezaLog() {
    }

    static void info(String msg) {
        LOG.info(msg);
    }

    static void warn(String msg) {
        LOG.warn(msg);
    }

    static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }
}
