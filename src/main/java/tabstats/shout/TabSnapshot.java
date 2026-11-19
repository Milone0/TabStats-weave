package tabstats.shout;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;
import tabstats.TabStats;
import tabstats.listener.GameOverlayListener;
import tabstats.playerapi.ChatRevealedPlayer;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.StatWorld;
import tabstats.render.StatsTab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The tab list as it stands right now: every player it shows, in its order, with their team.
 * Spectators without a team are left out - in Bed Wars that is everyone already finally killed.
 * Read it on the client thread.
 */
public final class TabSnapshot {
    private final List<ShoutPlayer> players;
    private final String ownTeam;

    private TabSnapshot(List<ShoutPlayer> players, String ownTeam) {
        this.players = players;
        this.ownTeam = ownTeam;
    }

    public static TabSnapshot take(String gamemode) {
        Minecraft mc = Minecraft.getMinecraft();
        TabStats tabStats = TabStats.getTabStats();
        StatWorld statWorld = tabStats == null ? null : tabStats.getStatWorld();
        GameOverlayListener overlay = tabStats == null ? null : tabStats.getGameOverlayListener();
        StatsTab statsTab = overlay == null ? null : overlay.getStatsTab();
        if (mc == null || mc.thePlayer == null || statWorld == null || statsTab == null) {
            return new TabSnapshot(Collections.<ShoutPlayer>emptyList(), null);
        }

        UUID selfId = mc.thePlayer.getUniqueID();
        String ownTeam = null;
        List<ShoutPlayer> players = new ArrayList<>();
        Set<UUID> listedIds = new HashSet<>();
        Set<String> listedNames = new HashSet<>();

        for (NetworkPlayerInfo info : statsTab.listedPlayers()) {
            GameProfile profile = info.getGameProfile();
            listedIds.add(profile.getId());
            listedNames.add(profile.getName().toLowerCase(Locale.ROOT));

            ScorePlayerTeam scoreTeam = info.getPlayerTeam();
            String team = ShoutText.teamFromPrefix(scoreTeam == null ? null : scoreTeam.getColorPrefix());
            boolean self = profile.getId().equals(selfId);
            if (self) {
                ownTeam = team;
            }

            /*
             * A Bed Wars player waiting to respawn is a spectator for a few seconds but keeps the
             * team; someone finally killed has lost it.
             */
            if (info.getGameType() == WorldSettings.GameType.SPECTATOR && team == null) {
                continue;
            }

            IChatComponent displayName = info.getDisplayName();
            HPlayer player = statWorld.getPlayerByIdentity(profile.getId(),
                    displayName != null ? displayName.getFormattedText() : null, profile.getName());
            players.add(ShoutPlayer.of(profile.getName(), team, self, player, gamemode, false));
        }

        /* The pre-game lobby: players the server hides, known only from what they wrote. */
        for (ChatRevealedPlayer revealed : statWorld.getChatRevealedPlayers()) {
            String name = revealed.getName();
            if (name == null || revealed.getUuid() == null || listedIds.contains(revealed.getUuid())
                    || listedNames.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }

            HPlayer player = statWorld.getPlayerByIdentity(revealed.getUuid(), name);
            players.add(ShoutPlayer.of(name, null, false, player, gamemode, !revealed.hasRealProfile()));
        }

        return new TabSnapshot(Collections.unmodifiableList(players), ownTeam);
    }

    public List<ShoutPlayer> getPlayers() {
        return this.players;
    }

    /** The team of the player themselves, or null outside a game. */
    public String getOwnTeam() {
        return this.ownTeam;
    }

    /** The teams that still have someone alive, in tab order. */
    public Set<String> getTeams() {
        Set<String> teams = new LinkedHashSet<>();
        for (ShoutPlayer player : this.players) {
            if (player.getTeam() != null) {
                teams.add(player.getTeam());
            }
        }
        return teams;
    }

    /** Whether the stats of everyone shown have arrived, nicks counting as arrived. */
    public boolean allLoaded() {
        for (ShoutPlayer player : this.players) {
            if (!player.isLoaded()) {
                return false;
            }
        }
        return true;
    }

    /** A shown player by name, ignoring case, or null. */
    public ShoutPlayer find(String name) {
        for (ShoutPlayer player : this.players) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }
}
