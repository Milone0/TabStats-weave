package tabstats.util;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

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

        String normalized = stripped.replace(" ", "").toUpperCase();
        if ("BEDWARS".equals(normalized) || "DUELS".equals(normalized) || "SKYWARS".equals(normalized)) {
            return normalized;
        }

        return null;
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
