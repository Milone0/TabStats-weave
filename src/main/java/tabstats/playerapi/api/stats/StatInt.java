package tabstats.playerapi.api.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class StatInt extends Stat {
    private final int value;
    private final boolean loadedValue;

    /**
     * Reads the value right away. The JSON object is not kept, so a cached player does not hold on
     * to the whole API answer.
     *
     * @param statName Name of the Stat
     * @param jsonName Json Name of the Stat in Hypixel's API
     * @param source JsonObject of the desired Game Stat, may be null
     */
    public StatInt(String statName, String jsonName, JsonObject source) {
        super(statName);

        int parsed = 0;
        boolean loaded = false;
        JsonElement element = source == null ? null : source.get(jsonName);
        if (element != null && element.isJsonPrimitive()) {
            try {
                parsed = element.getAsInt();
                loaded = true;
            } catch (NumberFormatException ignored) {
                // not a number - leave it at 0
            }
        }

        this.value = parsed;
        this.loadedValue = loaded;
    }

    public int getValue() { return this.value; }

    /** Whether the API actually had this stat, as opposed to it defaulting to 0. */
    public boolean isLoadedValue() {
        return loadedValue;
    }

    @Override
    public StatType getType() {
        return StatType.INT;
    }
}
