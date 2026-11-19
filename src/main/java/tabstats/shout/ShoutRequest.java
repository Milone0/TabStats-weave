package tabstats.shout;

import tabstats.util.PlayerNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one {@code /bw shout} asks for, read from the words after "shout".
 *
 * <pre>
 *   /bw shout              the good players of every team (see ShoutSettings)
 *   /bw shout all          everyone in the game - "." does the same
 *   /bw shout red blue     everyone in those teams
 *   /bw shout Hans Klaus   those players
 * </pre>
 *
 * Teams and names can be mixed. A word that names a team is read as the team, so a player called
 * "Red" has to be looked up with the plain {@code /bw}.
 */
public final class ShoutRequest {
    private final boolean everyone;
    private final Set<String> teams;
    private final List<String> names;
    private final String error;

    private ShoutRequest(boolean everyone, Set<String> teams, List<String> names, String error) {
        this.everyone = everyone;
        this.teams = teams;
        this.names = names;
        this.error = error;
    }

    public static ShoutRequest parse(String[] words) {
        boolean everyone = false;
        Set<String> teams = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();

        if (words != null) {
            for (String raw : words) {
                String word = raw == null ? "" : raw.trim();
                if (word.isEmpty()) {
                    continue;
                }

                if (".".equals(word) || "all".equalsIgnoreCase(word)) {
                    everyone = true;
                    continue;
                }

                String team = ShoutText.teamFromWord(word);
                if (team != null) {
                    teams.add(team);
                    continue;
                }

                if (!PlayerNames.isValid(word)) {
                    return new ShoutRequest(false, Collections.<String>emptySet(), Collections.<String>emptyList(),
                            "\"" + word + "\" is neither a team nor a player name.");
                }

                if (!containsIgnoreCase(names, word)) {
                    names.add(word);
                }
            }
        }

        return new ShoutRequest(everyone, Collections.unmodifiableSet(teams), Collections.unmodifiableList(names), null);
    }

    /**
     * The words after {@code /shout /bw shout}, or null when the message is something else - the
     * form that sends the result to everyone in the game rather than to the current chat.
     */
    public static String[] fromShoutCommand(String message) {
        if (message == null) {
            return null;
        }

        String[] words = message.trim().split("\\s+");
        if (words.length < 3) {
            return null;
        }

        if (!"/shout".equalsIgnoreCase(words[0]) || !"/bw".equalsIgnoreCase(words[1])
                || !"shout".equals(words[2].toLowerCase(Locale.ROOT))) {
            return null;
        }

        String[] rest = new String[words.length - 3];
        System.arraycopy(words, 3, rest, 0, rest.length);
        return rest;
    }

    /** Everyone in the game, no filter. */
    public boolean isEveryone() {
        return this.everyone;
    }

    /** Nothing named at all: the good players of every team. */
    public boolean isGoodOnly() {
        return !this.everyone && this.teams.isEmpty() && this.names.isEmpty();
    }

    public Set<String> getTeams() {
        return this.teams;
    }

    public List<String> getNames() {
        return this.names;
    }

    /** Why the words could not be read, or null when they could. */
    public String getError() {
        return this.error;
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
