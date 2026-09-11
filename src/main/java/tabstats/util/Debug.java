package tabstats.util;

import tabstats.config.ModConfig;

/**
 * Traces what happens to the names seen in chat. Goes to the game log, and is on while the
 * pre-lobby reveal is still being shaken out against real lobbies - Hypixel's chat formats are
 * the whole basis of that feature and only a real lobby shows what they are.
 */
public final class Debug {

    private Debug() {
    }

    public static void chatReveal(String message) {
        if (!ModConfig.getInstance().isChatRevealDebugEnabled()) {
            return;
        }

        System.out.println("[TabStats] " + message);
    }
}
