package tabstats.shout;

import tabstats.util.ChatColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns tab list content into text a player is allowed to send, and cuts it into chat messages.
 *
 * <p>Two limits shape everything here. A 1.8.9 client sends at most 100 characters per message,
 * and a player may not send the section sign at all - the server kicks for it - so colours are
 * dropped. Anything outside plain ASCII goes too, the star glyphs among it, since Hypixel filters
 * a good part of it anyway.
 */
public final class ShoutText {
    /** What a 1.8.9 client sends at most in one chat message. */
    public static final int MAX_MESSAGE_LENGTH = 100;

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TEAM_LETTER = Pattern.compile("[A-Z]");

    private static final String PLAYER_SEPARATOR = " | ";

    /** Colour code to team name, for the colours Hypixel gives its teams. */
    private static final Map<Character, String> TEAM_NAMES = new LinkedHashMap<>();
    /** Every word a team can be asked for by, lower-case, to its name. */
    private static final Map<String, String> TEAM_WORDS = new LinkedHashMap<>();

    static {
        team('c', "Red");
        team('9', "Blue");
        team('a', "Green");
        team('e', "Yellow");
        team('b', "Aqua");
        team('f', "White");
        team('d', "Pink");
        /* Which grey the Gray team is drawn in is not certain, so both count. */
        team('8', "Gray");
        team('7', "Gray");
        team('6', "Orange");
        team('2', "DarkGreen");
        team('5', "Purple");
        team('1', "DarkBlue");
        team('3', "Cyan");
        team('4', "DarkRed");
        team('0', "Black");
        TEAM_WORDS.put("grey", "Gray");
    }

    private static void team(char code, String name) {
        TEAM_NAMES.put(code, name);
        TEAM_WORDS.put(name.toLowerCase(Locale.ROOT), name);
    }

    private ShoutText() {
    }

    /** Colours and anything that is not printable ASCII removed, whitespace collapsed. */
    public static String plain(String formatted) {
        String stripped = ChatColor.stripColor(formatted);
        if (stripped == null) {
            return "";
        }

        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == ChatColor.COLOR_CHAR) {
                /* A stray section sign the colour pattern did not match, plus its code. */
                i++;
                continue;
            }
            out.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }

        return WHITESPACE.matcher(out).replaceAll(" ").trim();
    }

    /** The first number in a plain value - "412" in "412", "3.45" in "3.45" - or null. */
    public static Double number(String plain) {
        if (plain == null) {
            return null;
        }

        Matcher matcher = NUMBER.matcher(plain);
        if (!matcher.find()) {
            return null;
        }

        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The team a tab entry belongs to, read off its team prefix - or null when it has none.
     *
     * <p>In a game Hypixel prefixes each player with the bold initial of their team in the team
     * colour, {@code §c§lR §c}. Ranks are prefixes as well, {@code §b[MVP§c+§b] }, so only a prefix
     * that is a lone capital letter counts as a team; its first colour is the team colour.
     */
    public static String teamFromPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }

        String stripped = ChatColor.stripColor(prefix);
        if (stripped == null || !TEAM_LETTER.matcher(stripped.trim()).matches()) {
            return null;
        }

        for (int i = 0; i + 1 < prefix.length(); i++) {
            if (prefix.charAt(i) == ChatColor.COLOR_CHAR) {
                String name = TEAM_NAMES.get(Character.toLowerCase(prefix.charAt(i + 1)));
                if (name != null) {
                    return name;
                }
            }
        }

        return null;
    }

    /** The team a word asks for - "red", "Blue", "grey" - or null when it names no team. */
    public static String teamFromWord(String word) {
        return word == null ? null : TEAM_WORDS.get(word.trim().toLowerCase(Locale.ROOT));
    }

    /** Every team word, for tab completion. */
    public static List<String> teamWords() {
        return Collections.unmodifiableList(new ArrayList<>(TEAM_WORDS.keySet()));
    }

    /**
     * One player as one chat message, {@code "Red: Hans STAR:412 FKDR:3.45"}, cut short when it
     * would not fit.
     *
     * @param team the team label, or null for a player without a team
     */
    public static String line(String team, String player, int limit) {
        String head = team == null ? "" : team + ": ";
        return head + cut(player, limit - head.length());
    }

    /**
     * One team's players packed into as few chat messages as possible: {@code "Red: Hans STAR:412 FKDR:3.45 | Klaus ..."}.
     * Everything goes into one message while it fits; the rest continues in further messages that
     * repeat the team label. A single player too long for a message on their own is cut short.
     *
     * @param team  the team label, or null for players without a team
     * @param limit the most characters one message may have
     */
    public static List<String> pack(String team, List<String> players, int limit) {
        List<String> messages = new ArrayList<>();
        if (players == null || players.isEmpty()) {
            return messages;
        }

        String head = team == null ? "" : team + ": ";
        StringBuilder current = new StringBuilder();
        for (String player : players) {
            String entry = cut(player, limit - head.length());
            if (current.length() > 0 && current.length() + PLAYER_SEPARATOR.length() + entry.length() <= limit) {
                current.append(PLAYER_SEPARATOR).append(entry);
                continue;
            }

            if (current.length() > 0) {
                messages.add(current.toString());
            }
            current.setLength(0);
            current.append(head).append(entry);
        }

        messages.add(current.toString());
        return messages;
    }

    /**
     * Joins consecutive messages with {@code separator} for as long as they fit into one, for
     * when only very few messages may be sent at all.
     */
    public static List<String> merge(List<String> messages, String separator, int limit) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String message : messages) {
            if (current.length() > 0 && current.length() + separator.length() + message.length() <= limit) {
                current.append(separator).append(message);
                continue;
            }

            if (current.length() > 0) {
                merged.add(current.toString());
            }
            current.setLength(0);
            current.append(cut(message, limit));
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }
        return merged;
    }

    private static String cut(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit)).trim();
    }
}
