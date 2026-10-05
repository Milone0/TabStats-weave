package tabstats.playerapi;

import tabstats.playerapi.api.games.HGameBase;
import tabstats.playerapi.api.games.bedwars.Bedwars;
import tabstats.playerapi.api.games.duels.Duels;
import tabstats.playerapi.api.games.skywars.Skywars;
import tabstats.playerapi.api.stats.Stat;
import tabstats.util.ChatColor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Hypixel Player */
public class HPlayer {
    private final Map<String, HGameBase> gameMap = new HashMap<>();
    private final String playerUUID;
    /** When this entry was made - for a player from the API, when the stats were fetched. */
    private final long loadedAt = System.currentTimeMillis();
    private String playerName;
    private String playerRank = "";
    private boolean nicked;

    /**
     * A bare entry without stats, for nicked players and for players the API had nothing on.
     *
     * @param playerUUID Player's UUID
     * @param playerName Player's Name
     */
    public HPlayer(String playerUUID, String playerName) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
    }

    /**
     * Builds a player from a Hypixel API answer: rank, real name, and the stats of every supported
     * game. The one place this happens, for the tab list and the chat commands alike.
     *
     * @param wholeObject an answer {@link tabstats.playerapi.api.HypixelAPI#getWholeObject} returned,
     *                    so it holds a "player" object
     * @param fallbackName the name to use when the answer carries none
     */
    public static HPlayer fromApi(String playerUUID, String fallbackName, JsonObject wholeObject) {
        JsonObject playerObject = wholeObject.getAsJsonObject("player");

        HPlayer player = new HPlayer(playerUUID, fallbackName);
        player.setPlayerRank(playerObject);

        JsonElement displayName = playerObject.get("displayname");
        if (displayName != null && displayName.isJsonPrimitive()) {
            player.playerName = displayName.getAsString();
        }

        player.addGames(
                new Bedwars(player.playerName, playerUUID, playerObject),
                new Duels(player.playerName, playerUUID, playerObject),
                new Skywars(player.playerName, playerUUID, playerObject)
        );
        return player;
    }

    private void addGames(HGameBase... gameBases) {
        for (HGameBase game : gameBases) {
            this.gameMap.put(game.getGame().getGameName(), game);
        }
    }

    /** Whether any game stats were loaded for this player, as opposed to a bare entry. */
    public boolean hasGameData() {
        return !this.gameMap.isEmpty();
    }

    public String getPlayerUUID() {
        return this.playerUUID;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public long getLoadedAt() {
        return this.loadedAt;
    }

    /** The formatted stats of one game, in the game's own order; empty when there are none. */
    public List<Stat> getFormattedGameStats(String gameName) {
        HGameBase game = this.gameMap.get(gameName);
        return game == null ? Collections.<Stat>emptyList() : game.getFormattedStatList();
    }

    /** The same stats keyed by {@link tabstats.util.StatFormatting#key}. */
    public Map<String, Stat> getGameStatsByKey(String gameName) {
        HGameBase game = this.gameMap.get(gameName);
        return game == null ? Collections.<String, Stat>emptyMap() : game.getStatsByKey();
    }

    public void setNicked(boolean nicked) { this.nicked = nicked; }

    public boolean isNicked() { return this.nicked; }

    private void setPlayerRank(JsonObject player) {
        String s = ChatColor.GRAY.toString();  // Default to gray for non-ranked players

        // Staff rank, monthly package rank (MVP++), regular package rank (VIP, MVP, etc.) and its colour
        String staff = stringOr(player, "rank", "NOT STAFF");
        String mvpPlusPlus = stringOr(player, "monthlyPackageRank", "NEVER BROUGHT");
        String rank = stringOr(player, "newPackageRank", "");
        ChatColor rankColour = colourOr(stringOr(player, "rankPlusColor", "RED"), ChatColor.RED);

        // Check for staff ranks first (highest priority)
        if (staff.equalsIgnoreCase("HELPER")) {
            s = ChatColor.BLUE + "[HELPER] ";
        } else if (staff.equalsIgnoreCase("MODERATOR")) {
            s = ChatColor.DARK_GREEN + "[MODERATOR] ";
        } else if (staff.equalsIgnoreCase("ADMIN")) {
            s = ChatColor.RED + "[ADMIN] ";
        } else if (staff.equalsIgnoreCase("YOUTUBER")) {
            s = ChatColor.RED + "[" + ChatColor.WHITE + "YOUTUBE" + ChatColor.RED + "] ";
        }
        // Check for MVP++ (superstar)
        else if (mvpPlusPlus.equalsIgnoreCase("SUPERSTAR")) {
            s = ChatColor.GOLD + "[MVP" + rankColour + "++" + ChatColor.GOLD + "] ";
        }
        // Check for other ranks
        else if (rank.equalsIgnoreCase("MVP_PLUS")) {
            s = ChatColor.AQUA + "[MVP" + rankColour + "+" + ChatColor.AQUA + "] ";
        } else if (rank.equalsIgnoreCase("MVP")) {
            s = ChatColor.AQUA + "[MVP] ";
        } else if (rank.equalsIgnoreCase("VIP_PLUS")) {
            s = ChatColor.GREEN + "[VIP" + ChatColor.GOLD + "+" + ChatColor.GREEN + "] ";
        } else if (rank.equalsIgnoreCase("VIP")) {
            s = ChatColor.GREEN + "[VIP] ";
        }
        // If no rank matches, keep default gray color (s = "§7")

        this.playerRank = s;
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    /** A colour Hypixel names that this enum does not know would otherwise fail the whole player. */
    private static ChatColor colourOr(String name, ChatColor fallback) {
        try {
            return ChatColor.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public String getPlayerRank() {
        String baseRank = this.playerRank == null ? "" : this.playerRank;

        // If player is detected as nicked, prepend nick indicator
        if (isNicked()) {
            return ChatColor.WHITE + "[" + ChatColor.RED + "NICKED" + ChatColor.WHITE + "] " + baseRank;
        }

        return baseRank;
    }

    public String getPlayerRankColor() {
        if (this.playerRank == null || this.playerRank.length() < 2) {
            return ChatColor.RESET.toString();
        }

        return this.playerRank.substring(0, 2);
    }
}
