package tabstats.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C01PacketChatMessage;

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
 * <p>Every answer is hidden from the chat, whoever asked and however late it comes - the only
 * exception is a {@code /locraw} the player typed in, whose answer is what they want to see.
 */
public final class HypixelLocation {
    /** How long to wait for an answer before asking again. */
    private static final long RETRY_AFTER_MS = 3_000L;
    /** The server ignores commands sent in the same breath as the world change. */
    private static final long JOIN_GRACE_MS = 1_000L;
    private static final int MAX_ATTEMPTS = 3;
    /** How long a {@code /locraw} typed by the player waits for the answer it gets to see. */
    private static final long TYPED_ANSWER_WINDOW_MS = 5_000L;

    private static volatile String gametype;
    private static volatile String mode;
    private static volatile String map;
    private static volatile String lobbyName;
    private static volatile String server;
    private static volatile boolean resolved;
    private static volatile int attempts;
    private static volatile long lastRequest;
    private static volatile long worldJoinTime;
    /** {@code /locraw} commands the player typed whose answer has not been shown yet. */
    private static volatile int typedRequests;
    private static volatile long lastTypedRequest;

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
        attempts++;
        /*
         * Straight to the connection rather than through sendChatMessage: that one fires the
         * ChatEvent.Sent of a command typed by the player, and this request must not pass for one.
         */
        mc.thePlayer.sendQueue.addToSendQueue(new C01PacketChatMessage("/locraw"));
    }

    /** Notes a {@code /locraw} typed by the player, so that its answer is let through to the chat. */
    public static void handleSentMessage(String message) {
        if (message == null) {
            return;
        }

        String command = message.trim();
        if (!command.equalsIgnoreCase("/locraw")) {
            return;
        }

        long now = System.currentTimeMillis();
        typedRequests = now - lastTypedRequest > TYPED_ANSWER_WINDOW_MS ? 1 : typedRequests + 1;
        lastTypedRequest = now;
    }

    /**
     * Offers a chat line to the tracker.
     *
     * @return true when the line is an answer to {@code /locraw} that the player did not ask for,
     *         and should not reach the chat.
     */
    public static boolean handleChatMessage(String rawMessage) {
        JsonObject json = parseAnswer(rawMessage);
        if (json == null) {
            return false;
        }

        /*
         * Until this world has been asked, an answer still belongs to the world just left. Any
         * answer after that - a late one, a retry's, or one the player asked for - describes the
         * server the client is on now, so it may overwrite what an earlier one said.
         */
        if (lastRequest != 0L) {
            server = asString(json, "server");
            gametype = asString(json, "gametype");
            mode = asString(json, "mode");
            map = asString(json, "map");
            lobbyName = asString(json, "lobbyname");
            resolved = true;
        }

        return !claimTypedRequest();
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

    /**
     * The chat line as a {@code /locraw} answer: a JSON object naming the server, and nothing
     * else on the line. Which other fields it carries is left open on purpose, so that a field
     * Hypixel adds one day cannot make the answer show up in the chat again.
     */
    private static JsonObject parseAnswer(String rawMessage) {
        String text = ChatColor.stripColor(rawMessage);
        if (text == null) {
            return null;
        }

        text = text.trim();
        if (!text.startsWith("{") || !text.endsWith("}") || !text.contains("\"server\"")) {
            return null;
        }

        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) {
                return null;
            }

            JsonObject json = parsed.getAsJsonObject();
            return asString(json, "server") == null ? null : json;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Whether this answer is the one a {@code /locraw} typed by the player is waiting for. One
     * that never came back is forgotten after a while, so it cannot let a later answer through.
     */
    private static boolean claimTypedRequest() {
        if (typedRequests <= 0) {
            return false;
        }

        if (System.currentTimeMillis() - lastTypedRequest > TYPED_ANSWER_WINDOW_MS) {
            typedRequests = 0;
            return false;
        }

        typedRequests--;
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
