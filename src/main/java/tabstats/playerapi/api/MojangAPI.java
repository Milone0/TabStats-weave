package tabstats.playerapi.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import tabstats.util.BuildInfo;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Name to UUID lookups. Needed for players that are only known by the name they wrote in chat,
 * since the Hypixel API v2 player endpoint takes a UUID and nothing else.
 */
public class MojangAPI {
    private static final String PROFILE_ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final String USER_AGENT = "TabStats/" + BuildInfo.VERSION;

    /**
     * @param name player name as it appeared in chat
     * @return the profile, {@link Profile#NOT_FOUND} when Mojang has no account under that name
     *         (which on Hypixel means a nick), or null when the lookup itself failed and is
     *         worth retrying later
     */
    public Profile lookupProfile(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Profile.NOT_FOUND;
        }

        try {
            HttpGet request = new HttpGet(String.format(PROFILE_ENDPOINT, URLEncoder.encode(name.trim(), "UTF-8")));
            request.addHeader("Accept", "application/json");
            request.addHeader("User-Agent", USER_AGENT);
            return execute(request, name);
        } catch (Exception ex) {
            // Network hiccup, throttling, anything else - let the caller try again later
            return null;
        }
    }

    private static Profile execute(HttpGet request, String name) throws IOException {
        try (CloseableHttpResponse response = Http.CLIENT.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();

            /* 204 (old behaviour) and 404 both mean "no such account". */
            if (status == 204 || status == 404) {
                EntityUtils.consumeQuietly(entity);
                return Profile.NOT_FOUND;
            }

            if (status != 200 || entity == null) {
                EntityUtils.consumeQuietly(entity);
                return null;
            }

            JsonObject object;
            try (InputStreamReader reader = new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8)) {
                object = new JsonParser().parse(reader).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException ex) {
                return null;
            } finally {
                EntityUtils.consumeQuietly(entity);
            }

            if (object == null || !object.has("id")) {
                return Profile.NOT_FOUND;
            }

            UUID uuid = parseUndashedUuid(object.get("id").getAsString());
            if (uuid == null) {
                return null;
            }

            String resolvedName = object.has("name") ? object.get("name").getAsString() : name.trim();
            return new Profile(uuid, resolvedName);
        }
    }

    private static UUID parseUndashedUuid(String id) {
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
        public static final Profile NOT_FOUND = new Profile(null, null);

        private final UUID uuid;
        private final String name;

        private Profile(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public boolean exists() {
            return this.uuid != null;
        }

        public UUID getUuid() {
            return this.uuid;
        }

        public String getName() {
            return this.name;
        }
    }
}
