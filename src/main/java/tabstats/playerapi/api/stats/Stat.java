package tabstats.playerapi.api.stats;

public abstract class Stat {
    protected final String statName;

    protected Stat(String statName) {
        this.statName = statName;
    }

    public String getStatName() { return this.statName; }

    public abstract StatType getType();
}
