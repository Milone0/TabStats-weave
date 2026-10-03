package tabstats.playerapi.api.stats;

public class StatString extends Stat {
    /** Volatile: the Urchin TAG is filled in by a worker thread while the tab draws it. */
    private volatile String value;

    public StatString(String statName, String value) {
        super(statName);
        this.value = value;
    }

    public void setValue(String value) { this.value = value; }

    public String getValue() { return this.value; }

    @Override
    public StatType getType() {
        return StatType.STRING;
    }
}
