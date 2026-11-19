package tabstats.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts the full parties that joined the pre-game lobby, from its join and quit lines alone.
 *
 * <p>A pre-game lobby names nobody: every join line carries a made-up name,
 * {@code c8oxqbN5pHN has joined (13/16)!}. Two things still give a party away, both confirmed on
 * some 500 logged lobbies:
 * <ul>
 *   <li>Hypixel moves a party in as one, so its members' join lines arrive together. In Doubles
 *       two lines within the same second were the most common case by far, in 4v4v4v4 four.</li>
 *   <li>The made-up name stays the same for as long as the player is in that lobby - a quit line
 *       uses it, and so does a join line when the player comes back.</li>
 * </ul>
 * So join lines that follow each other within {@link #BURST_GAP_MS} make up one group, and a group
 * holds as many full parties as the team size fits into it. Each group keeps track of who of it is
 * still there, and only counts the parties it still has the players for.
 *
 * <p>The group the player themselves arrived in is their own party and never counted. Players who
 * were already in the lobby before the player came are not announced and cannot be counted
 * either. Everything here runs on the client thread.
 */
public final class LobbyParties {
    /**
     * Members of one party arrive within this of each other; a stranger rarely does. In the logs,
     * two join lines one second apart were no more common than two or five seconds apart - a party
     * is never spread that far - while the same second was eight times as common.
     */
    static final long BURST_GAP_MS = 500L;

    /** Made-up names are letters and digits like real ones, which keeps "Hans: has joined ..." out. */
    private static final Pattern JOIN = Pattern.compile("^([A-Za-z0-9_]{1,16}) has joined \\(\\d+/\\d+\\)!$");
    private static final Pattern QUIT = Pattern.compile("^([A-Za-z0-9_]{1,16}) has quit!$");

    private static final LobbyParties INSTANCE = new LobbyParties();

    /** Join lines that may still be joined by more of the same party. */
    private final List<String> burst = new ArrayList<>();
    private long lastJoinAt;
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Group> groupOf = new HashMap<>();
    /** Whether join lines have been seen since the last reset, which means a pre-game lobby. */
    private boolean waiting;
    /** The world the counts belong to. */
    private Object world;

    private static final class Group {
        private final int size;
        private final Set<String> present = new HashSet<>();

        private Group(List<String> members) {
            this.size = members.size();
            this.present.addAll(members);
        }

        /** As many full parties as fit into the group, and as are still all there. */
        private int fullParties(int teamSize) {
            return Math.min(this.size / teamSize, this.present.size() / teamSize);
        }
    }

    public static LobbyParties get() {
        return INSTANCE;
    }

    /**
     * Starts over when the client has changed worlds since the last call. Call before every
     * {@link #offer} and every read. The world change is noticed here rather than in a tick: the
     * player's own join line arrives in the same breath as the new world, before any tick has run.
     */
    public void enterWorld(Object currentWorld) {
        if (currentWorld != this.world) {
            reset();
            this.world = currentWorld;
        }
    }

    /** The start of the game, or a new world: the lobby these parties were in is gone. */
    public void reset() {
        this.burst.clear();
        this.groups.clear();
        this.groupOf.clear();
        this.lastJoinAt = 0L;
        this.waiting = false;
    }

    /**
     * Offers a chat line, colours stripped.
     *
     * @param selfName the player's own name - the line naming it belongs to the player's own party
     */
    public void offer(String strippedLine, String selfName, long now) {
        if (strippedLine == null) {
            return;
        }

        String line = strippedLine.trim();
        Matcher join = JOIN.matcher(line);
        if (join.matches()) {
            onJoin(join.group(1), selfName, now);
            return;
        }

        Matcher quit = QUIT.matcher(line);
        if (quit.matches()) {
            onQuit(quit.group(1), selfName, now);
        }
    }

    private void onJoin(String name, String selfName, long now) {
        this.waiting = true;
        closeBurstIfOver(selfName, now);

        String key = key(name);
        Group group = this.groupOf.get(key);
        if (group != null) {
            /* Back again under the same made-up name: they still belong to their party. */
            group.present.add(key);
            return;
        }

        this.burst.add(key);
        this.lastJoinAt = now;
    }

    private void onQuit(String name, String selfName, long now) {
        closeBurstIfOver(selfName, now);

        String key = key(name);
        if (this.burst.remove(key)) {
            return;
        }

        Group group = this.groupOf.get(key);
        if (group != null) {
            group.present.remove(key);
        }
    }

    /**
     * The full parties that joined after the player and are all still here.
     *
     * @param teamSize the players per team; below 2 there are no parties to count
     */
    public int fullParties(int teamSize, String selfName, long now) {
        if (teamSize < 2) {
            return 0;
        }

        closeBurstIfOver(selfName, now);
        int count = 0;
        for (Group group : this.groups) {
            count += group.fullParties(teamSize);
        }
        return count;
    }

    /** Whether the client sits in a pre-game lobby that has seen players join. */
    public boolean isWaiting() {
        return this.waiting;
    }

    private void closeBurstIfOver(String selfName, long now) {
        if (this.burst.isEmpty() || now - this.lastJoinAt <= BURST_GAP_MS) {
            return;
        }

        List<String> members = new ArrayList<>(this.burst);
        this.burst.clear();

        /* The player's own party arrives together with the player - not someone to warn about. */
        if (selfName != null && members.contains(key(selfName))) {
            return;
        }

        Group group = new Group(members);
        this.groups.add(group);
        for (String member : members) {
            this.groupOf.put(member, group);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
