package tabstats.playerapi;

import tabstats.TabStats;
import tabstats.playerapi.api.HypixelAPI;
import tabstats.playerapi.api.MojangAPI;
import tabstats.playerapi.exception.ApiRequestException;
import tabstats.playerapi.exception.ApiThrottleException;
import tabstats.playerapi.exception.BadJsonException;
import tabstats.playerapi.exception.InvalidKeyException;
import tabstats.playerapi.exception.PlayerCooldownException;
import tabstats.playerapi.exception.PlayerNullException;
import tabstats.util.ChatColor;
import tabstats.util.Handler;
import tabstats.util.Log;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class StatWorld {
    /** Once more players than this are cached, the ones no longer around are dropped. */
    protected static final int MAX_CACHED_PLAYERS = 500;
    /** Attempts 0 to 8 - after that a player is shown without stats. */
    private static final int MAX_API_RETRIES = 8;
    /** Spreads the retries that wait for the same throttle to lift, so they do not all fire at once. */
    private static final long THROTTLE_JITTER_MS = 500L;
    /** Upper bound on chat reveals, so a busy lobby cannot grow the tab list without end. */
    private static final int MAX_CHAT_REVEALED = 80;

    private final ConcurrentHashMap<UUID, HPlayer> worldPlayers = new ConcurrentHashMap<>();
    private final Map<String, HPlayer> nameAliases = new ConcurrentHashMap<>();
    protected final Set<UUID> statAssembly = ConcurrentHashMap.newKeySet();
    /** Players known only from chat, keyed by lower-case name, in the order they were revealed. */
    private final Map<String, ChatRevealedPlayer> chatRevealed =
            Collections.synchronizedMap(new LinkedHashMap<String, ChatRevealedPlayer>());
    private final Set<String> chatLookupsInFlight = ConcurrentHashMap.newKeySet();
    /**
     * When the current world was joined. A new world is a new game, and stats loaded before it
     * miss whatever has been played since - the winstreak above all.
     */
    private volatile long worldJoinedAt = System.currentTimeMillis();
    /** Players already loaded again in this world, so a reload that fails is not retried every tick. */
    private final Set<UUID> refreshed = ConcurrentHashMap.newKeySet();
    /**
     * Bumped whenever the chat reveals are dropped, so a lookup that started in the lobby before
     * cannot add its player to the next one. Guarded by {@link #chatRevealed}.
     */
    private int chatGeneration;
    /** How many version 2 UUIDs were looked up, and for how many of them Hypixel had a player. */
    private final AtomicInteger v2Lookups = new AtomicInteger();
    private final AtomicInteger v2Hits = new AtomicInteger();

    public void removePlayer(UUID playerUUID) {
        HPlayer removed = worldPlayers.remove(playerUUID);
        statAssembly.remove(playerUUID);
        removeAliases(removed);
    }

    public void addPlayer(UUID playerUUID, HPlayer player) {
        worldPlayers.put(playerUUID, player);
        registerAlias(player, player.getPlayerName());
    }

    public void clearPlayers() {
        worldPlayers.clear();
        clearChatRevealed();
        statAssembly.clear();
        nameAliases.clear();
        refreshed.clear();
    }

    /**
     * Called on every world change. Everything loaded so far stays on screen, but counts as
     * outdated from now on and is loaded again as the players show up.
     */
    public void startNewWorld() {
        this.worldJoinedAt = System.currentTimeMillis();
        this.refreshed.clear();
        // Names picked up from chat belong to the lobby just left
        clearChatRevealed();
    }

    /** Whether a player's stats were loaded before the current world, so before the game being played. */
    public boolean isOutdated(HPlayer player) {
        return !player.isNicked() && player.getLoadedAt() < this.worldJoinedAt;
    }

    /**
     * Claims the one reload an outdated player gets per world; the old stats stay on screen
     * until the new ones are in. False when there is nothing to reload or a load is under way.
     */
    protected boolean claimRefresh(UUID uuid, HPlayer player) {
        return isOutdated(player) && !this.statAssembly.contains(uuid)
                && this.refreshed.add(uuid) && this.statAssembly.add(uuid);
    }

    /**
     * Re-render tab list: For each player check if they're in cache, if yes display cached data,
     * if not in cache then fetch stats for that player only
     */
    public void rerenderTabList() {
        // Clear tracking to allow fresh processing but preserve cached players. The actual
        // re-rendering happens in WorldLoader.onClientTick: cached players display immediately,
        // everyone else is fetched.
        statAssembly.clear();
    }

    /**
     * Recheck all players: Force all players through fetchStatsWithRetry regardless of cache status
     */
    public void recheckAllPlayers() {
        // Clear all cached data to force re-fetching for everyone
        clearPlayers();
        PlayerLookup.clearCache();
    }

    public ConcurrentHashMap<UUID, HPlayer> getWorldPlayers() {
        return this.worldPlayers;
    }

    public void removeFromStatAssembly(UUID uuid) { this.statAssembly.remove(uuid); }

    public HPlayer getPlayerByUUID(UUID uuid) {
        return this.worldPlayers.get(uuid);
    }

    public HPlayer getPlayerByIdentity(UUID uuid, String... fallbackName) {
        HPlayer player = this.worldPlayers.get(uuid);
        if (player != null) {
            return player;
        }

        if (fallbackName != null) {
            for (String candidate : fallbackName) {
                HPlayer aliased = getPlayerByName(candidate);
                if (aliased != null) {
                    return aliased;
                }
            }
        }

        return null;
    }

    /** A cached player by any name they were seen under, ignoring case. */
    public HPlayer getPlayerByName(String name) {
        if (name == null) {
            return null;
        }

        String normalized = name.trim();
        return normalized.isEmpty() ? null : this.nameAliases.get(normalized.toLowerCase(Locale.ROOT));
    }

    /** How many version 2 UUIDs were looked up this session, for {@code /tabstats where}. */
    public int getV2Lookups() {
        return this.v2Lookups.get();
    }

    /** How many of those Hypixel actually had a player for. */
    public int getV2Hits() {
        return this.v2Hits.get();
    }

    /**
     * Fetch stats for a specific player using the retry system
     */
    public void fetchStats(EntityPlayer entityPlayer) {
        if (!TabStats.isActive()) {
            this.statAssembly.remove(entityPlayer.getUniqueID());
            return;
        }

        IChatComponent displayName = entityPlayer.getDisplayName();
        fetchStatsWithRetry(
                entityPlayer.getUniqueID(),
                entityPlayer.getName(),
                displayName != null ? displayName.getFormattedText() : null,
                0,
                0L);
    }

    /**
     * Loads one player on the pool after {@code delayMs}. The wait happens on the scheduler, so a
     * retry that is waiting out a throttle never ties up a pool thread.
     */
    private void fetchStatsWithRetry(UUID uuid, String playerName, String displayComponent, int attempt, long delayMs) {
        Handler.schedule(() -> loadStats(uuid, playerName, displayComponent, attempt), delayMs);
    }

    private void loadStats(UUID uuid, String playerName, String displayComponent, int attempt) {
        if (!TabStats.isActive()) {
            this.statAssembly.remove(uuid);
            return;
        }

        HPlayer existing = getPlayerByIdentity(uuid, displayComponent, playerName);
        if (existing != null && !isOutdated(existing)) {
            cachePlayer(uuid, existing, displayComponent);
            return;
        }

        /* Just fetched for a chat command - Hypixel would refuse to hand the same player out again this soon. */
        HPlayer lookedUp = PlayerLookup.getRecentByUuid(uuid);
        if (lookedUp != null) {
            if (!isOutdated(lookedUp)) {
                cachePlayer(uuid, lookedUp, playerName, displayComponent);
                return;
            }

            if (existing == null) {
                // From before this game, but better than nothing while the new stats load
                this.addPlayer(uuid, lookedUp);
            }
        }

        String playerUUID = uuid.toString().replace("-", "");
        boolean versionTwo = uuid.version() == 2;
        if (versionTwo && attempt == 0) {
            this.v2Lookups.incrementAndGet();
        }

        try {
            JsonObject wholeObject = new HypixelAPI().getWholeObject(playerUUID);
            // Only real players have API data, so whoever comes back here is not nicked
            HPlayer hPlayer = HPlayer.fromApi(playerUUID, playerName, wholeObject);
            if (versionTwo) {
                this.v2Hits.incrementAndGet();
            }
            cachePlayer(uuid, hPlayer, playerName, displayComponent);
        } catch (ApiThrottleException ex) {
            // Requests are paused for everyone; come back once the pause is over
            long jitter = ThreadLocalRandom.current().nextLong(THROTTLE_JITTER_MS);
            retryLater(uuid, playerName, displayComponent, attempt, ex.getRetryAfterMs() + jitter);
        } catch (PlayerCooldownException ex) {
            // Only this player has to wait; everyone else is still asked for right away
            Log.info("Hypixel refused " + playerName + " as asked for too recently - retrying in "
                    + (ex.getRetryAfterMs() / 1000L) + "s");
            retryLater(uuid, playerName, displayComponent, attempt, ex.getRetryAfterMs());
        } catch (InvalidKeyException ex) {
            /*
             * No key, or one Hypixel rejects. Asking again would only repeat the answer - each tick,
             * for every player. They stay in statAssembly instead; saving a new key rechecks everyone.
             */
        } catch (PlayerNullException ex) {
            if (versionTwo) {
                // Version 2 UUIDs with no API data are NPCs and lobby bots - leave in statAssembly so we don't re-fetch
                return;
            }
            retryLater(uuid, playerName, displayComponent, attempt, backoff(attempt));
        } catch (ApiRequestException | BadJsonException ex) {
            retryLater(uuid, playerName, displayComponent, attempt, backoff(attempt));
        } catch (RuntimeException ex) {
            // A player object shaped differently than expected - treat it like a failed call
            Log.warn("Unexpected API answer for " + playerName, ex);
            retryLater(uuid, playerName, displayComponent, attempt, backoff(attempt));
        }
    }

    private void retryLater(UUID uuid, String playerName, String displayComponent, int attempt, long delayMs) {
        if (attempt >= MAX_API_RETRIES) {
            // Out of retries: show them as a player without stats rather than not at all
            Log.warn("Giving up on the stats of " + playerName + " after " + (attempt + 1) + " attempts");
            if (getPlayerByUUID(uuid) != null) {
                // A failed reload: the stats from an earlier game stay, and are not asked for again in this world
                this.refreshed.add(uuid);
                this.removeFromStatAssembly(uuid);
                return;
            }

            HPlayer bare = new HPlayer(uuid.toString().replace("-", ""), playerName);
            cachePlayer(uuid, bare, playerName, displayComponent);
            return;
        }

        fetchStatsWithRetry(uuid, playerName, displayComponent, attempt + 1, delayMs);
    }

    /** Exponential backoff for failed calls: 0, 250, 500, 1000 ... 16000 ms. */
    private static long backoff(int attempt) {
        return attempt == 0 ? 0L : 250L << (attempt - 1);
    }

    /**
     * A name showed up in chat. Resolves it to a UUID and pulls the stats in, so the player can be
     * drawn in the tab list even though a pre-game lobby gives them no tab entry of their own.
     */
    public void revealFromChat(String name) {
        if (name == null || !TabStats.isActive()) {
            return;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String key = trimmed.toLowerCase(Locale.ROOT);
        final int generation;
        synchronized (this.chatRevealed) {
            if (this.chatRevealed.containsKey(key) || this.chatRevealed.size() >= MAX_CHAT_REVEALED) {
                return;
            }
            generation = this.chatGeneration;
        }

        /* Already fetched for some other reason - they have an entity, or they chatted before. */
        HPlayer known = getPlayerByName(trimmed);
        if (known != null) {
            UUID knownUuid = parseUuid(known.getPlayerUUID());
            if (knownUuid != null) {
                this.chatRevealed.put(key, new ChatRevealedPlayer(knownUuid, known.getPlayerName(), !known.isNicked()));
                // Without an entity the tick never sees them, so stats from an earlier game are reloaded here
                if (claimRefresh(knownUuid, known)) {
                    fetchStatsWithRetry(knownUuid, known.getPlayerName(), null, 0, 0L);
                }
            }
            return;
        }

        if (!this.chatLookupsInFlight.add(key)) {
            return;
        }

        Handler.asExecutor(() -> {
            try {
                MojangAPI.Profile profile = new MojangAPI().lookupProfile(trimmed);
                if (profile == null) {
                    // Lookup itself failed - the next message from them tries again
                    return;
                }

                if (!profile.exists()) {
                    /*
                     * No Mojang account behind the name: on Hypixel that means a nick. Kept out of
                     * the player cache on purpose - the placeholder UUID is not a real identity,
                     * and an alias under it would label the real tab entry of that name too.
                     */
                    UUID placeholder = UUID.nameUUIDFromBytes(("TabStatsNick:" + key).getBytes(StandardCharsets.UTF_8));
                    revealIfStillCurrent(generation, key, new ChatRevealedPlayer(placeholder, trimmed, false));
                    return;
                }

                UUID uuid = profile.getUuid();
                if (!revealIfStillCurrent(generation, key, new ChatRevealedPlayer(uuid, profile.getName(), true))) {
                    return;
                }

                if (getPlayerByUUID(uuid) == null && this.statAssembly.add(uuid)) {
                    fetchStatsWithRetry(uuid, profile.getName(), null, 0, 0L);
                }
            } finally {
                this.chatLookupsInFlight.remove(key);
            }
        });
    }

    /** Adds a reveal unless the lobby it was looked up for has been left in the meantime. */
    private boolean revealIfStillCurrent(int generation, String key, ChatRevealedPlayer player) {
        synchronized (this.chatRevealed) {
            if (generation != this.chatGeneration) {
                return false;
            }

            this.chatRevealed.put(key, player);
            return true;
        }
    }

    /** Drops a chat reveal again, for when the server announces that the player left. */
    public void hideFromChat(String name) {
        if (name == null) {
            return;
        }

        this.chatRevealed.remove(name.trim().toLowerCase(Locale.ROOT));
    }

    public List<ChatRevealedPlayer> getChatRevealedPlayers() {
        synchronized (this.chatRevealed) {
            return new ArrayList<>(this.chatRevealed.values());
        }
    }

    public void clearChatRevealed() {
        synchronized (this.chatRevealed) {
            this.chatGeneration++;
            this.chatRevealed.clear();
        }
        this.chatLookupsInFlight.clear();
    }

    /** Drops every cached player that is not in {@code keep} and not revealed by chat either. */
    protected void retainPlayers(Set<UUID> keep) {
        Set<UUID> safe = new HashSet<>(keep);
        // Chat-revealed players have no entity, so keep them out of the eviction
        for (ChatRevealedPlayer player : getChatRevealedPlayers()) {
            safe.add(player.getUuid());
        }

        for (UUID playerUUID : new ArrayList<>(this.worldPlayers.keySet())) {
            if (!safe.contains(playerUUID)) {
                removePlayer(playerUUID);
            }
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.replace("-", "");
        if (value.length() != 32) {
            return null;
        }

        try {
            return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void registerAlias(HPlayer player, String name) {
        if (player == null || name == null) {
            return;
        }

        String normalized = name.trim();
        if (normalized.isEmpty()) {
            return;
        }

        storeAlias(normalized, player);

        String stripped = ChatColor.stripColor(normalized);
        if (stripped != null && !stripped.equalsIgnoreCase(normalized)) {
            storeAlias(stripped, player);
        }
    }

    private void removeAliases(HPlayer player) {
        if (player == null) {
            return;
        }

        nameAliases.entrySet().removeIf(entry -> entry.getValue() == player);
    }

    private void storeAlias(String name, HPlayer player) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        nameAliases.put(trimmed.toLowerCase(Locale.ROOT), player);
    }

    /**
     * Caches a finished player and lets the tick pick up the next one. The aliases are only
     * registered here, so a name never points at a player that is still being fetched.
     */
    private void cachePlayer(UUID uuid, HPlayer player, String... aliases) {
        this.addPlayer(uuid, player);
        for (String alias : aliases) {
            registerAlias(player, alias);
        }
        this.removeFromStatAssembly(uuid);
    }
}
