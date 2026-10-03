package tabstats.playerapi.api.games.bedwars;

import tabstats.playerapi.api.games.HypixelGames;
import tabstats.playerapi.api.games.bedwars.BedwarsUtil.CachedUrchinTag;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatInt;
import tabstats.playerapi.api.stats.StatString;
import tabstats.util.ChatColor;
import tabstats.util.Handler;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bedwars extends BedwarsUtil {
    /** A failed Urchin lookup is tried again this often, this much later each time. */
    private static final int URCHIN_MAX_RETRIES = 3;
    private static final long URCHIN_RETRY_DELAY_MS = 15_000L;

    /** Null when no Urchin key was set while this player was loaded. */
    private final StatString tagStat;
    private final AtomicBoolean urchinLookupScheduled = new AtomicBoolean();
    private int urchinRetries;

    public Bedwars(String playerName, String playerUUID, JsonObject player) {
        super(playerName, playerUUID);

        JsonObject bedwarsJson = gameStats(player, HypixelGames.BEDWARS);
        // "bedwars_level" is the star. Unlike every other Bedwars stat it lives in the achievements.
        int star = new StatInt("Level", "bedwars_level", child(player, "achievements")).getValue();

        List<Stat> stats = new ArrayList<>();
        if (bedwarsJson == null) {
            // No Bedwars stats at all: only the star column, which reads "-"
            this.tagStat = null;
            stats.add(new StatString("STAR", this.getStarWithColor(star)));
            setFormattedStats(stats);
            return;
        }

        StatInt winstreak = new StatInt("Winstreak", "winstreak", bedwarsJson);
        int finalKills = new StatInt("Final Kills", "final_kills_bedwars", bedwarsJson).getValue();
        int finalDeaths = new StatInt("Final Deaths", "final_deaths_bedwars", bedwarsJson).getValue();
        int wins = new StatInt("Wins", "wins_bedwars", bedwarsJson).getValue();
        int losses = new StatInt("Losses", "losses_bedwars", bedwarsJson).getValue();
        int bedsBroken = new StatInt("Beds Broken", "beds_broken_bedwars", bedwarsJson).getValue();
        int bedsLost = new StatInt("Beds Lost", "beds_lost_bedwars", bedwarsJson).getValue();

        double fkdr = ratio(finalKills, finalDeaths);
        double wlr = ratio(wins, losses);
        double bblr = ratio(bedsBroken, bedsLost);

        // TAG stays the first column, so it is added before everything else
        this.tagStat = getActiveUrchinApiKey().isEmpty() ? null : new StatString("TAG", "");
        if (this.tagStat != null) {
            stats.add(this.tagStat);
        }

        stats.add(new StatString("STAR", this.getStarWithColor(star)));
        stats.add(new StatString("WS", formatWsValue(winstreak)));
        stats.add(new StatString("FKDR", this.getFkdrColor(fkdr).toString() + fkdr));
        stats.add(new StatString("FINALS", /* this sets the color >>*/ this.getFinalsColor(finalKills).toString() + /* this is what's actually displayed >>>*/ finalKills));
        stats.add(new StatString("WLR", this.getWlrColor(wlr).toString() + wlr));
        stats.add(new StatString("WINS", this.getWinsColor(wins).toString() + wins));
        stats.add(new StatString("BBLR", this.getBblrColor(bblr).toString() + bblr));
        setFormattedStats(stats);

        if (this.tagStat != null) {
            scheduleUrchinLookup();
        }
    }

    @Override
    public HypixelGames getGame() {
        return HypixelGames.BEDWARS;
    }

    private void scheduleUrchinLookup() {
        if (getActiveUrchinApiKey().isEmpty()) {
            return;
        }

        String identity = getLookupIdentity();
        if (identity == null) {
            return;
        }

        CachedUrchinTag cached = getCachedUrchinTag(identity);
        if (cached != null) {
            applyUrchinResult(cached);
            return;
        }

        if (!this.urchinLookupScheduled.compareAndSet(false, true)) {
            return;
        }

        enqueueUrchinLookup(identity, result -> {
            this.urchinLookupScheduled.set(false);
            if (result.isPending()) {
                retryUrchinLookupLater();
            } else {
                applyUrchinResult(result);
            }
        });
    }

    /** Urchin did not answer. The TAG cell stays empty until a later attempt gets through. */
    private void retryUrchinLookupLater() {
        if (this.urchinRetries >= URCHIN_MAX_RETRIES) {
            return;
        }

        this.urchinRetries++;
        Handler.schedule(this::scheduleUrchinLookup, URCHIN_RETRY_DELAY_MS * this.urchinRetries);
    }

    private void applyUrchinResult(CachedUrchinTag data) {
        if (data == null) {
            return;
        }

        this.tagStat.setValue(data.getDisplayValue());
        announceTagIfNeeded(data);
    }

    private String formatWsValue(StatInt wsStat) {
        if (!wsStat.isLoadedValue()) {
            return ChatColor.GRAY + "-";
        }

        int value = wsStat.getValue();
        return this.getWSColor(value).toString() + value;
    }
}
