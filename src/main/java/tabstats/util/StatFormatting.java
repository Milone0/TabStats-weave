package tabstats.util;

import tabstats.config.ModConfig;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatInt;
import tabstats.playerapi.api.stats.StatString;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns a player's stats into the strings that get shown, for the tab list and for the chat
 * commands alike - both have to agree on what a stat is called and what its value looks like.
 */
public final class StatFormatting {

    private StatFormatting() {
    }

    /** The value of a stat the way it is displayed, colour codes included. */
    public static String valueOf(Stat stat) {
        if (stat == null) {
            return "";
        }

        switch (stat.getType()) {
            case INT:
                return Integer.toString(((StatInt) stat).getValue());
            case STRING:
                String value = ((StatString) stat).getValue();
                return value == null ? "" : value;
            default:
                return "";
        }
    }

    /** A stat name in the form everything matches on: trimmed and upper-cased. */
    public static String key(String statName) {
        return statName == null ? "" : statName.trim().toUpperCase(Locale.ROOT);
    }

    /** Every stat a gamemode has for a player, in the order the game class produced them. */
    public static List<Stat> rawStats(HPlayer player, String gamemode) {
        if (player == null || gamemode == null) {
            return Collections.emptyList();
        }

        List<Stat> stats = player.getFormattedGameStats(key(gamemode));
        return stats == null ? Collections.<Stat>emptyList() : stats;
    }

    /** The stats the mod is set to show for a gamemode, in the configured order. */
    public static List<Stat> configuredStats(HPlayer player, String gamemode) {
        List<Stat> stats = rawStats(player, gamemode);
        if (stats.isEmpty()) {
            return stats;
        }

        return ModConfig.getInstance().getStatColumns().apply(stats, key(gamemode));
    }

    /** The stat of that name, or null when the player has none. */
    public static Stat find(List<Stat> stats, String statName) {
        if (stats == null) {
            return null;
        }

        String wanted = key(statName);
        for (Stat stat : stats) {
            if (stat != null && key(stat.getStatName()).equals(wanted)) {
                return stat;
            }
        }

        return null;
    }
}
