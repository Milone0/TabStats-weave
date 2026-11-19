package tabstats.shout;

import tabstats.playerapi.HPlayer;
import tabstats.playerapi.api.stats.Stat;
import tabstats.util.StatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One player the way a shout reports them: team, name and the stats the tab shows, as plain text. */
public final class ShoutPlayer {
    private enum State { LOADING, NICKED, NO_STATS, OK }

    private final String name;
    private final String team;
    private final boolean self;
    private final State state;
    /** The shown columns as {@code KEY:value}, in tab order. */
    private final List<String> shown;
    /** Every stat that reads as a number, shown or not, so a rule can use a hidden column too. */
    private final Map<String, Double> numbers;
    private final boolean tagged;

    private ShoutPlayer(String name, String team, boolean self, State state, List<String> shown,
                        Map<String, Double> numbers, boolean tagged) {
        this.name = name;
        this.team = team;
        this.self = self;
        this.state = state;
        this.shown = shown;
        this.numbers = numbers;
        this.tagged = tagged;
    }

    /**
     * @param player   the cached stats, or null while they are still loading
     * @param nicked   known to be nicked even without stats - a chat reveal of a name nobody owns
     */
    public static ShoutPlayer of(String name, String team, boolean self, HPlayer player, String gamemode, boolean nicked) {
        if (nicked || (player != null && player.isNicked())) {
            return bare(name, team, self, State.NICKED);
        }

        if (player == null) {
            return bare(name, team, self, State.LOADING);
        }

        List<String> shown = new ArrayList<>();
        for (Stat stat : StatFormatting.configuredStats(player, gamemode)) {
            String value = ShoutText.plain(StatFormatting.valueOf(stat));
            if (value.isEmpty() || "-".equals(value)) {
                /* Nothing to say, like the empty cells of the tab: no tag, a hidden winstreak. */
                continue;
            }
            shown.add(StatFormatting.key(stat.getStatName()) + ":" + value);
        }

        Map<String, Double> numbers = new HashMap<>();
        boolean tagged = false;
        for (Stat stat : StatFormatting.rawStats(player, gamemode)) {
            String key = StatFormatting.key(stat.getStatName());
            String value = ShoutText.plain(StatFormatting.valueOf(stat));
            if ("TAG".equals(key)) {
                tagged = !value.isEmpty() && !"-".equals(value);
                continue;
            }

            Double number = ShoutText.number(value);
            if (number != null) {
                numbers.put(key, number);
            }
        }

        String shownName = player.getPlayerName() == null ? name : player.getPlayerName();
        State state = shown.isEmpty() ? State.NO_STATS : State.OK;
        return new ShoutPlayer(shownName, team, self, state, Collections.unmodifiableList(shown),
                Collections.unmodifiableMap(numbers), tagged);
    }

    /** A player looked up by name who could not be resolved to any stats. */
    public static ShoutPlayer unresolved(String name, boolean nicked) {
        return bare(name, null, false, nicked ? State.NICKED : State.NO_STATS);
    }

    private static ShoutPlayer bare(String name, String team, boolean self, State state) {
        return new ShoutPlayer(name, team, self, state, Collections.<String>emptyList(),
                Collections.<String, Double>emptyMap(), false);
    }

    public String getName() {
        return this.name;
    }

    /** The team name, or null outside a game. */
    public String getTeam() {
        return this.team;
    }

    public boolean isSelf() {
        return this.self;
    }

    public boolean isLoaded() {
        return this.state != State.LOADING;
    }

    /** Nicked or tagged by Urchin: worth a warning whatever the stats say. */
    public boolean isFlagged() {
        return this.state == State.NICKED || this.tagged;
    }

    public Map<String, Double> getNumbers() {
        return this.numbers;
    }

    /** The player as one entry of a chat message. */
    public String describe() {
        switch (this.state) {
            case LOADING:
                return this.name + " (loading)";
            case NICKED:
                return this.name + " (nick)";
            case NO_STATS:
                return this.name + " (no stats)";
            default:
                StringBuilder out = new StringBuilder(this.name);
                for (String stat : this.shown) {
                    out.append(' ').append(stat);
                }
                return out.toString();
        }
    }
}
