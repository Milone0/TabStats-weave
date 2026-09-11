package tabstats.listener;

import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.StatWorld;
import tabstats.playerapi.api.stats.Stat;
import tabstats.render.StatsTab;
import tabstats.util.ChatColor;
import tabstats.util.Gamemodes;
import tabstats.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameOverlayListener {
    private static final String[] OVERLAY_FIELD = {"overlayPlayerList", "field_175196_v"};
    private static final String[] HEADER_FIELD = {"header", "field_175256_i"};
    private static final String[] FOOTER_FIELD = {"footer", "field_175255_h"};

    private final StatsTab statsTab;
    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean overlayInjected = false;
    private GuiPlayerTabOverlay originalOverlay;
    private boolean modEnabled;

    public GameOverlayListener() {
        this.statsTab = new StatsTab(this.mc, this.mc.ingameGUI);
        this.statsTab.setRenderHeaderFooter(ModConfig.getInstance().isRenderHeaderFooterEnabled());
        this.modEnabled = ModConfig.getInstance().isModEnabled();
    }

    /**
     * Gets the StatsTab instance for external access
     */
    public StatsTab getStatsTab() {
        return this.statsTab;
    }

    /*
     * Forge let us cancel just the PLAYER_LIST element of the HUD. The Weave equivalent of
     * RenderGameOverlayEvent covers the whole overlay, so instead the custom overlay object is
     * swapped into GuiIngame and drives rendering itself -- see StatsTab.renderPlayerlist.
     * Keeping the swap in a tick handler means it survives the mod being toggled at runtime.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (this.mc.thePlayer == null) {
            return;
        }

        syncModEnabled();

        if (this.modEnabled) {
            ensureCustomOverlayInjected();
        } else {
            restoreOriginalOverlay();
        }
    }

    /**
     * Renders the stats tab list, in place of the vanilla player list.
     * Called by StatsTab.renderPlayerlist.
     *
     * @return false when the caller should fall back to vanilla rendering.
     */
    public boolean renderTab(Scoreboard scoreboard, ScoreObjective scoreObjective) {
        if (this.mc.thePlayer == null) {
            return false;
        }

        syncModEnabled();

        if (!this.modEnabled) {
            return false;
        }

        Scoreboard effectiveScoreboard = scoreboard != null ? scoreboard : this.mc.thePlayer.getWorldScoreboard();
        String gamemode = resolveGamemode(effectiveScoreboard);
        boolean supportedGamemode = gamemode != null;

        StatWorld statWorld = TabStats.getTabStats().getStatWorld();
        HPlayer theHPlayer = statWorld == null ? null : statWorld.getPlayerByUUID(this.mc.thePlayer.getUniqueID());

        List<Stat> gameStatTitleList;
        if (!supportedGamemode || theHPlayer == null) {
            gameStatTitleList = Collections.emptyList();
        } else {
            List<Stat> stats = theHPlayer.getFormattedGameStats(gamemode);
            if (stats == null) {
                gameStatTitleList = new ArrayList<>();
            } else {
                gameStatTitleList = stats;
            }
        }

        int width = computeTabWidth(gameStatTitleList);
        ScoreObjective effectiveObjective = scoreObjective != null
                ? scoreObjective
                : (effectiveScoreboard == null ? null : effectiveScoreboard.getObjectiveInDisplaySlot(0));

        this.statsTab.renderNewPlayerlist(width, effectiveScoreboard, effectiveObjective, gameStatTitleList, supportedGamemode ? gamemode : null);
        return true;
    }

    private void syncModEnabled() {
        boolean configEnabled = ModConfig.getInstance().isModEnabled();
        if (configEnabled != this.modEnabled) {
            setModEnabled(configEnabled);
        }
    }

    private String resolveGamemode(Scoreboard scoreboard) {
        return Gamemodes.resolve(scoreboard);
    }

    private int computeTabWidth(List<Stat> stats) {
        int width = (StatsTab.headSize + 2) * 2 + this.mc.fontRendererObj.getStringWidth(ChatColor.BOLD + "[YOUTUBE] WWWWWWWWWWWWWWWW") + 10 - 10;

        for (Stat stat : stats) {
            if (stat == null) {
                continue;
            }

            String statName = stat.getStatName();
            if (statName == null) {
                continue;
            }

            width += this.mc.fontRendererObj.getStringWidth(ChatColor.BOLD + statName) + 10;
        }

        return width;
    }

    private static Field overlayField() {
        Field field = Reflect.field(GuiIngame.class, OVERLAY_FIELD);
        return field != null ? field : Reflect.fieldOfType(GuiIngame.class, GuiPlayerTabOverlay.class);
    }

    private void ensureCustomOverlayInjected() {
        if (!this.modEnabled || this.overlayInjected) {
            return;
        }

        GuiIngame guiIngame = this.mc.ingameGUI;
        if (guiIngame == null) {
            return;
        }

        Field field = overlayField();
        if (field == null) {
            return;
        }

        GuiPlayerTabOverlay currentOverlay = Reflect.get(field, guiIngame);

        if (this.originalOverlay == null && currentOverlay != this.statsTab) {
            this.originalOverlay = currentOverlay;
        }

        if (currentOverlay == this.statsTab) {
            this.overlayInjected = true;
            return;
        }

        if (!Reflect.set(field, guiIngame, this.statsTab)) {
            return;
        }

        if (currentOverlay != null) {
            IChatComponent currentHeader = Reflect.get(Reflect.field(GuiPlayerTabOverlay.class, HEADER_FIELD), currentOverlay);
            IChatComponent currentFooter = Reflect.get(Reflect.field(GuiPlayerTabOverlay.class, FOOTER_FIELD), currentOverlay);

            if (currentHeader != null) {
                this.statsTab.setHeader(currentHeader.createCopy());
            }

            if (currentFooter != null) {
                this.statsTab.setFooter(currentFooter.createCopy());
            }
        }

        this.overlayInjected = true;
    }

    public void setModEnabled(boolean enabled) {
        if (this.modEnabled == enabled) {
            return;
        }

        this.modEnabled = enabled;

        if (!enabled) {
            restoreOriginalOverlay();
            this.statsTab.resetScroll();
        } else {
            this.overlayInjected = false;
            this.statsTab.setRenderHeaderFooter(ModConfig.getInstance().isRenderHeaderFooterEnabled());
        }
    }

    private void restoreOriginalOverlay() {
        if (!this.overlayInjected) {
            return;
        }

        GuiIngame guiIngame = this.mc.ingameGUI;
        if (guiIngame == null) {
            return;
        }

        Field field = overlayField();
        if (field != null) {
            GuiPlayerTabOverlay target = this.originalOverlay != null ? this.originalOverlay : new GuiPlayerTabOverlay(this.mc, guiIngame);
            if (this.originalOverlay == null) {
                this.originalOverlay = target;
            }
            Reflect.set(field, guiIngame, target);
        }

        this.overlayInjected = false;
    }
}
