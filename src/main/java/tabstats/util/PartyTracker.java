package tabstats.util;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is in the client party, asked from the server itself with {@code /party list}.
 *
 * <p>There is no other source for it: a party is not on the scoreboard and not in the tab list,
 * so the only way to learn the members is to run the command and read the answer back out of
 * chat. The answer is swallowed while a request of the mod is in flight, the same way
 * {@link HypixelLocation} handles {@code /locraw}, so asking never spams the chat.
 *
 * <p>Everything here runs on the client thread: the command that asks, the chat line that
 * answers, and the tick that gives up when no answer ever arrives.
 */
public final class PartyTracker {
    /** Called once per request, with either the members or a reason there are none. */
    public interface Callback {
        void onParty(List<String> members, String error);
    }

    /** Hypixel answers within a tick or two; well before this the request is lost. */
    private static final long TIMEOUT_MS = 6_000L;

    private static final Pattern RANK_TAG = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern MEMBER_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final List<Callback> WAITING = new ArrayList<>();
    private static final List<String> MEMBERS = new ArrayList<>();

    private static long requestedAt;
    private static boolean capturing;

    private PartyTracker() {
    }

    /**
     * Asks the server for the party. A request already on its way is shared rather than sent
     * twice, so two commands in the same breath still only cost one {@code /party list}.
     */
    public static void request(Callback callback) {
        if (callback == null) {
            return;
        }

        WAITING.add(callback);
        if (requestedAt != 0L) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            finish(null, "You are not in a world.");
            return;
        }

        MEMBERS.clear();
        capturing = false;
        requestedAt = System.currentTimeMillis();
        mc.thePlayer.sendChatMessage("/party list");
    }

    /**
     * Offers a chat line to the tracker.
     *
     * @return true when the line was part of the answer to our own request and should not reach
     *         the chat.
     */
    public static boolean handleChatMessage(String rawMessage) {
        if (requestedAt == 0L || rawMessage == null) {
            return false;
        }

        String text = ChatColor.stripColor(rawMessage);
        if (text == null) {
            return false;
        }

        text = text.trim();

        if (isNotInParty(text)) {
            finish(null, "You are not in a party.");
            return true;
        }

        if (isDivider(text)) {
            if (capturing) {
                finish(new ArrayList<>(MEMBERS), null);
            } else {
                capturing = true;
            }
            return true;
        }

        if (isMemberLine(text)) {
            capturing = true;
            collect(text);
            return true;
        }

        /* The headline and the blank line in the middle of the block, but nothing else. */
        return capturing && (text.isEmpty() || text.startsWith("Party "));
    }

    /** Gives up on an answer that never came. Call from the client tick. */
    public static void tick() {
        if (requestedAt == 0L || System.currentTimeMillis() - requestedAt < TIMEOUT_MS) {
            return;
        }

        if (MEMBERS.isEmpty()) {
            finish(null, "No answer to /party list - are you on Hypixel?");
        } else {
            finish(new ArrayList<>(MEMBERS), null);
        }
    }

    private static void finish(List<String> members, String error) {
        List<Callback> callbacks = new ArrayList<>(WAITING);
        WAITING.clear();
        MEMBERS.clear();
        capturing = false;
        requestedAt = 0L;

        List<String> result = members == null ? new ArrayList<>() : members;
        for (Callback callback : callbacks) {
            callback.onParty(result, error);
        }
    }

    /**
     * Pulls the names out of a {@code Party Leader:} / {@code Party Members:} line. Rank tags are
     * dropped first so that a {@code [MVP+]} cannot be mistaken for a name, and what is left of
     * the line past the colon is scanned for anything shaped like a player name - which skips the
     * bullet that Hypixel puts behind every member.
     */
    private static void collect(String text) {
        int colon = text.indexOf(':');
        String tail = colon < 0 ? text : text.substring(colon + 1);
        tail = RANK_TAG.matcher(tail).replaceAll(" ");

        Matcher matcher = MEMBER_NAME.matcher(tail);
        while (matcher.find()) {
            String name = matcher.group();
            if (!contains(MEMBERS, name)) {
                MEMBERS.add(name);
            }
        }
    }

    private static boolean isMemberLine(String text) {
        if (!text.startsWith("Party ") || text.indexOf(':') < 0) {
            return false;
        }

        return text.startsWith("Party Leader:")
                || text.startsWith("Party Moderators:")
                || text.startsWith("Party Members:");
    }

    private static boolean isNotInParty(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("not currently in a party") || lower.contains("you are not in a party");
    }

    /** The line of dashes Hypixel draws around the party list. */
    private static boolean isDivider(String text) {
        String compact = text.replace(" ", "");
        if (compact.length() < 5) {
            return false;
        }

        for (int i = 0; i < compact.length(); i++) {
            if (compact.charAt(i) != '-') {
                return false;
            }
        }

        return true;
    }

    private static boolean contains(List<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }
}
