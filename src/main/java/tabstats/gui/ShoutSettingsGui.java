package tabstats.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import tabstats.config.ModConfig;
import tabstats.config.ShoutSettings;
import tabstats.util.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for {@code /bw shout}: whether it runs by itself at the start of a game, and the rules
 * that make a player "good" - a minimum per stat, an empty field meaning no rule for that stat.
 * Everything is saved when the screen is left.
 */
public class ShoutSettingsGui extends GuiScreen {
    private static final int ID_AUTO = 1;
    private static final int ID_OWN_TEAM = 2;
    private static final int ID_MATCH = 3;
    private static final int ID_FLAGGED = 4;
    private static final int ID_DONE = 5;

    private static final int WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STRIDE = 24;
    private static final int LABEL_WIDTH = 46;
    private static final int RULE_GAP = 10;
    private static final int RULE_STRIDE = 22;
    private static final int RULE_COLUMNS = 2;

    private final GuiScreen parent;
    private final List<GuiTextField> fields = new ArrayList<>();
    private final List<int[]> labelPositions = new ArrayList<>();
    private int titleY;
    private int hintY;

    public ShoutSettingsGui(GuiScreen parent) {
        this.parent = parent;
    }

    private static ShoutSettings settings() {
        return ModConfig.getInstance().getShoutSettings();
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        this.fields.clear();
        this.labelPositions.clear();

        int ruleRows = (ShoutSettings.RULE_STATS.length + RULE_COLUMNS - 1) / RULE_COLUMNS;
        int blockHeight = 4 * ROW_STRIDE + 14 + ruleRows * RULE_STRIDE + 8 + ROW_HEIGHT;
        int top = Math.max(30, (this.height - blockHeight) / 2);
        int left = this.width / 2 - WIDTH / 2;

        this.titleY = top - 18;
        ShoutSettings settings = settings();
        this.buttonList.add(new GuiButton(ID_AUTO, left, top, WIDTH, ROW_HEIGHT, autoLabel(settings)));
        this.buttonList.add(new GuiButton(ID_OWN_TEAM, left, top + ROW_STRIDE, WIDTH, ROW_HEIGHT, ownTeamLabel(settings)));
        this.buttonList.add(new GuiButton(ID_MATCH, left, top + ROW_STRIDE * 2, WIDTH, ROW_HEIGHT, matchLabel(settings)));
        this.buttonList.add(new GuiButton(ID_FLAGGED, left, top + ROW_STRIDE * 3, WIDTH, ROW_HEIGHT, flaggedLabel(settings)));

        this.hintY = top + ROW_STRIDE * 4 + 2;
        int rulesTop = this.hintY + 12;
        int columnWidth = (WIDTH - RULE_GAP) / RULE_COLUMNS;
        for (int i = 0; i < ShoutSettings.RULE_STATS.length; i++) {
            String stat = ShoutSettings.RULE_STATS[i];
            int x = left + (i % RULE_COLUMNS) * (columnWidth + RULE_GAP);
            int y = rulesTop + (i / RULE_COLUMNS) * RULE_STRIDE;

            GuiTextField field = new GuiTextField(100 + i, this.fontRendererObj, x + LABEL_WIDTH, y,
                    columnWidth - LABEL_WIDTH, ROW_HEIGHT - 2);
            field.setMaxStringLength(8);
            field.setText(format(settings.getMinimum(stat)));
            this.fields.add(field);
            this.labelPositions.add(new int[]{x, y + 5});
        }

        int doneY = rulesTop + ruleRows * RULE_STRIDE + 8;
        this.buttonList.add(new GuiButton(ID_DONE, left, doneY, WIDTH, ROW_HEIGHT, "Done"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ShoutSettings settings = settings();
        switch (button.id) {
            case ID_AUTO:
                settings.setAutoShout(!settings.isAutoShout());
                button.displayString = autoLabel(settings);
                break;
            case ID_OWN_TEAM:
                settings.setIncludeOwnTeam(!settings.isIncludeOwnTeam());
                button.displayString = ownTeamLabel(settings);
                break;
            case ID_MATCH:
                settings.setMatchAll(!settings.isMatchAll());
                button.displayString = matchLabel(settings);
                break;
            case ID_FLAGGED:
                settings.setAlwaysShowFlagged(!settings.isAlwaysShowFlagged());
                button.displayString = flaggedLabel(settings);
                break;
            case ID_DONE:
                close();
                return;
            default:
                return;
        }

        ModConfig.getInstance().save();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            close();
            return;
        }

        for (GuiTextField field : this.fields) {
            if (field.isFocused() && field.textboxKeyTyped(typedChar, keyCode)) {
                /* Only a number makes sense here, so anything else is dropped as it is typed. */
                field.setText(field.getText().replaceAll("[^0-9.]", ""));
                return;
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiTextField field : this.fields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField field : this.fields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawCenteredString(this.fontRendererObj, "Shout Settings", this.width / 2, this.titleY, 0xFFFFFF);
        drawCenteredString(this.fontRendererObj, ChatColor.GRAY + "A good player reaches at least (empty = no rule):",
                this.width / 2, this.hintY, 0xFFFFFF);

        for (int i = 0; i < this.fields.size(); i++) {
            int[] position = this.labelPositions.get(i);
            drawString(this.fontRendererObj, ShoutSettings.RULE_STATS[i], position[0], position[1], 0xAAAAAA);
            this.fields.get(i).drawTextBox();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Takes over the minimums typed in; a field that does not read as a number keeps the old rule. */
    private void close() {
        ShoutSettings settings = settings();
        for (int i = 0; i < this.fields.size(); i++) {
            String text = this.fields.get(i).getText().trim();
            String stat = ShoutSettings.RULE_STATS[i];
            if (text.isEmpty()) {
                settings.setMinimum(stat, null);
                continue;
            }

            try {
                settings.setMinimum(stat, Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                // "1.2.3" and the like - the rule stays as it was
            }
        }

        ModConfig.getInstance().save();
        Minecraft.getMinecraft().displayGuiScreen(this.parent);
    }

    private static String format(Double value) {
        if (value == null) {
            return "";
        }
        return value == Math.floor(value) ? Long.toString(value.longValue()) : Double.toString(value);
    }

    private static String autoLabel(ShoutSettings settings) {
        return "Auto shout at game start: " + onOff(settings.isAutoShout());
    }

    private static String ownTeamLabel(ShoutSettings settings) {
        return "Own team: " + (settings.isIncludeOwnTeam() ? ChatColor.GREEN + "Included" : ChatColor.RED + "Left out");
    }

    private static String matchLabel(ShoutSettings settings) {
        return "Good means: " + ChatColor.YELLOW + (settings.isMatchAll() ? "every rule met" : "any rule met");
    }

    private static String flaggedLabel(ShoutSettings settings) {
        return "Nicks & Urchin tags: " + (settings.isAlwaysShowFlagged()
                ? ChatColor.GREEN + "Always shown" : ChatColor.YELLOW + "Rules apply");
    }

    private static String onOff(boolean value) {
        return value ? ChatColor.GREEN + "On" : ChatColor.RED + "Off";
    }
}
