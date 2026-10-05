package tabstats.playerapi.api;

import tabstats.config.ModConfig;
import tabstats.playerapi.exception.*;
import tabstats.util.Log;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class HypixelAPI {
    private static final String PLAYER_ENDPOINT = "https://api.hypixel.net/v2/player?uuid=%s";
    /** Pause when Hypixel throttles without saying for how long. */
    private static final long DEFAULT_KEY_THROTTLE_MS = 10_000L;
    private static final long DEFAULT_GLOBAL_THROTTLE_MS = 30_000L;
    /** Hypixel refuses the same player again for about a minute. */
    public static final long PLAYER_COOLDOWN_MS = 60_000L;

    /**
     * No request goes out before this moment. One throttle answer closes the gate for every
     * caller at once - retrying each player on its own clock would keep the throttle alive.
     */
    private static volatile long notBefore;
    private static volatile String lastRejectedKey;

    /** How long requests are paused for, 0 when they may go out right away. */
    public static long millisUntilAllowed() {
        return Math.max(0L, notBefore - System.currentTimeMillis());
    }

    /**
     * @param uuid Target player's UUID
     * @return JsonObject of the player's whole api result, with a non-null "player" object
     * @throws InvalidKeyException If Hypixel API Key is missing or Invalid
     * @throws PlayerNullException If Target Player UUID is returned Null from the Hypixel API
     * @throws ApiThrottleException If requests are paused, or Hypixel throttled this one
     * @throws PlayerCooldownException If Hypixel refused this player because they were asked for a moment ago
     * @throws ApiRequestException If the request failed in any other way, network errors included
     * @throws BadJsonException If the answer was not a JSON object
     */
    public JsonObject getWholeObject(String uuid) throws InvalidKeyException, PlayerNullException, ApiRequestException, BadJsonException {
        String apiKey = ModConfig.getInstance().getApiKey().trim();
        if (apiKey.isEmpty()) {
            throw new InvalidKeyException();
        }

        long wait = millisUntilAllowed();
        if (wait > 0L) {
            throw new ApiThrottleException(false, wait);
        }

        HttpGet request = new HttpGet(String.format(PLAYER_ENDPOINT, uuid.replace("-", "")));
        request.addHeader("Accept", "application/json");
        // A header rather than a query parameter, so the key never ends up in a logged URL
        request.addHeader("API-Key", apiKey);

        int status;
        long remaining;
        long resetMs;
        JsonObject obj;
        try (CloseableHttpResponse response = Http.CLIENT.execute(request)) {
            status = response.getStatusLine().getStatusCode();
            remaining = headerSeconds(response, "RateLimit-Remaining");
            resetMs = headerSeconds(response, "RateLimit-Reset") * 1000L;

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new ApiRequestException("Hypixel API returned no body (HTTP " + status + ")");
            }

            try (InputStreamReader reader = new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed == null || !parsed.isJsonObject()) {
                    throw new BadJsonException();
                }
                obj = parsed.getAsJsonObject();
            } catch (JsonParseException ex) {
                throw new BadJsonException();
            } finally {
                EntityUtils.consumeQuietly(entity);
            }
        } catch (IOException ex) {
            throw new ApiRequestException("Could not reach the Hypixel API: " + ex);
        }

        boolean success = isTrue(obj, "success");
        boolean global = isTrue(obj, "global");
        boolean throttled = !success && (global || isTrue(obj, "throttle") || status == 429);
        String cause = asString(obj, "cause");

        /*
         * Hypixel also answers 429 when the same player was asked for within the last minute,
         * e.g. by /bw right before the tab list wanted them. That says nothing about the key, so
         * only this player waits - closing the gate over it once paused every request for the
         * rest of the five-minute window, and a pre-game lobby went by without any stats.
         */
        if (throttled && !global && isPlayerCooldown(cause, remaining)) {
            throw new PlayerCooldownException(PLAYER_COOLDOWN_MS);
        }

        if (remaining == 0L && resetMs > 0L) {
            // The window is used up; asking again before it resets only earns a throttle
            closeGate(resetMs, "request window used up");
        }

        if (!success) {
            if (throttled) {
                long pause = resetMs > 0L ? resetMs : (global ? DEFAULT_GLOBAL_THROTTLE_MS : DEFAULT_KEY_THROTTLE_MS);
                closeGate(pause, cause.isEmpty() ? "HTTP " + status : cause);
                throw new ApiThrottleException(global, pause);
            }

            if (status == 403 || "Invalid API key".equalsIgnoreCase(cause)) {
                if (!apiKey.equals(lastRejectedKey)) {
                    lastRejectedKey = apiKey;
                    Log.warn("Hypixel rejected the API key - set a new one with /tabstats");
                }
                throw new InvalidKeyException();
            }

            throw cause.isEmpty() ? new ApiRequestException("Hypixel API request failed (HTTP " + status + ")") : new ApiRequestException(cause);
        }

        JsonElement playerElement = obj.get("player");
        if (playerElement == null || !playerElement.isJsonObject()) {
            throw new PlayerNullException();
        }

        return obj;
    }

    private static void closeGate(long millis, String reason) {
        long until = System.currentTimeMillis() + millis;
        if (until > notBefore) {
            if (millisUntilAllowed() == 0L) {
                Log.info("Hypixel API throttled (" + reason + ") - pausing requests for " + (millis / 1000L) + "s");
            }
            notBefore = until;
        }
    }

    /**
     * Whether a throttle answer is about the one player asked for rather than the key. Hypixel
     * says so in the cause; failing that, a key with requests left in its window cannot be the
     * one that ran out.
     */
    private static boolean isPlayerCooldown(String cause, long remaining) {
        return cause.toLowerCase(Locale.ROOT).contains("too recently") || remaining > 0L;
    }

    /** A numeric response header, or -1 when it is missing or not a number. */
    private static long headerSeconds(CloseableHttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        if (header == null) {
            return -1L;
        }

        try {
            return Long.parseLong(header.getValue().trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static boolean isTrue(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private static String asString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
