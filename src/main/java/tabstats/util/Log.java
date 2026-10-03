package tabstats.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The mod's logger. Minecraft 1.8.9 ships log4j, so this writes to the game log next to
 * everything else - which is the only place a failure on a worker thread can show up at all.
 */
public final class Log {
    private static final Logger LOGGER = LogManager.getLogger("TabStats");

    private Log() {
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warn(message);
    }

    public static void warn(String message, Throwable cause) {
        LOGGER.warn(message, cause);
    }

    public static void error(String message, Throwable cause) {
        LOGGER.error(message, cause);
    }
}
