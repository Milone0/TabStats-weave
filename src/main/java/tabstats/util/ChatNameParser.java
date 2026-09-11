package tabstats.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls player names out of Hypixel chat lines.
 *
 * In a pre-game lobby Hypixel hides who is in the lobby: the tab list and the player entities
 * carry no usable identity, so the only moment a name becomes known is when that player writes
 * something. That, and only that, counts as a reveal: a join line names a player too but is
 * deliberately ignored, and party, guild and private messages are rejected because their
 * sender need not be in this lobby. The other lines reported here only retire names again -
 * a quit line drops one, the bar around a game summary drops all of them.
 */
public final class ChatNameParser {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    /** Rank tags, guild tags, SkyWars levels - anything the server wraps in brackets. */
    private static final Pattern BRACKET_GROUP = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern QUIT_LINE = Pattern.compile("^(.+?) has quit!$");
    /**
     * The bar Hypixel draws around a game's start and end summary. It is the one line that marks
     * the waiting phase as over, and a pre-game lobby does not always sit on its own world, so
     * this is what retires the reveals of the lobby that just turned into a game.
     */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^\u25AC{8,}$");
    /**
     * Single words that end up in front of a colon in server messages, never a player. This list
     * is what keeps a line like "Store: store.hypixel.net" from being looked up as a name: the
     * colour of the colon cannot tell the two apart, since Hypixel writes a rankless player as
     * "\u00A77Name\u00A77: message".
     */
    private static final Set<String> NON_PLAYER_LABELS = new HashSet<>(Arrays.asList(
            "store", "shop", "sale", "error", "warning", "note", "tip", "tips", "reward", "rewards",
            "info", "help", "alert", "news", "discord", "website", "forums", "link", "www", "http",
            "https", "hypixel", "map", "mode", "time", "team", "winner", "winners", "loser", "losers",
            "mvp", "vip", "youtube", "admin", "mod", "helper", "you", "click", "reason", "protip",
            "version", "server", "lobby", "game", "games", "mission", "objective", "quest", "event",
            "score", "scores", "kills", "deaths", "damage", "bed", "beds", "final", "coins", "exp",
            "level", "levels", "rank", "guild", "party", "friend", "friends", "cooldown", "challenge",
            "watchdog", "staff", "ban", "punishment", "next", "status", "players", "player", "target"
    ));

    private ChatNameParser() {
    }

    /**
     * @param formattedMessage the chat line as the server sent it, color codes included
     * @return what the line says about the lobby, or null when it says nothing
     */
    public static Reveal parse(String formattedMessage) {
        if (formattedMessage == null) {
            return null;
        }

        String stripped = ChatColor.stripColor(formattedMessage);
        if (stripped == null) {
            return null;
        }

        stripped = stripped.trim();
        if (stripped.isEmpty()) {
            return null;
        }

        if (SEPARATOR_LINE.matcher(stripped.replaceAll("\\s+", "")).matches()) {
            return new Reveal(Kind.CLEAR, null);
        }

        Matcher quit = QUIT_LINE.matcher(stripped);
        if (quit.matches()) {
            String name = extractName(quit.group(1));
            return name == null ? null : new Reveal(Kind.HIDE, name);
        }

        int colonIndex = stripped.indexOf(": ");
        if (colonIndex <= 0) {
            return null;
        }

        String prefix = stripped.substring(0, colonIndex);
        /* "Party > ", "Guild > ", "Co-op > ", "From ", "To " - senders that need not be in this lobby. */
        if (prefix.indexOf('>') >= 0 || prefix.startsWith("From ") || prefix.startsWith("To ")) {
            return null;
        }

        /*
         * Everything in front of the colon has to collapse to a single username - one token, no
         * server label - which is what extractName enforces.
         */
        String name = extractName(prefix);
        return name == null ? null : new Reveal(Kind.REVEAL, name);
    }

    /** Strips bracketed tags off a name segment and accepts what is left only if it is a lone username. */
    private static String extractName(String segment) {
        if (segment == null) {
            return null;
        }

        String cleaned = BRACKET_GROUP.matcher(segment).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        String[] tokens = cleaned.split("\\s+");
        if (tokens.length != 1) {
            return null;
        }

        String token = tokens[0];
        if (!USERNAME.matcher(token).matches()) {
            return null;
        }

        if (NON_PLAYER_LABELS.contains(token.toLowerCase(Locale.ROOT))) {
            return null;
        }

        return token;
    }

    public enum Kind {
        /** The player wrote something: show them. */
        REVEAL,
        /** The player left the lobby: drop that one name again. */
        HIDE,
        /** The waiting phase is over: drop every name this lobby revealed. */
        CLEAR
    }

    public static final class Reveal {
        private final Kind kind;
        private final String name;

        private Reveal(Kind kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        public Kind getKind() {
            return this.kind;
        }

        /** The name the line names, null for {@link Kind#CLEAR}. */
        public String getName() {
            return this.name;
        }
    }
}
