package tabstats.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatColumnLayoutTest {
    private static final List<String> BEDWARS_DEFAULTS =
            Arrays.asList("TAG", "STAR", "WS", "FKDR", "FINALS", "WLR", "WINS", "BBLR");

    private static List<String> keys(List<StatColumnLayout.Column> columns) {
        List<String> keys = new ArrayList<>();
        for (StatColumnLayout.Column column : columns) {
            keys.add(column.getKey());
        }
        return keys;
    }

    private static List<String> names(List<Stat> stats) {
        List<String> names = new ArrayList<>();
        for (Stat stat : stats) {
            names.add(stat.getStatName());
        }
        return names;
    }

    private static List<Stat> stats(String... names) {
        List<Stat> stats = new ArrayList<>();
        for (String name : names) {
            stats.add(new StatString(name, "1"));
        }
        return stats;
    }

    private static JsonObject json(String text) {
        return new JsonParser().parse(text).getAsJsonObject();
    }

    @Test
    void aFreshLayoutShowsEveryDefaultColumn() {
        StatColumnLayout layout = new StatColumnLayout();

        List<StatColumnLayout.Column> columns = layout.getColumns("BEDWARS");
        assertEquals(BEDWARS_DEFAULTS, keys(columns));
        for (StatColumnLayout.Column column : columns) {
            assertTrue(column.isEnabled(), column.getKey());
        }
    }

    @Test
    void theGamemodeIsMatchedWhateverItsSpelling() {
        StatColumnLayout layout = new StatColumnLayout();

        assertSame(layout.getColumns("BEDWARS"), layout.getColumns(" bedwars "));
    }

    @Test
    void movingAndTogglingStayInsideTheList() {
        StatColumnLayout layout = new StatColumnLayout();

        assertFalse(layout.moveUp("BEDWARS", 0));
        assertFalse(layout.moveDown("BEDWARS", BEDWARS_DEFAULTS.size() - 1));
        assertFalse(layout.toggle("BEDWARS", -1));
        assertFalse(layout.toggle("BEDWARS", BEDWARS_DEFAULTS.size()));
        assertEquals(BEDWARS_DEFAULTS, keys(layout.getColumns("BEDWARS")));

        assertTrue(layout.moveUp("BEDWARS", 1));
        assertTrue(layout.moveDown("BEDWARS", 2));
        assertEquals(Arrays.asList("STAR", "TAG", "FKDR", "WS", "FINALS", "WLR", "WINS", "BBLR"),
                keys(layout.getColumns("BEDWARS")));

        assertTrue(layout.toggle("BEDWARS", 0));
        assertFalse(layout.getColumns("BEDWARS").get(0).isEnabled());
    }

    @Test
    void applyOrdersFiltersAndKeepsUnknownStats() {
        StatColumnLayout layout = new StatColumnLayout();
        layout.moveUp("BEDWARS", 2);  // WS in front of STAR
        layout.toggle("BEDWARS", 3);  // FKDR hidden

        List<Stat> applied = layout.apply(stats("STAR", "FKDR", "ws", "EXTRA"), "BEDWARS");

        assertEquals(Arrays.asList("ws", "STAR", "EXTRA"), names(applied));
    }

    @Test
    void applyLeavesAnEmptyListAlone() {
        StatColumnLayout layout = new StatColumnLayout();

        assertTrue(layout.apply(null, "BEDWARS").isEmpty());
        assertTrue(layout.apply(new ArrayList<Stat>(), "BEDWARS").isEmpty());
    }

    @Test
    void resetPutsTheDefaultsBack() {
        StatColumnLayout layout = new StatColumnLayout();
        layout.moveUp("BEDWARS", 3);
        layout.toggle("BEDWARS", 0);

        layout.resetToDefaults("BEDWARS");

        assertEquals(BEDWARS_DEFAULTS, keys(layout.getColumns("BEDWARS")));
        assertTrue(layout.getColumns("BEDWARS").get(0).isEnabled());
    }

    @Test
    void aStoredLayoutKeepsItsOrderAndGainsNewDefaults() {
        StatColumnLayout layout = new StatColumnLayout();
        layout.loadFrom(json("{\"bedwars\":["
                + "{\"name\":\"WINS\",\"enabled\":false},"
                + "\"fkdr\","
                + "{\"name\":\"wins\"},"
                + "{\"enabled\":true},"
                + "{\"name\":\"  \"},"
                + "null,"
                + "[]"
                + "]}"));

        List<StatColumnLayout.Column> columns = layout.getColumns("BEDWARS");
        assertEquals(Arrays.asList("WINS", "FKDR", "TAG", "STAR", "WS", "FINALS", "WLR", "BBLR"), keys(columns));
        assertFalse(columns.get(0).isEnabled());
        assertTrue(columns.get(1).isEnabled(), "a bare string counts as shown");
    }

    @Test
    void whatIsSavedLoadsBackUnchanged() {
        StatColumnLayout saved = new StatColumnLayout();
        saved.moveDown("DUELS", 0);
        saved.toggle("DUELS", 2);
        saved.toggle("SKYWARS", 4);

        StatColumnLayout loaded = new StatColumnLayout();
        loaded.loadFrom(new Gson().toJsonTree(saved.toSerializable()).getAsJsonObject());

        for (String gamemode : StatColumnLayout.GAMEMODES) {
            List<StatColumnLayout.Column> before = saved.getColumns(gamemode);
            List<StatColumnLayout.Column> after = loaded.getColumns(gamemode);
            assertEquals(keys(before), keys(after), gamemode);
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i).isEnabled(), after.get(i).isEnabled(), gamemode + " " + before.get(i).getKey());
            }
        }
    }

    @Test
    void aMissingLayoutFallsBackToTheDefaults() {
        StatColumnLayout layout = new StatColumnLayout();
        layout.moveUp("BEDWARS", 1);

        layout.loadFrom(null);

        assertEquals(BEDWARS_DEFAULTS, keys(layout.getColumns("BEDWARS")));
    }
}
