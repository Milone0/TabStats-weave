package tabstats.playerapi;

import com.google.gson.JsonObject;
import tabstats.TabStats;
import tabstats.playerapi.api.HypixelAPI;
import tabstats.playerapi.api.MojangAPI;
import tabstats.playerapi.exception.ApiRequestException;
import tabstats.playerapi.exception.BadJsonException;
import tabstats.playerapi.exception.InvalidKeyException;
import tabstats.playerapi.exception.PlayerNullException;
import tabstats.util.Handler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stats for a player named by hand, for the chat commands.
 *
 * <p>Unlike {@link StatWorld}, which only ever looks at players the client can see, this starts
 * from a name: Mojang turns it into a UUID, Hypixel turns that into stats. Players the stat world
 * already knows are handed back untouched, so asking about someone in your own game costs nothing.
 *
 * <p>Results are not written into the stat world. A name is a weaker identity than a tab entry -
 * a nicked player may well be wearing the name of someone real, and the tab list must not end up
 * showing that stranger stats for them. The stat world may still pick a result up by UUID through
 * {@link #getRecentByUuid}, which is as strong an identity as the tab entry itself.
 */
public final class PlayerLookup {
    /** Why a lookup produced no stats. */
    public enum Status {
        OK,
        /** Mojang has no account under that name, which on Hypixel means a nick. */
        UNKNOWN_NAME,
        /** A real account that has never been on Hypixel. */
        NO_HYPIXEL_DATA,
        NO_API_KEY,
        /** Network trouble, throttling, anything else worth retrying. */
        FAILED
    }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        private final String name;
        private final Status status;
        private final HPlayer player;

        private Result(String name, Status status, HPlayer player) {
            this.name = name;
            this.status = status;
            this.player = player;
        }

        /** The name as the lookup resolved it, falling back to the one that was asked for. */
        public String getName() {
            return this.name;
        }

        public Status getStatus() {
            return this.status;
        }

        public HPlayer getPlayer() {
            return this.player;
        }

        public boolean isOk() {
            return this.status == Status.OK && this.player != null;
        }
    }

    private static final Pattern VALID_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_CACHED = 60;

    private static final Map<String, CachedPlayer> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedPlayer>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedPlayer> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    private PlayerLookup() {
    }

    /**
     * Resolves one name. The callback runs on whichever thread the answer became available on -
     * the calling one when nothing had to be fetched, a worker thread otherwise.
     */
    public static void lookup(String rawName, Callback callback) {
        final String name = rawName == null ? "" : rawName.trim();
        if (!VALID_USERNAME.matcher(name).matches()) {
            callback.onResult(new Result(name, Status.UNKNOWN_NAME, null));
            return;
        }

        HPlayer known = fromStatWorld(name);
        if (known == null) {
            known = fromCache(name);
        }

        if (known != null) {
            callback.onResult(new Result(displayName(known, name), Status.OK, known));
            return;
        }

        Handler.asExecutor(() -> callback.onResult(fetch(name)));
    }

    /** Drops everything remembered, for when the mod is handed a different API key. */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * A player fetched here within the last few minutes, matched by UUID. Lets the stat world take
     * over what a chat command just loaded instead of asking Hypixel again, which refuses the same
     * player for about a minute.
     */
    public static HPlayer getRecentByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        String wanted = uuid.toString().replace("-", "");
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            for (CachedPlayer cached : CACHE.values()) {
                if (now - cached.fetchedAt <= CACHE_TTL_MS && wanted.equalsIgnoreCase(cached.player.getPlayerUUID())) {
                    return cached.player;
                }
            }
        }

        return null;
    }

    private static Result fetch(String name) {
        MojangAPI.Profile profile;
        try {
            profile = new MojangAPI().lookupProfile(name);
        } catch (RuntimeException ex) {
            return new Result(name, Status.FAILED, null);
        }

        if (profile == null) {
            return new Result(name, Status.FAILED, null);
        }

        if (!profile.exists()) {
            return new Result(name, Status.UNKNOWN_NAME, null);
        }

        UUID uuid = profile.getUuid();
        String resolvedName = profile.getName() == null || profile.getName().trim().isEmpty()
                ? name
                : profile.getName();

        /* The real name may differ from the one that was typed, so the caches get another look. */
        HPlayer known = fromStatWorld(resolvedName);
        if (known == null) {
            known = fromCache(resolvedName);
        }

        if (known != null) {
            remember(name, known);
            return new Result(displayName(known, resolvedName), Status.OK, known);
        }

        String playerUUID = uuid.toString().replace("-", "");
        try {
            JsonObject wholeObject = new HypixelAPI().getWholeObject(playerUUID);
            HPlayer player = HPlayer.fromApi(playerUUID, resolvedName, wholeObject);

            remember(name, player);
            remember(resolvedName, player);
            return new Result(displayName(player, resolvedName), Status.OK, player);
        } catch (InvalidKeyException ex) {
            return new Result(resolvedName, Status.NO_API_KEY, null);
        } catch (PlayerNullException ex) {
            return new Result(resolvedName, Status.NO_HYPIXEL_DATA, null);
        } catch (ApiRequestException | BadJsonException ex) {
            return new Result(resolvedName, Status.FAILED, null);
        } catch (RuntimeException ex) {
            /* A player object shaped differently than expected - treat it like a failed call. */
            return new Result(resolvedName, Status.FAILED, null);
        }
    }

    /** The player as the tab list already knows them, if their stats were loaded. */
    private static HPlayer fromStatWorld(String name) {
        TabStats tabStats = TabStats.getTabStats();
        StatWorld statWorld = tabStats == null ? null : tabStats.getStatWorld();
        if (statWorld == null) {
            return null;
        }

        HPlayer player = statWorld.getPlayerByName(name);
        return player != null && !player.isNicked() && player.hasGameData() && isCurrent(player) ? player : null;
    }

    /**
     * Whether stats are recent enough to hand out: loaded in the current world, so no game has
     * ended since. Older ones still count while Hypixel would refuse to hand the player out again.
     */
    private static boolean isCurrent(HPlayer player) {
        TabStats tabStats = TabStats.getTabStats();
        StatWorld statWorld = tabStats == null ? null : tabStats.getStatWorld();
        return statWorld == null
                || !statWorld.isOutdated(player)
                || System.currentTimeMillis() - player.getLoadedAt() < HypixelAPI.PLAYER_COOLDOWN_MS;
    }

    private static HPlayer fromCache(String name) {
        String key = key(name);
        CachedPlayer cached = CACHE.get(key);
        if (cached == null) {
            return null;
        }

        if (System.currentTimeMillis() - cached.fetchedAt > CACHE_TTL_MS) {
            CACHE.remove(key);
            return null;
        }

        return isCurrent(cached.player) ? cached.player : null;
    }

    private static void remember(String name, HPlayer player) {
        if (player == null) {
            return;
        }

        CACHE.put(key(name), new CachedPlayer(player));
    }

    private static String displayName(HPlayer player, String fallback) {
        String name = player == null ? null : player.getPlayerName();
        return name == null || name.trim().isEmpty() ? fallback : name;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CachedPlayer {
        private final HPlayer player;
        private final long fetchedAt;

        private CachedPlayer(HPlayer player) {
            this.player = player;
            this.fetchedAt = System.currentTimeMillis();
        }
    }
}
