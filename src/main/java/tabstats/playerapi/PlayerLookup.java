package tabstats.playerapi;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import tabstats.TabStats;
import tabstats.playerapi.api.HypixelAPI;
import tabstats.playerapi.api.MojangAPI;
import tabstats.playerapi.exception.ApiRequestException;
import tabstats.playerapi.exception.BadJsonException;
import tabstats.playerapi.exception.InvalidKeyException;
import tabstats.playerapi.exception.PlayerCooldownException;
import tabstats.playerapi.exception.PlayerNullException;
import tabstats.util.Handler;
import tabstats.util.PlayerNames;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stats for a player named by hand, for the chat commands.
 *
 * <p>Unlike {@link StatWorld}, which only ever looks at players the client can see, this starts
 * from a name, which has to become a UUID before Hypixel can be asked. Players the stat world
 * already knows are handed back untouched, so asking about someone in your own game costs nothing.
 * Anyone else in the tab list - yourself included - has their UUID right there in their entry;
 * only names the client has never seen are sent to Mojang, which rate-limits those lookups.
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
        /** The name to UUID services all refused for being asked too often. */
        RATE_LIMITED,
        /** Hypixel hands out the same player only about once a minute, and this one was just asked for. */
        COOLDOWN,
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
     * the client thread when nothing had to be fetched, a worker thread otherwise.
     */
    public static void lookup(String rawName, Callback callback) {
        final String name = rawName == null ? "" : rawName.trim();
        if (!PlayerNames.isValid(name)) {
            callback.onResult(new Result(name, Status.UNKNOWN_NAME, null));
            return;
        }

        /* The stat world and the tab list both belong to the client thread. */
        Handler.onClientThread(() -> {
            HPlayer known = fromStatWorld(name);
            if (known == null) {
                known = fromCache(name);
            }

            if (known != null) {
                callback.onResult(new Result(displayName(known, name), Status.OK, known));
                return;
            }

            GameProfile listed = fromTabList(name);
            if (listed != null && listed.getId().version() == 1) {
                /* Version 1 UUIDs are always nicks on Hypixel - Mojang would hand out a stranger. */
                callback.onResult(new Result(listed.getName(), Status.UNKNOWN_NAME, null));
                return;
            }

            final GameProfile profile = listed != null && listed.getId().version() == 4 ? listed : null;
            Handler.asExecutor(() -> callback.onResult(fetch(name, profile)));
        });
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

        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            for (CachedPlayer cached : CACHE.values()) {
                if (now - cached.fetchedAt <= CACHE_TTL_MS && uuid.equals(cached.player.getUuid())) {
                    return cached.player;
                }
            }
        }

        return null;
    }

    /**
     * @param listed the player's tab list entry, when they have one - their UUID is then taken
     *               from it and Mojang is not asked at all
     */
    private static Result fetch(String name, GameProfile listed) {
        UUID uuid;
        String resolvedName;

        if (listed != null) {
            uuid = listed.getId();
            resolvedName = listed.getName();
        } else {
            MojangAPI.Profile profile = new MojangAPI().lookupProfile(name);
            if (profile.failed()) {
                return new Result(name, profile.isRateLimited() ? Status.RATE_LIMITED : Status.FAILED, null);
            }

            if (!profile.exists()) {
                return new Result(name, Status.UNKNOWN_NAME, null);
            }

            uuid = profile.getUuid();
            resolvedName = profile.getName() == null || profile.getName().trim().isEmpty()
                    ? name
                    : profile.getName();

            /* The real name may differ from the one that was typed, so the cache gets another look. */
            HPlayer known = fromCache(resolvedName);
            if (known != null) {
                remember(name, known);
                return new Result(displayName(known, resolvedName), Status.OK, known);
            }
        }

        try {
            JsonObject wholeObject = new HypixelAPI().getWholeObject(uuid);
            HPlayer player = HPlayer.fromApi(uuid, resolvedName, wholeObject);

            remember(name, player);
            remember(resolvedName, player);
            return new Result(displayName(player, resolvedName), Status.OK, player);
        } catch (InvalidKeyException ex) {
            return new Result(resolvedName, Status.NO_API_KEY, null);
        } catch (PlayerNullException ex) {
            return new Result(resolvedName, Status.NO_HYPIXEL_DATA, null);
        } catch (PlayerCooldownException ex) {
            return new Result(resolvedName, Status.COOLDOWN, null);
        } catch (ApiRequestException | BadJsonException ex) {
            return new Result(resolvedName, Status.FAILED, null);
        } catch (RuntimeException ex) {
            /* A player object shaped differently than expected - treat it like a failed call. */
            return new Result(resolvedName, Status.FAILED, null);
        }
    }

    /**
     * The tab list entry of that name, matched regardless of case - your own included, which a
     * server does not always list. Call on the client thread only.
     */
    private static GameProfile fromTabList(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return null;
        }

        GameProfile own = mc.thePlayer.getGameProfile();
        if (isNamed(own, name)) {
            return own;
        }

        NetHandlerPlayClient netHandler = mc.getNetHandler();
        if (netHandler == null) {
            return null;
        }

        for (NetworkPlayerInfo info : netHandler.getPlayerInfoMap()) {
            GameProfile profile = info == null ? null : info.getGameProfile();
            if (isNamed(profile, name)) {
                return profile;
            }
        }

        return null;
    }

    private static boolean isNamed(GameProfile profile, String name) {
        return profile != null && profile.getId() != null && name.equalsIgnoreCase(profile.getName());
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
