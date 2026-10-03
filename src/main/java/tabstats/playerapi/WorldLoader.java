package tabstats.playerapi;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;
import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.listener.GameOverlayListener;
import tabstats.util.ChatColor;
import tabstats.util.Gamemodes;
import tabstats.util.HypixelLocation;
import tabstats.util.PartyTracker;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class WorldLoader extends StatWorld {
    private final Minecraft mc = Minecraft.getMinecraft();
    private World lastObservedWorld;
    private static final Pattern VALID_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private boolean lastModEnabled = ModConfig.getInstance().isModEnabled();

    public boolean loadOrRender(EntityPlayer player) {
        if (player == null) return false;

        UUID uuid = player.getUniqueID();
        if (uuid == null) {
            return false;
        }

        String baseName = player.getName();
        if (baseName == null || !VALID_USERNAME.matcher(baseName).matches()) {
            return false;
        }

        IChatComponent displayComponent = player.getDisplayName();
        if (displayComponent != null) {
            String stripped = ChatColor.stripColor(displayComponent.getFormattedText());
            if (stripped != null && stripped.trim().startsWith("[NPC]")) {
                return false;
            }
        }

    // Allow only UUID versions we care about: 4 (real), 2 (lobby/replay), 1 (nicked). Version 3 = holograms/NPCs.
        int version = uuid.version();
        return version == 4 || version == 2 || version == 1;
    }

    /** Version 1 UUIDs are always nicked on Hypixel, so there is nothing to ask the API. */
    private void markNicked(EntityPlayer entityPlayer) {
        UUID uuid = entityPlayer.getUniqueID();
        HPlayer hPlayer = new HPlayer(uuid.toString().replace("-", ""), entityPlayer.getName());
        hPlayer.setNicked(true);
        this.addPlayer(uuid, hPlayer);
        this.removeFromStatAssembly(uuid);
    }

    /* populates and checks the stat world player cache every client tick */
    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        /* Ahead of every gate below: the stat commands work wherever the player is. */
        PartyTracker.tick();

        if (!ModConfig.getInstance().isModEnabled()) {
            // Just reset scroll when disabling, preserve cache
            if (lastModEnabled) {
                resetTabScroll();
            }
            lastModEnabled = false;
            return;
        }

        lastModEnabled = true;
        World currentWorld = mc.theWorld;

        if (currentWorld != lastObservedWorld) {
            lastObservedWorld = currentWorld;
            // A new world is a new Hypixel server, so where we are has to be asked again.
            HypixelLocation.reset();
            // Only reset scroll position on world change, preserve cache
            resetTabScroll();
            // Names picked up from chat belong to the lobby we just left
            this.clearChatRevealed();
        }

        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        HypixelLocation.tick();

        /*
         * Lobbies resolve to no gamemode. Nothing would be rendered there, so nothing is fetched
         * either - a full lobby would otherwise burn a three-digit number of API calls for stats
         * that never reach the screen.
         */
        if (!Gamemodes.inSupportedGame()) {
            return;
        }

        boolean added = false;
        for (EntityPlayer entityPlayer : mc.theWorld.playerEntities) {
            UUID uuid = entityPlayer.getUniqueID();

            // The cheap checks first: nearly every player is already cached or on its way
            if (uuid == null || this.getWorldPlayers().containsKey(uuid) || this.statAssembly.contains(uuid)) {
                continue;
            }

            if (!loadOrRender(entityPlayer) || !this.statAssembly.add(uuid)) {
                continue;
            }

            if (uuid.version() == 1) {
                this.markNicked(entityPlayer);
            } else {
                this.fetchStats(entityPlayer);
            }
            added = true;
        }

        if (added) {
            checkCacheSize();
        }
    }

    /** Past the bound, keeps only the players that are in this world (and the chat reveals). */
    private void checkCacheSize() {
        if (getWorldPlayers().size() <= MAX_CACHED_PLAYERS) {
            return;
        }

        Set<UUID> present = new HashSet<>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            present.add(player.getUniqueID());
        }
        retainPlayers(present);
    }

    private void resetTabScroll() {
        TabStats instance = TabStats.getTabStats();
        if (instance == null) {
            return;
        }

        GameOverlayListener overlayListener = instance.getGameOverlayListener();
        if (overlayListener == null || overlayListener.getStatsTab() == null) {
            return;
        }

        overlayListener.getStatsTab().resetScroll();
    }
}
