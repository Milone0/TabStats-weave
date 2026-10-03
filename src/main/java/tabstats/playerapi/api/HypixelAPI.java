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

public class HypixelAPI {
    private static final String PLAYER_ENDPOINT = "https://api.hypixel.net/v2/player?uuid=%s";
    /** Pause when Hypixel throttles without saying for how long. */
    private static final long DEFAULT_KEY_THROTTLE_MS = 10_000L;
    private static final long DEFAULT_GLOBAL_THROTTLE_MS = 30_000L;

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
        long resetMs;
        JsonObject obj;
        try (CloseableHttpResponse response = Http.CLIENT.execute(request)) {
            status = response.getStatusLine().getStatusCode();
            resetMs = headerSeconds(response, "RateLimit-Reset") * 1000L;
            if (headerSeconds(response, "RateLimit-Remaining") == 0L && resetMs > 0L) {
                // The window is used up; asking again before it resets only earns a throttle
                closeGate(resetMs);
            }

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
        if (!success) {
            boolean global = isTrue(obj, "global");
            if (global || isTrue(obj, "throttle") || status == 429) {
                long pause = resetMs > 0L ? resetMs : (global ? DEFAULT_GLOBAL_THROTTLE_MS : DEFAULT_KEY_THROTTLE_MS);
                closeGate(pause);
                throw new ApiThrottleException(global, pause);
            }

            String cause = asString(obj, "cause");
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

    private static void closeGate(long millis) {
        long until = System.currentTimeMillis() + millis;
        if (until > notBefore) {
            if (millisUntilAllowed() == 0L) {
                Log.info("Hypixel API throttled - pausing requests for " + (millis / 1000L) + "s");
            }
            notBefore = until;
        }
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
