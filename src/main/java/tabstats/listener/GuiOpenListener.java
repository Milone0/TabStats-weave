package tabstats.listener;

import tabstats.gui.TabStatsGui;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

public class GuiOpenListener {
    private static boolean shouldOpenGui = false;

    public static void requestGuiOpen() {
        shouldOpenGui = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (!shouldOpenGui) {
            return;
        }

        shouldOpenGui = false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null) {
            mc.displayGuiScreen(new TabStatsGui());
        }
    }
}
