package tabstats.command;

import tabstats.listener.GuiOpenListener;
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
        GuiOpenListener.requestGuiOpen();
    }
}
