package tabstats.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoutSettingsTest {

    private static Map<String, Double> stats(Object... pairs) {
        Map<String, Double> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return values;
    }

    private static ShoutSettings withRules() {
        ShoutSettings settings = new ShoutSettings();
        settings.setMinimum("STAR", 400.0);
        settings.setMinimum("FKDR", 3.0);
        return settings;
    }

    @Test
    void anyRuleIsEnoughByDefault() {
        ShoutSettings settings = withRules();
        assertTrue(settings.isGood(stats("STAR", 500, "FKDR", 1), false));
        assertTrue(settings.isGood(stats("STAR", 100, "FKDR", 3), false));
        assertFalse(settings.isGood(stats("STAR", 100, "FKDR", 2.99), false));
    }

    @Test
    void matchAllNeedsEveryRule() {
        ShoutSettings settings = withRules();
        settings.setMatchAll(true);
        assertFalse(settings.isGood(stats("STAR", 500, "FKDR", 1), false));
        assertTrue(settings.isGood(stats("STAR", 500, "FKDR", 4), false));
        assertFalse(settings.isGood(stats("STAR", 500), false), "a missing stat does not meet its rule");
    }

    @Test
    void flaggedPlayersPassUnlessThatIsSwitchedOff() {
        ShoutSettings settings = withRules();
        assertTrue(settings.isGood(stats(), true));
        settings.setAlwaysShowFlagged(false);
        assertFalse(settings.isGood(stats(), true));
    }

    @Test
    void withoutRulesEveryoneIsGood() {
        ShoutSettings settings = new ShoutSettings();
        for (String stat : ShoutSettings.RULE_STATS) {
            settings.setMinimum(stat, null);
        }
        assertTrue(settings.isGood(stats(), false));
    }

    @Test
    void roundTripsThroughJson() {
        ShoutSettings original = new ShoutSettings();
        original.setAutoShout(true);
        original.setMatchAll(true);
        original.setMinimum("STAR", null);
        original.setMinimum("WLR", 2.5);

        Gson gson = new Gson();
        JsonObject json = gson.toJsonTree(original.toSerializable()).getAsJsonObject();
        ShoutSettings loaded = new ShoutSettings();
        loaded.loadFrom(json);

        assertTrue(loaded.isAutoShout());
        assertTrue(loaded.isMatchAll());
        assertNull(loaded.getMinimum("STAR"), "a removed rule stays removed");
        assertEquals(2.5, loaded.getMinimum("wlr"));
        assertEquals(3.0, loaded.getMinimum("FKDR"));
    }

    @Test
    void aMissingSectionKeepsTheDefaults() {
        ShoutSettings settings = new ShoutSettings();
        settings.loadFrom(null);
        assertFalse(settings.isAutoShout());
        assertEquals(400.0, settings.getMinimum("STAR"));
    }
}
