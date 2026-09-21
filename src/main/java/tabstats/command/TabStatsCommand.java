package tabstats.command;

import tabstats.listener.GuiOpenListener;
import tabstats.util.ChatColor;
import tabstats.util.Gamemodes;
import tabstats.util.HypixelLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.weavemc.api.command.Command;

public class TabStatsCommand extends Command {

    public TabStatsCommand() {
        super("tabstats", "ts");
    }

    /**
     * Weave passes the command name itself as args[0], so a bare "/tabstats" is length 1.
     */
    @Override
    public void execute(String[] args) {
        if (args.length > 1 && "where".equalsIgnoreCase(args[1])) {
            printLocation();
            return;
        }

        GuiOpenListener.requestGuiOpen();
    }

    /** {@code /tabstats where} - says why the mod is active here, or why it is not. */
    private void printLocation() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }

        String gamemode = Gamemodes.resolveCurrent();
        String message = ChatColor.GOLD + "[TabStats] " + ChatColor.GRAY + "scoreboard: "
                + ChatColor.WHITE + (gamemode == null ? "no supported game" : gamemode)
                + ChatColor.GRAY + " | locraw: " + ChatColor.WHITE + HypixelLocation.describe()
                + ChatColor.GRAY + " | stats: "
                + (Gamemodes.inSupportedGame() ? ChatColor.GREEN + "on" : ChatColor.RED + "off");

        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }
}
