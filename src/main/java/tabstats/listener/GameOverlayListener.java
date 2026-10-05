package tabstats.listener;

import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.config.StatColumnLayout;
import tabstats.render.StatsTab;
import tabstats.util.Gamemodes;
import tabstats.util.HypixelLocation;
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
    /** Refreshed every tick rather than every frame: asking for the key checks the config file. */
    private boolean urchinKeySet;

    public GameOverlayListener() {
        this.statsTab = new StatsTab(this.mc, this.mc.ingameGUI);
        this.statsTab.setRenderHeaderFooter(ModConfig.getInstance().isRenderHeaderFooterEnabled());
        this.modEnabled = TabStats.isActive();
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
        this.urchinKeySet = !ModConfig.getInstance().getUrchinApiKey().trim().isEmpty();

        /* Outside of games and pre-game lobbies the vanilla tab list stays in place. */
        if (this.modEnabled && Gamemodes.inSupportedGame()) {
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

        if (gamemode == null || !HypixelLocation.inGame()) {
            // A lobby: the mod has no stats to draw here, so vanilla renders the list.
            return false;
        }

        ScoreObjective effectiveObjective = scoreObjective != null
                ? scoreObjective
                : (effectiveScoreboard == null ? null : effectiveScoreboard.getObjectiveInDisplaySlot(0));

        this.statsTab.renderNewPlayerlist(effectiveScoreboard, effectiveObjective, visibleColumns(gamemode), gamemode);
        return true;
    }

    /**
     * The columns drawn for a gamemode: the layout's enabled ones, in its order. Taken from the
     * layout instead of from the client player's own stats, so the headers do not go missing when
     * those have not loaded, or the client player never played this mode.
     */
    private List<StatColumnLayout.Column> visibleColumns(String gamemode) {
        List<StatColumnLayout.Column> all = ModConfig.getInstance().getStatColumns().getColumns(gamemode);
        List<StatColumnLayout.Column> visible = new ArrayList<>(all.size());
        for (StatColumnLayout.Column column : all) {
            // TAG is only ever filled with an Urchin key
            if (column.isEnabled() && (this.urchinKeySet || !"TAG".equals(column.getKey()))) {
                visible.add(column);
            }
        }
        return visible;
    }

    /** Also hands the vanilla tab list back as soon as the client leaves Hypixel. */
    private void syncModEnabled() {
        boolean active = TabStats.isActive();
        if (active != this.modEnabled) {
            setModEnabled(active);
        }
    }

    private String resolveGamemode(Scoreboard scoreboard) {
        return Gamemodes.resolve(scoreboard);
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
