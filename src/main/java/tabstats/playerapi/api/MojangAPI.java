package tabstats.playerapi.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import tabstats.util.BuildInfo;
import tabstats.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Name to UUID lookups. Needed for players that are only known by a name - typed into a command,
 * or written in chat - since the Hypixel API v2 player endpoint takes a UUID and nothing else.
 *
 * <p>Mojang rate-limits these lookups per IP and is down every now and then, so the same question
 * goes to several services in turn: Mojang's two endpoints first, then playerdb.co, which answers
 * from a cache of its own. A service that refused is left alone for a while instead of being
 * asked again for every single name.
 */
public class MojangAPI {
    private static final String USER_AGENT = "TabStats/" + BuildInfo.VERSION;
    /** How long a service that refused or failed is skipped. */
    private static final long BACKOFF_MS = 30_000L;

    private static final Service[] SERVICES = {
            new Service("api.mojang.com", "https://api.mojang.com/users/profiles/minecraft/%s", false),
            new Service("api.minecraftservices.com", "https://api.minecraftservices.com/minecraft/profile/lookup/name/%s", false),
            new Service("playerdb.co", "https://playerdb.co/api/player/minecraft/%s", true),
    };

    /**
     * @param name player name as it was typed or appeared in chat
     * @return never null: the profile, {@link Profile#NOT_FOUND} when there is no account under
     *         that name (which on Hypixel means a nick), or one of the failures, which are worth
     *         retrying later
     */
    public Profile lookupProfile(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Profile.NOT_FOUND;
        }

        String encoded;
        try {
            encoded = URLEncoder.encode(name.trim(), "UTF-8");
        } catch (IOException ex) {
            return Profile.FAILED;
        }

        boolean rateLimited = false;
        boolean askedAny = false;
        long now = System.currentTimeMillis();

        for (Service service : SERVICES) {
            if (now < service.skipUntil) {
                rateLimited |= service.lastRefusalWasRateLimit;
                continue;
            }

            askedAny = true;
            Answer answer = service.ask(encoded, name.trim());
            if (answer.profile != null) {
                return answer.profile;
            }

            service.lastRefusalWasRateLimit = answer.rateLimited;
            service.skipUntil = System.currentTimeMillis() + BACKOFF_MS;
            rateLimited |= answer.rateLimited;
        }

        if (!askedAny) {
            Log.info("Every name lookup service is backing off - skipped " + name.trim());
        }

        return rateLimited ? Profile.RATE_LIMITED : Profile.FAILED;
    }

    /** What one service said: a definite profile (or definite absence), or why it could not tell. */
    private static final class Answer {
        private static final Answer RATE_LIMITED = new Answer(null, true);
        private static final Answer FAILED = new Answer(null, false);

        private final Profile profile;
        private final boolean rateLimited;

        private Answer(Profile profile, boolean rateLimited) {
            this.profile = profile;
            this.rateLimited = rateLimited;
        }

        private static Answer of(Profile profile) {
            return new Answer(profile, false);
        }
    }

    private static final class Service {
        private final String label;
        private final String endpoint;
        /** playerdb.co wraps the profile in an envelope of its own, Mojang's endpoints do not. */
        private final boolean playerDb;

        private volatile long skipUntil;
        private volatile boolean lastRefusalWasRateLimit;

        private Service(String label, String endpoint, boolean playerDb) {
            this.label = label;
            this.endpoint = endpoint;
            this.playerDb = playerDb;
        }

        private Answer ask(String encodedName, String name) {
            HttpGet request = new HttpGet(String.format(this.endpoint, encodedName));
            request.addHeader("Accept", "application/json");
            request.addHeader("User-Agent", USER_AGENT);

            try (CloseableHttpResponse response = Http.CLIENT.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                try {
                    return this.playerDb ? readPlayerDb(status, entity, name) : readMojang(status, entity, name);
                } finally {
                    EntityUtils.consumeQuietly(entity);
                }
            } catch (IOException | RuntimeException ex) {
                Log.info("Name lookup via " + this.label + " failed: " + ex);
                return Answer.FAILED;
            }
        }

        private Answer readMojang(int status, HttpEntity entity, String name) throws IOException {
            /* 204 (old behaviour) and 404 both mean "no such account". */
            if (status == 204 || status == 404) {
                return Answer.of(Profile.NOT_FOUND);
            }

            if (status != 200) {
                return refused(status);
            }

            JsonObject object = parse(entity);
            if (object == null) {
                return Answer.FAILED;
            }

            if (!object.has("id")) {
                return Answer.of(Profile.NOT_FOUND);
            }

            return profileOf(object.get("id").getAsString(), object.has("name") ? object.get("name").getAsString() : name);
        }

        /**
         * {@code {"code":"player.found","data":{"player":{"username":..,"raw_id":..}}}} when found,
         * HTTP 400 with {@code "code":"minecraft.invalid_username"} when there is no such account.
         */
        private Answer readPlayerDb(int status, HttpEntity entity, String name) throws IOException {
            if (status == 429 || status >= 500) {
                return refused(status);
            }

            JsonObject object = parse(entity);
            if (object == null) {
                return Answer.FAILED;
            }

            String code = object.has("code") && !object.get("code").isJsonNull() ? object.get("code").getAsString() : "";
            if ("minecraft.invalid_username".equals(code) || "player.not_found".equals(code)) {
                return Answer.of(Profile.NOT_FOUND);
            }

            if (status != 200 || !"player.found".equals(code)) {
                return refused(status);
            }

            JsonObject data = object.getAsJsonObject("data");
            JsonObject player = data == null ? null : data.getAsJsonObject("player");
            if (player == null || !player.has("raw_id")) {
                return Answer.FAILED;
            }

            String resolvedName = player.has("username") ? player.get("username").getAsString() : name;
            return profileOf(player.get("raw_id").getAsString(), resolvedName);
        }

        private Answer refused(int status) {
            Log.info("Name lookup via " + this.label + " refused (HTTP " + status + ")");
            return status == 429 ? Answer.RATE_LIMITED : Answer.FAILED;
        }
    }

    private static JsonObject parse(HttpEntity entity) throws IOException {
        if (entity == null) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException ex) {
            /* An HTML error page, which is what Mojang sends along with most of its 429s. */
            return null;
        }
    }

    private static Answer profileOf(String id, String name) {
        UUID uuid = parseUndashedUuid(id);
        return uuid == null ? Answer.FAILED : Answer.of(new Profile(Profile.Kind.FOUND, uuid, name));
    }

    static UUID parseUndashedUuid(String id) {
        if (id == null || id.length() != 32) {
            return null;
        }

        try {
            return UUID.fromString(
                    id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16)
                            + "-" + id.substring(16, 20) + "-" + id.substring(20));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static final class Profile {
        enum Kind { FOUND, NOT_FOUND, RATE_LIMITED, FAILED }

        public static final Profile NOT_FOUND = new Profile(Kind.NOT_FOUND, null, null);
        /** Every service that was asked refused for asking too often. */
        public static final Profile RATE_LIMITED = new Profile(Kind.RATE_LIMITED, null, null);
        /** The lookup failed for some other reason - network, outage, an answer that made no sense. */
        public static final Profile FAILED = new Profile(Kind.FAILED, null, null);

        private final Kind kind;
        private final UUID uuid;
        private final String name;

        private Profile(Kind kind, UUID uuid, String name) {
            this.kind = kind;
            this.uuid = uuid;
            this.name = name;
        }

        public boolean exists() {
            return this.kind == Kind.FOUND;
        }

        /** No answer either way: neither a profile nor a definite "no such account". */
        public boolean failed() {
            return this.kind == Kind.RATE_LIMITED || this.kind == Kind.FAILED;
        }

        public boolean isRateLimited() {
            return this.kind == Kind.RATE_LIMITED;
        }

        public UUID getUuid() {
            return this.uuid;
        }

        public String getName() {
            return this.name;
        }
    }
}
