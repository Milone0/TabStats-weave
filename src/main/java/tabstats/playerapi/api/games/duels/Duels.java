package tabstats.playerapi.api.games.duels;

import tabstats.playerapi.api.games.HypixelGames;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatInt;
import tabstats.playerapi.api.stats.StatString;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class Duels extends DuelsUtil {

    public Duels(String playerName, String playerUUID, JsonObject player) {
        super(playerName, playerUUID);

        JsonObject duelJson = gameStats(player, HypixelGames.DUELS);
        if (duelJson == null) {
            // No stats: the list stays empty, so they show only their name
            return;
        }

        int winstreak = new StatInt("Winstreak", "current_winstreak", duelJson).getValue();
        int bestWinstreak = new StatInt("Best Winstreak", "best_overall_winstreak", duelJson).getValue();
        int wins = new StatInt("Wins", "wins", duelJson).getValue();
        int losses = new StatInt("Losses", "losses", duelJson).getValue();
        int kills = new StatInt("Kills", "kills", duelJson).getValue();
        double wlr = ratio(wins, losses);

        List<Stat> stats = new ArrayList<>();
        stats.add(new StatString("TITLE", buildTitle(duelJson)));
        stats.add(new StatString("WS", this.getWSColor(winstreak).toString() + winstreak));
        stats.add(new StatString("BWS", this.getWSColor(bestWinstreak).toString() + bestWinstreak));
        stats.add(new StatString("KILLS", this.getKillsColor(kills).toString() + kills));
        stats.add(new StatString("WLR", this.getWlrColor(wlr).toString() + wlr));
        stats.add(new StatString("WINS", /* this sets the color >>*/ this.getWinsColor(wins).toString() + /* this is what's actually displayed >>>*/ wins));
        stats.add(new StatString("LOSSES", this.getLossesColor(losses).toString() + losses));
        setFormattedStats(stats);
    }

    @Override
    public HypixelGames getGame() {
        return HypixelGames.DUELS;
    }

    private String buildTitle(JsonObject duelJson) {
        try {
            String title = string(duelJson, "active_cosmetictitle");
            String formatted = this.getFormattedTitle(title == null ? "" : title, duelJson);
            return formatted == null ? "N/A" : formatted;
        } catch (RuntimeException ignored) {
            return "N/A";
        }
    }
}
