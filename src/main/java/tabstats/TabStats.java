package tabstats;

import tabstats.command.GameStatsCommand;
import tabstats.command.TabStatsCommand;
import tabstats.config.ModConfig;
import tabstats.listener.ChatListener;
import tabstats.listener.GameOverlayListener;
import tabstats.listener.GuiOpenListener;
import tabstats.listener.InputListener;
import tabstats.playerapi.WorldLoader;
import tabstats.util.HypixelServer;
import net.weavemc.api.ModInitializer;
import net.weavemc.api.command.CommandBus;
import net.weavemc.api.event.EventBus;
import net.weavemc.api.event.StartGameEvent;
import net.weavemc.api.event.SubscribeEvent;

import java.util.Arrays;

public class TabStats implements ModInitializer {
    private static TabStats tabStats;
    private WorldLoader statWorld;
    private GameOverlayListener gameOverlayListener;

    /**
     * Weave calls this from the head of {@code Minecraft.main}, so no Minecraft state exists
     * yet -- {@code Minecraft.getMinecraft()} still returns null. Everything that touches the
     * game is therefore deferred to {@link StartGameEvent.Post}, which fires at the tail of
     * {@code Minecraft.startGame()} and is the equivalent of Forge's FMLInitializationEvent.
     */
    @Override
    public void init() {
        tabStats = this;
        EventBus.subscribe(this);
    }

    @SubscribeEvent
    public void onStartGame(StartGameEvent.Post event) {
        ModConfig.getInstance().init();

        this.statWorld = new WorldLoader();
        this.gameOverlayListener = new GameOverlayListener();
        this.registerListeners(statWorld, gameOverlayListener, new GuiOpenListener(), new InputListener(),
                new ChatListener());

        this.applyModEnabled(ModConfig.getInstance().isModEnabled());

        CommandBus.register(new TabStatsCommand(), new GameStatsCommand("bw", "BEDWARS", "BW"));
    }

    private void registerListeners(Object... listeners) {
        Arrays.stream(listeners).forEachOrdered(EventBus::subscribe);
    }

    public static TabStats getTabStats() {
        return tabStats;
    }

    public WorldLoader getStatWorld() {
        return statWorld;
    }

    public GameOverlayListener getGameOverlayListener() {
        return gameOverlayListener;
    }

    public boolean isModEnabled() {
        return ModConfig.getInstance().isModEnabled();
    }

    /**
     * Whether the mod does anything right now: switched on in the menu, and connected to Hypixel.
     * Off Hypixel it disables itself without touching the setting, so it is back on the next join.
     */
    public static boolean isActive() {
        return ModConfig.getInstance().isModEnabled() && HypixelServer.isOnHypixel();
    }

    public void applyModEnabled(boolean enabled) {
        boolean active = enabled && HypixelServer.isOnHypixel();
        if (this.gameOverlayListener != null) {
            this.gameOverlayListener.setModEnabled(active);
        }

        // When re-enabling, refresh the display to show current lobby
        // When disabling, just stop processing - preserve cache for future use
        if (active && this.statWorld != null) {
            this.statWorld.rerenderTabList();
        }
    }
}
