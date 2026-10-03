package tabstats.playerapi.api.games;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tabstats.playerapi.api.stats.Stat;
import tabstats.util.StatFormatting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stats of one game for one player. Everything is read and formatted once, in the
 * constructor; the API answer is not kept around afterwards, and the tab only ever reads the
 * finished values.
 */
public abstract class HGameBase {
    private final String playerName, playerUUID;
    private List<Stat> formattedStats = Collections.emptyList();
    private Map<String, Stat> statsByKey = Collections.emptyMap();

    protected HGameBase(String playerName, String playerUUID) {
        this.playerName = playerName;
        this.playerUUID = playerUUID;
    }

    /**
     * @return Game Enumeration of sub-classes Game
     */
    public abstract HypixelGames getGame();

    /**
     * Every formatted stat, colours included, in the order the game class produces them. Empty
     * when the player has no stats for this game, so only their name is shown.
     */
    public final List<Stat> getFormattedStatList() {
        return this.formattedStats;
    }

    /** The same stats keyed by {@link StatFormatting#key}, so the tab can draw each under its column. */
    public final Map<String, Stat> getStatsByKey() {
        return this.statsByKey;
    }

    /** Called once by the subclass constructor with the finished stats. */
    protected final void setFormattedStats(List<Stat> stats) {
        Map<String, Stat> byKey = new LinkedHashMap<>();
        for (Stat stat : stats) {
            byKey.put(StatFormatting.key(stat.getStatName()), stat);
        }

        this.formattedStats = Collections.unmodifiableList(stats);
        this.statsByKey = Collections.unmodifiableMap(byKey);
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public String getPlayerUUID() {
        return this.playerUUID;
    }

    /** The stats of one game inside the API's player object, or null when the player never played it. */
    protected static JsonObject gameStats(JsonObject player, HypixelGames game) {
        return child(child(player, "stats"), game.getApiName());
    }

    protected static JsonObject child(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** A string field, or null when it is missing or not a plain value. */
    protected static String string(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * {@code a / b} rounded to two decimals, or {@code a} itself when {@code b} is 0 - the usual
     * convention for K/D style ratios.
     */
    protected static double ratio(int a, int b) {
        if (b == 0) {
            return a;
        }
        return Math.round(a * 100.0 / b) / 100.0;
    }
}
