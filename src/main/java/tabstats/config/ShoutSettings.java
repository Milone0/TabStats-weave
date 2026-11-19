package tabstats.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How {@code /bw shout} decides who is worth telling the team about, and whether it runs on its
 * own when a game starts.
 *
 * <p>A player is "good" when they reach the minimum of at least one rule - or of every rule, when
 * {@link #isMatchAll()} is set. A stat without a minimum is no rule at all, and with no rules left
 * everyone counts as good. Nicked and Urchin-tagged players can be let through regardless, since
 * a nick hides exactly the stats the rules would look at.
 */
public final class ShoutSettings {
    /** The stats a minimum can be set for, in the order the settings screen lists them. */
    public static final String[] RULE_STATS = {"STAR", "FKDR", "WLR", "WS", "FINALS", "WINS", "BBLR"};

    private static final String AUTO_SHOUT = "AutoShout";
    private static final String INCLUDE_OWN_TEAM = "IncludeOwnTeam";
    private static final String MATCH_ALL = "MatchAll";
    private static final String ALWAYS_SHOW_FLAGGED = "AlwaysShowFlagged";
    private static final String MINIMUMS = "Minimums";

    private boolean autoShout;
    private boolean includeOwnTeam;
    private boolean matchAll;
    private boolean alwaysShowFlagged = true;
    /** Stat key to minimum. A stat that is missing here is not a rule. */
    private final Map<String, Double> minimums = new LinkedHashMap<>();

    public ShoutSettings() {
        resetMinimums();
    }

    private void resetMinimums() {
        this.minimums.clear();
        this.minimums.put("STAR", 400.0);
        this.minimums.put("FKDR", 3.0);
    }

    public synchronized boolean isAutoShout() {
        return this.autoShout;
    }

    public synchronized void setAutoShout(boolean autoShout) {
        this.autoShout = autoShout;
    }

    public synchronized boolean isIncludeOwnTeam() {
        return this.includeOwnTeam;
    }

    public synchronized void setIncludeOwnTeam(boolean includeOwnTeam) {
        this.includeOwnTeam = includeOwnTeam;
    }

    public synchronized boolean isMatchAll() {
        return this.matchAll;
    }

    public synchronized void setMatchAll(boolean matchAll) {
        this.matchAll = matchAll;
    }

    public synchronized boolean isAlwaysShowFlagged() {
        return this.alwaysShowFlagged;
    }

    public synchronized void setAlwaysShowFlagged(boolean alwaysShowFlagged) {
        this.alwaysShowFlagged = alwaysShowFlagged;
    }

    /** The minimum for a stat, or null when that stat is no rule. */
    public synchronized Double getMinimum(String stat) {
        return this.minimums.get(key(stat));
    }

    /** Sets a minimum; null, or a value below zero, removes the rule. */
    public synchronized void setMinimum(String stat, Double minimum) {
        String key = key(stat);
        if (minimum == null || minimum < 0 || minimum.isNaN() || minimum.isInfinite()) {
            this.minimums.remove(key);
        } else {
            this.minimums.put(key, minimum);
        }
    }

    /**
     * @param values  the player's stats as numbers, keyed by stat key; a stat the player has no
     *                number for is simply absent
     * @param flagged nicked, or tagged by Urchin
     */
    public synchronized boolean isGood(Map<String, Double> values, boolean flagged) {
        if (flagged && this.alwaysShowFlagged) {
            return true;
        }

        if (this.minimums.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Double> rule : this.minimums.entrySet()) {
            Double value = values == null ? null : values.get(rule.getKey());
            boolean reached = value != null && value >= rule.getValue();
            if (reached && !this.matchAll) {
                return true;
            }
            if (!reached && this.matchAll) {
                return false;
            }
        }

        return this.matchAll;
    }

    /** Shape written to config.json. */
    public synchronized Map<String, Object> toSerializable() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AUTO_SHOUT, this.autoShout);
        out.put(INCLUDE_OWN_TEAM, this.includeOwnTeam);
        out.put(MATCH_ALL, this.matchAll);
        out.put(ALWAYS_SHOW_FLAGGED, this.alwaysShowFlagged);
        out.put(MINIMUMS, new LinkedHashMap<>(this.minimums));
        return out;
    }

    /**
     * Reads what {@link #toSerializable()} wrote. Anything missing keeps its default; a stored
     * set of minimums replaces the default one entirely, so a rule that was removed stays removed.
     */
    public synchronized void loadFrom(JsonObject object) {
        if (object == null) {
            return;
        }

        this.autoShout = bool(object, AUTO_SHOUT, this.autoShout);
        this.includeOwnTeam = bool(object, INCLUDE_OWN_TEAM, this.includeOwnTeam);
        this.matchAll = bool(object, MATCH_ALL, this.matchAll);
        this.alwaysShowFlagged = bool(object, ALWAYS_SHOW_FLAGGED, this.alwaysShowFlagged);

        JsonElement stored = object.get(MINIMUMS);
        if (stored == null || !stored.isJsonObject()) {
            return;
        }

        this.minimums.clear();
        for (Map.Entry<String, JsonElement> entry : stored.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) {
                continue;
            }

            try {
                setMinimum(entry.getKey(), value.getAsDouble());
            } catch (RuntimeException ignored) {
                // not a number - that rule is dropped
            }
        }
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }

        try {
            return element.getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String key(String stat) {
        return stat == null ? "" : stat.trim().toUpperCase(Locale.ROOT);
    }
}
