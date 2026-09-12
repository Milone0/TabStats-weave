package tabstats.config;

import tabstats.playerapi.api.stats.Stat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which stat columns the tab shows, and in what order, per gamemode.
 *
 * <p>The game classes always hand out their full stat list; this layout is applied on top of it
 * right before rendering, so a column that was moved or switched off here never reaches the tab.
 * Columns are matched by stat name, trimmed and upper-cased, because some names carry padding
 * spaces to widen their column (DUELS "TITLE").
 */
public final class StatColumnLayout {
    /** The gamemodes {@link tabstats.util.Gamemodes} can resolve, in the order the GUI cycles them. */
    public static final String[] GAMEMODES = {"BEDWARS", "SKYWARS", "DUELS"};

    private static final Map<String, List<String>> DEFAULTS = buildDefaults();

    private final Map<String, List<Column>> byGamemode = new LinkedHashMap<>();

    /** One column: the stat it stands for, plus whether the tab draws it. */
    public static final class Column {
        private final String name;
        private final String key;
        private boolean enabled;

        private Column(String name, boolean enabled) {
            this.name = name;
            this.key = normalize(name);
            this.enabled = enabled;
        }

        public String getName() {
            return this.name;
        }

        public String getKey() {
            return this.key;
        }

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static Map<String, List<String>> buildDefaults() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        defaults.put("BEDWARS", Arrays.asList("TAG", "STAR", "WS", "FKDR", "FINALS", "WLR", "WINS", "BBLR"));
        defaults.put("SKYWARS", Arrays.asList("STAR", "KDR", "KILLS", "WLR", "WINS"));
        defaults.put("DUELS", Arrays.asList("TITLE", "WS", "BWS", "KILLS", "WLR", "WINS", "LOSSES"));
        return Collections.unmodifiableMap(defaults);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The columns for a gamemode, in display order. Any default column the stored config does not
     * know yet is appended, so stats added in a later version show up instead of silently going
     * missing.
     */
    public List<Column> getColumns(String gamemode) {
        String key = normalize(gamemode);
        List<Column> columns = this.byGamemode.get(key);
        if (columns == null) {
            columns = new ArrayList<>();
            this.byGamemode.put(key, columns);
        }

        List<String> defaults = DEFAULTS.get(key);
        if (defaults != null) {
            for (String name : defaults) {
                if (indexOf(columns, name) < 0) {
                    columns.add(new Column(name, true));
                }
            }
        }

        return columns;
    }

    private static int indexOf(List<Column> columns, String name) {
        String key = normalize(name);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Swaps the column at {@code index} with the one above it. */
    public boolean moveUp(String gamemode, int index) {
        return swap(gamemode, index, index - 1);
    }

    /** Swaps the column at {@code index} with the one below it. */
    public boolean moveDown(String gamemode, int index) {
        return swap(gamemode, index, index + 1);
    }

    private boolean swap(String gamemode, int from, int to) {
        List<Column> columns = getColumns(gamemode);
        if (from < 0 || to < 0 || from >= columns.size() || to >= columns.size()) {
            return false;
        }

        Collections.swap(columns, from, to);
        return true;
    }

    /** Flips a column between shown and hidden. */
    public boolean toggle(String gamemode, int index) {
        List<Column> columns = getColumns(gamemode);
        if (index < 0 || index >= columns.size()) {
            return false;
        }

        Column column = columns.get(index);
        column.setEnabled(!column.isEnabled());
        return true;
    }

    /** Puts one gamemode back to the order the stat classes produce, with everything shown. */
    public void resetToDefaults(String gamemode) {
        this.byGamemode.remove(normalize(gamemode));
        getColumns(gamemode);
    }

    /**
     * Reorders and filters a freshly built stat list. Stats this layout has no column for keep
     * their relative order and are appended, so nothing disappears just because it is unknown.
     */
    public List<Stat> apply(List<Stat> stats, String gamemode) {
        if (stats == null || stats.isEmpty()) {
            return stats == null ? Collections.<Stat>emptyList() : stats;
        }

        List<Column> columns = getColumns(gamemode);
        if (columns.isEmpty()) {
            return stats;
        }

        Map<String, List<Stat>> byName = new LinkedHashMap<>();
        for (Stat stat : stats) {
            if (stat == null) {
                continue;
            }

            String key = normalize(stat.getStatName());
            List<Stat> bucket = byName.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>(1);
                byName.put(key, bucket);
            }
            bucket.add(stat);
        }

        List<Stat> ordered = new ArrayList<>(stats.size());
        for (Column column : columns) {
            List<Stat> bucket = byName.get(column.getKey());
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }

            Stat stat = bucket.remove(0);
            if (column.isEnabled()) {
                ordered.add(stat);
            }
        }

        for (List<Stat> leftover : byName.values()) {
            ordered.addAll(leftover);
        }

        return ordered;
    }

    /** Shape written to config.json: gamemode to [{name, enabled}, ...]. */
    public Map<String, Object> toSerializable() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String gamemode : GAMEMODES) {
            List<Object> rows = new ArrayList<>();
            for (Column column : getColumns(gamemode)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", column.getName());
                row.put("enabled", column.isEnabled());
                rows.add(row);
            }
            out.put(gamemode, rows);
        }
        return out;
    }

    /**
     * Reads the stored layout. A hand-edited config may list a column as a bare string instead of
     * an object; that counts as shown.
     */
    public void loadFrom(JsonObject object) {
        this.byGamemode.clear();
        if (object == null) {
            return;
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonArray()) {
                continue;
            }

            List<Column> columns = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                Column column = readColumn(element);
                if (column != null && indexOf(columns, column.getName()) < 0) {
                    columns.add(column);
                }
            }

            this.byGamemode.put(normalize(entry.getKey()), columns);
        }
    }

    private static Column readColumn(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }

            if (element.isJsonPrimitive()) {
                String name = element.getAsString();
                return name == null || name.trim().isEmpty() ? null : new Column(name.trim(), true);
            }

            if (!element.isJsonObject()) {
                return null;
            }

            JsonObject row = element.getAsJsonObject();
            if (!row.has("name")) {
                return null;
            }

            String name = row.get("name").getAsString();
            if (name == null || name.trim().isEmpty()) {
                return null;
            }

            boolean enabled = !row.has("enabled") || row.get("enabled").getAsBoolean();
            return new Column(name.trim(), enabled);
        } catch (Exception ignored) {
            return null;
        }
    }
}
