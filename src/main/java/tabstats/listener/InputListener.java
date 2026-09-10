package tabstats.listener;

import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.render.StatsTab;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.MouseEvent;
import net.weavemc.api.event.SubscribeEvent;

public class InputListener {

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.getDWheel() == 0) {
            return;
        }

        if (!ModConfig.getInstance().isModEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        if (!mc.gameSettings.keyBindPlayerList.isKeyDown() || mc.currentScreen != null) {
            return;
        }

        if (mc.getNetHandler() == null) {
            return;
        }

        GameOverlayListener overlayListener = TabStats.getTabStats().getGameOverlayListener();
        if (overlayListener == null) {
            return;
        }

        StatsTab statsTab = overlayListener.getStatsTab();
        if (statsTab == null) {
            return;
        }

        statsTab.handleMouseWheel(event.getDWheel(), mc.getNetHandler().getPlayerInfoMap().size());
    }
}
