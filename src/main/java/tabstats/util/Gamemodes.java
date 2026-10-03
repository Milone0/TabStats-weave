package tabstats.util;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.util.Locale;

/**
 * Resolves the Hypixel game the client is currently in from the scoreboard sidebar title,
 * which is the only signal the mod has. Returns one of the gamemode names the stat classes
 * are keyed by, or null for anything else (main lobbies, unsupported games).
 */
public final class Gamemodes {

    private Gamemodes() {
    }

    public static String resolve(Scoreboard scoreboard) {
        if (scoreboard == null) {
            return null;
        }

        ScoreObjective sidebarObjective = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebarObjective == null) {
            return null;
        }

        String stripped = ChatColor.stripColor(sidebarObjective.getDisplayName());
        if (stripped == null) {
            return null;
        }

        String normalized = stripped.replace(" ", "").toUpperCase(Locale.ROOT);
        if ("BEDWARS".equals(normalized) || "DUELS".equals(normalized) || "SKYWARS".equals(normalized)) {
            return normalized;
        }

        return null;
    }

    /**
     * Whether the client is somewhere the mod has anything to do: inside a supported game or the
     * pre-game lobby that leads into it. Anywhere else - the main lobby, and just as importantly a
     * game lobby, which titles its sidebar with the game name exactly like the game does - the mod
     * stays out of the way instead of spending API calls on a crowd whose stats it never shows.
     *
     * <p>The sidebar title says which game; {@link HypixelLocation} says whether this is the game
     * or the lobby in front of it.
     */
    public static boolean inSupportedGame() {
        return resolveCurrent() != null && HypixelLocation.inGame();
    }

    /** Same as {@link #resolve(Scoreboard)} for the scoreboard the client is looking at right now. */
    public static String resolveCurrent() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return null;
        }

        return resolve(mc.thePlayer.getWorldScoreboard());
    }
}
