package tabstats.playerapi.api.games.skywars;

import com.google.gson.JsonObject;
import tabstats.playerapi.api.games.HypixelGames;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatInt;
import tabstats.playerapi.api.stats.StatString;
import tabstats.util.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class Skywars extends SkywarsUtil {

    public Skywars(String playerName, String playerUUID, JsonObject player) {
        super(playerName, playerUUID);

        JsonObject skywarsJson = gameStats(player, HypixelGames.SKYWARS);
        if (skywarsJson == null) {
            // No stats: the list stays empty, so they show only their name
            return;
        }

        int wins = new StatInt("Wins", "wins", skywarsJson).getValue();
        int losses = new StatInt("Losses", "losses", skywarsJson).getValue();
        int kills = new StatInt("Kills", "kills", skywarsJson).getValue();
        int deaths = new StatInt("Deaths", "deaths", skywarsJson).getValue();

        double kdr = ratio(kills, deaths);
        double wlr = ratio(wins, losses);

        List<Stat> stats = new ArrayList<>();
        stats.add(new StatString("STAR", buildStarDisplay(skywarsJson)));
        stats.add(new StatString("KDR", this.getKdrColor(kdr).toString() + kdr));
        stats.add(new StatString("KILLS", this.getKillsColor(kills).toString() + kills));
        stats.add(new StatString("WLR", this.getWlrColor(wlr).toString() + wlr));
        stats.add(new StatString("WINS", this.getWinsColor(wins).toString() + wins));
        setFormattedStats(stats);
    }

    @Override
    public HypixelGames getGame() {
        return HypixelGames.SKYWARS;
    }

    private static String buildStarDisplay(JsonObject skywarsJson) {
        // Only use Hypixel's preformatted string; strip brackets so only number + glyph remain
        String formatted = string(skywarsJson, "levelFormattedWithBrackets");
        if (formatted != null) {
            // Remove literal square brackets, keep colors and any glyphs
            return formatted.replace("[", "").replace("]", "");
        }

        // If the API doesn't provide it, show a simple placeholder
        return ChatColor.GRAY + "-";
    }
}
