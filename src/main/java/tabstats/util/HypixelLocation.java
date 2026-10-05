package tabstats.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Where on Hypixel the client currently is, asked from the server itself with {@code /locraw}.
 *
 * <p>The scoreboard title alone cannot answer this: a game lobby titles its sidebar with the game
 * name just like the game does, so "BED WARS" is shown both in the lobby full of 80 players and in
 * the 16-player match. {@code /locraw} separates them - a lobby answers with a {@code lobbyname}
 * (or a {@code mode} of {@code LOBBY}) and no map, a real game or its pre-game lobby answers with
 * the mode and map it is running.
 *
 * <p>One request goes out per world the client joins, retried a few times if the answer is lost.
 * If Hypixel never answers, the mod falls back to the scoreboard alone rather than staying dark.
 *
 * <p>Every request sent is counted until its answer comes back, so that each one is hidden from
 * the chat - also an answer that is merely late and arrives after a retry already went out, or
 * after the world it was asked from has been left.
 */
public final class HypixelLocation {
    /** How long to wait for an answer before asking again. */
    private static final long RETRY_AFTER_MS = 3_000L;
    /** The server ignores commands sent in the same breath as the world change. */
    private static final long JOIN_GRACE_MS = 1_000L;
    private static final int MAX_ATTEMPTS = 3;
    /** How long a request can still be answered; anything later was asked by someone else. */
    private static final long ANSWER_WINDOW_MS = 10_000L;

    private static volatile String gametype;
    private static volatile String mode;
    private static volatile String map;
    private static volatile String lobbyName;
    private static volatile String server;
    private static volatile boolean resolved;
    private static volatile int attempts;
    private static volatile long lastRequest;
    private static volatile long worldJoinTime;
    /** Requests asked from the current world whose answer has not come back yet. */
    private static volatile int pending;
    /** Requests asked from a world already left: their answers are hidden, but describe the old server. */
    private static volatile int stale;
    /** When the last request went out. Unlike {@link #lastRequest}, it survives a world change. */
    private static volatile long lastSent;

    private HypixelLocation() {
    }

    /** A new world means a new server: everything known about the old one is void. */
    public static void reset() {
        gametype = null;
        mode = null;
        map = null;
        lobbyName = null;
        server = null;
        resolved = false;
        attempts = 0;
        lastRequest = 0L;
        worldJoinTime = System.currentTimeMillis();
        stale += pending;
        pending = 0;
    }

    /** Sends {@code /locraw} when an answer is due. Call from the client tick. */
    public static void tick() {
        if (resolved || attempts >= MAX_ATTEMPTS) {
            return;
        }

        /*
         * Only worth asking where the answer could change anything: the sidebar already has to
         * name a supported game before the mod does anything at all. That also keeps the command
         * off servers other than Hypixel, which would answer it with an error in chat.
         */
        if (Gamemodes.resolveCurrent() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - worldJoinTime < JOIN_GRACE_MS) {
            return;
        }

        if (lastRequest != 0L && now - lastRequest < RETRY_AFTER_MS) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }

        lastRequest = now;
        lastSent = now;
        attempts++;
        pending++;
        mc.thePlayer.sendChatMessage("/locraw");
    }

    /**
     * Offers a chat line to the tracker.
     *
     * @return true when the line was the answer to our own request and should not reach the chat.
     */
    public static boolean handleChatMessage(String rawMessage) {
        if (rawMessage == null || !expectingAnswer()) {
            return false;
        }

        String text = ChatColor.stripColor(rawMessage);
        if (text == null) {
            return false;
        }

        text = text.trim();
        if (!text.startsWith("{") || !text.endsWith("}") || !text.contains("\"server\"")) {
            return false;
        }

        JsonObject json;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) {
                return false;
            }
            json = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            return false;
        }

        /* Answers come back in the order they were asked, so the old world's ones are first. */
        if (stale > 0) {
            stale--;
            return true;
        }

        /* A late answer to an earlier try still describes this same server, so it may overwrite. */
        pending--;
        server = asString(json, "server");
        gametype = asString(json, "gametype");
        mode = asString(json, "mode");
        map = asString(json, "map");
        lobbyName = asString(json, "lobbyname");
        resolved = true;
        return true;
    }

    /**
     * Whether the client is in a game or the pre-game lobby of one - the only places the mod has
     * stats to show. False in every Hypixel lobby, and while the answer is still outstanding.
     */
    public static boolean inGame() {
        if (!resolved) {
            /*
             * No answer yet. Stay out of the way while the request is still in flight; once
             * Hypixel has ignored us MAX_ATTEMPTS times, hand the decision back to the scoreboard
             * so a broken /locraw disables the mod's stats rather than the mod itself.
             */
            return gaveUp();
        }

        if (lobbyName != null) {
            return false;
        }

        if (gametype == null) {
            // Limbo and the like: the answer carries nothing but a server name.
            return false;
        }

        String normalizedGametype = gametype.toUpperCase(Locale.ROOT);
        if ("MAIN".equals(normalizedGametype) || "LIMBO".equals(normalizedGametype)) {
            return false;
        }

        /*
         * A game lobby is given away by its lobbyname above; some also report a mode of LOBBY.
         * Everything left is a mini/mega server, which is a game or the pre-game lobby of one -
         * and the pre-game lobby is deliberately not required to name a mode or map yet.
         */
        return !"LOBBY".equalsIgnoreCase(mode);
    }

    /** True once Hypixel has been asked {@value #MAX_ATTEMPTS} times without ever answering. */
    public static boolean gaveUp() {
        return !resolved
                && attempts >= MAX_ATTEMPTS
                && System.currentTimeMillis() - lastRequest >= RETRY_AFTER_MS;
    }

    /** A short summary of the current location, for the debug output of {@code /tabstats}. */
    public static String describe() {
        if (!resolved) {
            return gaveUp() ? "unknown (no answer after " + attempts + " tries)" : "asking... (try " + attempts + ")";
        }

        return "server=" + server + " gametype=" + gametype + " mode=" + mode + " map=" + map
                + " lobbyname=" + lobbyName + " -> " + (inGame() ? "game" : "lobby");
    }

    /** Whether a request of the mod is still waiting for its answer. */
    private static boolean expectingAnswer() {
        if (pending <= 0 && stale <= 0) {
            return false;
        }

        if (System.currentTimeMillis() - lastSent > ANSWER_WINDOW_MS) {
            // Lost on the way: a /locraw answered now was typed by the player or another mod.
            pending = 0;
            stale = 0;
            return false;
        }

        return true;
    }

    private static String asString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }

        String value = element.getAsString();
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
