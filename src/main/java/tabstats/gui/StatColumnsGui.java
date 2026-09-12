package tabstats.gui;

import tabstats.config.ModConfig;
import tabstats.config.StatColumnLayout;
import tabstats.util.ChatColor;
import tabstats.util.Gamemodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;
import java.util.Locale;

/**
 * Picks which stat columns the tab shows and in what order, laid out like the vanilla server list:
 * the entry at the top is the column drawn first, and each row carries its own move buttons.
 *
 * <p>The columns differ per gamemode, so the screen edits one gamemode at a time and starts on
 * whichever one the player is currently in.
 */
public class StatColumnsGui extends GuiScreen {
    private static final int ID_GAMEMODE = 1;
    private static final int ID_RESET = 2;
    private static final int ID_DONE = 3;
    /** Row i owns the three ids starting here: toggle, move up, move down. */
    private static final int ROW_ID_BASE = 100;
    private static final int ROW_ID_STRIDE = 3;

    private static final int ROW_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 2;
    private static final int ARROW_WIDTH = 20;
    /** Room between the gamemode button and the first row, where the ordering hint sits. */
    private static final int HINT_GAP = 16;

    private final GuiScreen parent;
    private int gamemodeIndex;
    private int titleY;
    private int listTop;

    public StatColumnsGui(GuiScreen parent) {
        this.parent = parent;
        this.gamemodeIndex = indexOfCurrentGamemode();
    }

    private static int indexOfCurrentGamemode() {
        String current = Gamemodes.resolveCurrent();
        if (current == null) {
            return 0;
        }

        for (int i = 0; i < StatColumnLayout.GAMEMODES.length; i++) {
            if (StatColumnLayout.GAMEMODES[i].equals(current)) {
                return i;
            }
        }

        return 0;
    }

    private String gamemode() {
        return StatColumnLayout.GAMEMODES[this.gamemodeIndex];
    }

    private StatColumnLayout layout() {
        return ModConfig.getInstance().getStatColumns();
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        List<StatColumnLayout.Column> columns = layout().getColumns(gamemode());

        int centerX = this.width / 2;
        int rowStride = ROW_HEIGHT + ROW_GAP;
        int blockHeight = ROW_HEIGHT + HINT_GAP + columns.size() * rowStride + 8 + ROW_HEIGHT;
        int top = Math.max(32, (this.height - blockHeight) / 2);

        this.titleY = top - 22;
        int gamemodeY = top;
        this.listTop = gamemodeY + ROW_HEIGHT + HINT_GAP;

        this.buttonList.add(new GuiButton(ID_GAMEMODE, centerX - ROW_WIDTH / 2, gamemodeY, ROW_WIDTH, ROW_HEIGHT,
                "Gamemode: " + prettyGamemode(gamemode())));

        int toggleWidth = ROW_WIDTH - (ARROW_WIDTH + ROW_GAP) * 2;
        int rowX = centerX - ROW_WIDTH / 2;
        int shownSoFar = 0;

        for (int i = 0; i < columns.size(); i++) {
            StatColumnLayout.Column column = columns.get(i);
            int rowY = this.listTop + i * rowStride;
            int baseId = ROW_ID_BASE + i * ROW_ID_STRIDE;

            if (column.isEnabled()) {
                shownSoFar++;
            }

            this.buttonList.add(new GuiButton(baseId, rowX, rowY, toggleWidth, ROW_HEIGHT,
                    formatRowLabel(column, shownSoFar)));

            GuiButton up = new GuiButton(baseId + 1, rowX + toggleWidth + ROW_GAP, rowY, ARROW_WIDTH, ROW_HEIGHT, "^");
            up.enabled = i > 0;
            this.buttonList.add(up);

            GuiButton down = new GuiButton(baseId + 2, rowX + toggleWidth + ARROW_WIDTH + ROW_GAP * 2, rowY,
                    ARROW_WIDTH, ROW_HEIGHT, "v");
            down.enabled = i < columns.size() - 1;
            this.buttonList.add(down);
        }

        int bottomY = this.listTop + columns.size() * rowStride + 8;
        int halfWidth = (ROW_WIDTH - ROW_GAP * 2) / 2;
        this.buttonList.add(new GuiButton(ID_RESET, rowX, bottomY, halfWidth, ROW_HEIGHT, "Reset"));
        this.buttonList.add(new GuiButton(ID_DONE, rowX + halfWidth + ROW_GAP * 2, bottomY, halfWidth, ROW_HEIGHT, "Done"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DONE) {
            Minecraft.getMinecraft().displayGuiScreen(this.parent);
            return;
        }

        if (button.id == ID_GAMEMODE) {
            this.gamemodeIndex = (this.gamemodeIndex + 1) % StatColumnLayout.GAMEMODES.length;
            initGui();
            return;
        }

        if (button.id == ID_RESET) {
            layout().resetToDefaults(gamemode());
            ModConfig.getInstance().save();
            initGui();
            return;
        }

        if (button.id < ROW_ID_BASE) {
            return;
        }

        int offset = button.id - ROW_ID_BASE;
        int index = offset / ROW_ID_STRIDE;
        int action = offset % ROW_ID_STRIDE;

        boolean changed;
        switch (action) {
            case 0:
                changed = layout().toggle(gamemode(), index);
                break;
            case 1:
                changed = layout().moveUp(gamemode(), index);
                break;
            default:
                changed = layout().moveDown(gamemode(), index);
                break;
        }

        if (changed) {
            ModConfig.getInstance().save();
            initGui();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            Minecraft.getMinecraft().displayGuiScreen(this.parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawCenteredString(this.fontRendererObj, "Stat Columns", this.width / 2, this.titleY, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(this.fontRendererObj, ChatColor.GRAY + "Top of the list is drawn first",
                this.width / 2, this.listTop - 12, 0xFFFFFF);
    }

    /**
     * @param position the column's place among the shown ones, so the numbering matches what the
     *                 tab actually draws rather than the raw list index
     */
    private String formatRowLabel(StatColumnLayout.Column column, int position) {
        String name = column.getName().trim();
        if (column.isEnabled()) {
            return ChatColor.GREEN + (position + ". " + name);
        }

        return ChatColor.DARK_GRAY + (name + " (hidden)");
    }

    private String prettyGamemode(String gamemode) {
        if (gamemode == null || gamemode.isEmpty()) {
            return "";
        }

        return gamemode.charAt(0) + gamemode.substring(1).toLowerCase(Locale.ROOT);
    }
}
