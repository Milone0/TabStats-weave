package tabstats.render;

import tabstats.TabStats;
import tabstats.config.StatColumnLayout;
import tabstats.playerapi.ChatRevealedPlayer;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.StatWorld;
import tabstats.listener.GameOverlayListener;
import tabstats.playerapi.api.stats.Stat;
import tabstats.util.ChatColor;
import tabstats.util.StatFormatting;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldSettings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class StatsTab extends GuiPlayerTabOverlay {
    private static final Ordering<NetworkPlayerInfo> field_175252_a = Ordering.from(new StatsTab.PlayerComparator());
    private static final int MAX_TAB_PLAYERS = 80;
    private static final Pattern VALID_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String MAX_RANK_SAMPLE = ChatColor.BOLD + "[YOUTUBE] WWWWWWWWWWWWWWWW";
    private final Minecraft mc;
    private final GuiIngame guiIngame;
    private IChatComponent footer;
    private IChatComponent header;
    /** The amount of time since the playerlist was opened (went from not being rendered, to being rendered) */
    private long lastTimeOpened;
    private boolean renderHeaderFooter = true;
    private final int entryHeight = 12;
    private final int backgroundBorderSize = 12;
    public static final int headSize = 12;
    
    // Scrolling variables for smooth tab list navigation
    private float scrollOffset = 0.0f;
    private float targetScrollOffset = 0.0f;
    private int maxVisiblePlayers = 0;
    private final float scrollSpeed = 0.2f; // Animation smoothness factor
    private int lastPlayerListSize = 0;
    /** When this list was last drawn, so the scroll wheel only acts while it is on screen. */
    private long lastRenderedAt;
    /** Tab entries the mod makes up for players that only chat revealed, keyed by their UUID. */
    private final Map<UUID, NetworkPlayerInfo> syntheticInfos = new HashMap<>();
    /** Pixel widths of stat values. They hardly change between frames, and measuring is not free. */
    private final Map<String, Integer> textWidths = new HashMap<>();
    private static final int MAX_CACHED_WIDTHS = 4096;
    /** The vanilla copies of these are private, so the hearts animation keeps its own. */
    private boolean beingRendered;

    public StatsTab(Minecraft mcIn, GuiIngame guiIngameIn) {
        super(mcIn, guiIngameIn);
        this.mc = mcIn;
        this.guiIngame = guiIngameIn;
    }

    public void setRenderHeaderFooter(boolean renderHeaderFooterIn) {
        this.renderHeaderFooter = renderHeaderFooterIn;
    }

    @Override
    public void setHeader(IChatComponent headerIn) {
        super.setHeader(headerIn);
        this.header = headerIn;
    }

    @Override
    public void setFooter(IChatComponent footerIn) {
        super.setFooter(footerIn);
        this.footer = footerIn;
    }

    @Override
    public void resetFooterHeader() {
        super.resetFooterHeader();
        this.header = null;
        this.footer = null;
    }

    @Override
    public void updatePlayerList(boolean willBeRendered) {
        super.updatePlayerList(willBeRendered);
        if (willBeRendered && !this.beingRendered) {
            this.lastTimeOpened = Minecraft.getSystemTime();
        }
        this.beingRendered = willBeRendered;
    }
    
    /**
     * Calculates the maximum number of players that can fit on screen before overflow
     * @param scaledRes The current scaled resolution
     * @param startingY The Y position where player entries start
     * @param footerHeight Total pixel height reserved for the footer
     * @param footerSpacing Spacer between player entries and the footer
     * @return Maximum players that fit on screen
     */
    private int calculateMaxVisiblePlayers(ScaledResolution scaledRes, int startingY, int footerHeight, int footerSpacing) {
        // Available height = screen height - starting position - bottom padding and footer area
        int availableHeight = scaledRes.getScaledHeight() - startingY - this.backgroundBorderSize - this.entryHeight - footerHeight - footerSpacing;

        if (availableHeight < 0) {
            availableHeight = 0;
        }

        // Calculate how many complete entries fit, minimum 1
        return Math.max(1, availableHeight / (this.entryHeight + 1));
    }
    
    /**
     * Updates the scroll animation by interpolating towards the target offset
     */
    private void updateScrollAnimation() {
        // Smooth interpolation towards target
        float delta = targetScrollOffset - scrollOffset;
        scrollOffset += delta * scrollSpeed;
        
        // Snap to target when very close to avoid floating point precision issues
        if (Math.abs(delta) < 0.01f) {
            scrollOffset = targetScrollOffset;
        }
    }
    
    /**
     * Handles mouse wheel input for scrolling
     * @param wheelDelta The scroll wheel delta (positive = scroll up, negative = scroll down)
     * @return true when the list is on screen and long enough to scroll, so the wheel belongs to
     *         it - and must not also switch the hotbar slot
     */
    public boolean handleMouseWheel(int wheelDelta) {
        if (!isShowing() || maxVisiblePlayers <= 0 || maxVisiblePlayers >= this.lastPlayerListSize) {
            // Not drawn right now, or all players fit on screen
            return false;
        }

        // Scroll by 1 player per wheel notch
        targetScrollOffset += wheelDelta > 0 ? -1 : 1;

        // Clamp to valid bounds derived from the last rendered list size
        targetScrollOffset = MathHelper.clamp_float(targetScrollOffset, 0, Math.max(0, this.lastPlayerListSize - maxVisiblePlayers));
        return true;
    }

    /** Whether this list was drawn a moment ago, i.e. the tab key is held in a supported game. */
    public boolean isShowing() {
        return Minecraft.getSystemTime() - this.lastRenderedAt < 250L;
    }

    /**
     * Resets scroll position to top
     */
    public void resetScroll() {
        scrollOffset = 0.0f;
        targetScrollOffset = 0.0f;
    }
    
    /**
     * @param columns the stat columns to draw, in order. They come from the column layout rather
     *                than from anyone's stats, so the headers are there no matter whose stats
     *                have loaded, and each value is drawn under the column of the same name.
     */
    public void renderNewPlayerlist(Scoreboard scoreboardIn, ScoreObjective scoreObjectiveIn, List<StatColumnLayout.Column> columns, String gamemode) {
        this.lastRenderedAt = Minecraft.getSystemTime();
        NetHandlerPlayClient netHandler = this.mc.thePlayer.sendQueue;
        StatWorld statWorld = TabStats.getTabStats().getStatWorld();
        List<NetworkPlayerInfo> playerList = collectEligiblePlayers(netHandler, statWorld);
        Map<UUID, ChatRevealedPlayer> chatRows = appendChatRevealed(playerList, statWorld);

        ScaledResolution scaledRes = new ScaledResolution(this.mc);
        int baseY = 20;
        int fontHeight = this.mc.fontRendererObj.FONT_HEIGHT;
        int textColor = ChatColor.WHITE.getRGB();

        TextBlock headerBlock = createTextBlock(this.renderHeaderFooter ? this.header : null);
        TextBlock footerBlock = createTextBlock(this.renderHeaderFooter ? this.footer : null);
        int headerHeight = headerBlock.height(fontHeight);
        int footerHeight = footerBlock.height(fontHeight);
        int footerSpacing = footerBlock.hasLines() ? 1 : 0;

        int startingY = baseY + headerHeight + (headerBlock.hasLines() ? 1 : 0);

        String objectiveName = "";
        if (scoreObjectiveIn != null) {
            objectiveName = WordUtils.capitalize(scoreObjectiveIn.getDisplayName().replace("_", ""));
        }
        int objectiveLabelWidth = objectiveName.isEmpty() ? 0 : 5 + this.mc.fontRendererObj.getStringWidth(objectiveName);
        if (scoreObjectiveIn != null && scoreObjectiveIn.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS) {
            // Health is drawn inside the objective column, which needs the room for it
            objectiveLabelWidth = Math.max(objectiveLabelWidth, 5 + this.mc.fontRendererObj.getStringWidth("20.0hp"));
        }

        playerList = playerList.subList(0, Math.min(playerList.size(), MAX_TAB_PLAYERS));
        int playerListSize = playerList.size();
        this.lastPlayerListSize = playerListSize;

        // Every row is resolved up front: the column widths depend on all of them
        List<Row> rows = new ArrayList<>(playerListSize);
        for (NetworkPlayerInfo playerInfo : playerList) {
            rows.add(resolveRow(playerInfo, statWorld, chatRows, gamemode));
        }

        int nameColumnWidth = this.mc.fontRendererObj.getStringWidth(MAX_RANK_SAMPLE) + 10;
        List<StatColumn> statColumns = buildStatColumns(columns, rows);
        int width = headSize + 2 + nameColumnWidth + 4;
        for (StatColumn column : statColumns) {
            width += column.width;
        }

        this.maxVisiblePlayers = calculateMaxVisiblePlayers(scaledRes, startingY, footerHeight, footerSpacing);

        targetScrollOffset = MathHelper.clamp_float(targetScrollOffset, 0.0f, Math.max(0, playerListSize - maxVisiblePlayers));
        if (scrollOffset < 0.0f) {
            scrollOffset = 0.0f;
        }

        updateScrollAnimation();

        int startIndex = Math.max(0, Math.min((int)Math.floor(scrollOffset), playerListSize - maxVisiblePlayers));
        int endIndex = Math.min(playerListSize, startIndex + maxVisiblePlayers);
        List<Row> visibleRows = rows.subList(startIndex, endIndex);
        int visiblePlayerCount = visibleRows.size();

        width = Math.max(width, Math.max(headerBlock.getMaxWidth(), footerBlock.getMaxWidth()));

        int totalContentWidth = width + objectiveLabelWidth;
        int leftBound = scaledRes.getScaledWidth() / 2 - totalContentWidth / 2;
        int startingX = leftBound + objectiveLabelWidth;
        int contentRight = startingX + width;

        int textBaselineOffset = this.entryHeight / 2 - 4;
        int playerSectionHeight = (visiblePlayerCount + 1) * (this.entryHeight + 1);
        drawRect(
                leftBound - this.backgroundBorderSize,
                baseY - this.backgroundBorderSize,
                leftBound + totalContentWidth + this.backgroundBorderSize,
                startingY + playerSectionHeight - 1 + footerSpacing + footerHeight + this.backgroundBorderSize,
                Integer.MIN_VALUE
        );

        drawRect(startingX, startingY, contentRight, startingY + this.entryHeight, 553648127);

        int contentCenterX = startingX + Math.round(width / 2.0f);
        drawCenteredLines(headerBlock, baseY, contentCenterX, fontHeight, textColor);

        int nameColumnStartX = startingX + headSize + 2;
        this.mc.fontRendererObj.drawStringWithShadow(ChatColor.BOLD + "NAME", nameColumnStartX, startingY + textBaselineOffset, textColor);
        this.mc.fontRendererObj.drawStringWithShadow(objectiveName, startingX - objectiveLabelWidth, startingY + textBaselineOffset, textColor);

        int statColumnStartX = nameColumnStartX + nameColumnWidth;
        drawStatHeaders(statColumns, statColumnStartX, startingY + textBaselineOffset, textColor);

        int headerBottomY = startingY + this.entryHeight + 1;
        int ySpacer = headerBottomY - (int)(MathHelper.clamp_float(scrollOffset - startIndex, 0.0f, 0.999f) * (this.entryHeight + 1));

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                0,
                0,
                scaledRes.getScaledWidth() * scaledRes.getScaleFactor(),
                (scaledRes.getScaledHeight() - headerBottomY) * scaledRes.getScaleFactor()
        );

        for (Row row : visibleRows) {
            NetworkPlayerInfo playerInfo = row.info;
            int xSpacer = startingX;
            drawRect(xSpacer, ySpacer, contentRight, ySpacer + this.entryHeight, 553648127);

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

            String name = this.getPlayerName(playerInfo);
            GameProfile gameProfile = playerInfo.getGameProfile();
            ChatRevealedPlayer chatRow = row.chatRow;
            /* A nicked player's UUID is a local placeholder, so no skin can be loaded for it. */
            boolean hasSkin = chatRow == null || chatRow.hasRealProfile();

            if (hasSkin && (this.mc.isIntegratedServerRunning() || this.mc.getNetHandler().getNetworkManager().getIsencrypted()) && playerInfo.getLocationSkin() != null) {
                EntityPlayer entityPlayer = this.mc.theWorld.getPlayerEntityByUUID(gameProfile.getId());
                boolean upsideDown = entityPlayer != null && entityPlayer.isWearing(EnumPlayerModelParts.CAPE) && ("Dinnerbone".equals(gameProfile.getName()) || "Grumm".equals(gameProfile.getName()));
                this.mc.getTextureManager().bindTexture(playerInfo.getLocationSkin());
                int u = 8 + (upsideDown ? 8 : 0);
                int v = 8 * (upsideDown ? -1 : 1);
                Gui.drawScaledCustomSizeModalRect(xSpacer, ySpacer, 8.0F, u, 8, v, headSize, headSize, 64.0F, 64.0F);

                if (entityPlayer != null && entityPlayer.isWearing(EnumPlayerModelParts.HAT)) {
                    Gui.drawScaledCustomSizeModalRect(xSpacer, ySpacer, 40.0F, u, 8, v, headSize, headSize, 64.0F, 64.0F);
                }
            }

            xSpacer += headSize + 2;

            if (playerInfo.getGameType() != WorldSettings.GameType.SPECTATOR) {
                HPlayer hPlayer = row.hPlayer;
                if (hPlayer != null) {
                    if (hPlayer.isNicked()) {
                        name = this.getHPlayerName(playerInfo, hPlayer);
                    } else if (name.contains(ChatColor.OBFUSCATE.toString())) {
                        /* Obfuscated names are unreadable, so rebuild them - but keep the team color. */
                        ScorePlayerTeam liveTeam = playerInfo.getPlayerTeam();
                        String teamPrefix = liveTeam != null ? liveTeam.getColorPrefix() : "";
                        String color = teamPrefix.isEmpty() ? hPlayer.getPlayerRankColor() : teamPrefix;
                        name = color + hPlayer.getPlayerName();
                    }
                    /* Otherwise keep the name exactly as the server sent it, original team colors included. */

                    drawPlayerStats(row.stats, statColumns, statColumnStartX, ySpacer + textBaselineOffset, textColor);
                }

                if (chatRow != null) {
                    name = formatChatRevealedName(hPlayer, chatRow);
                }

                this.mc.fontRendererObj.drawStringWithShadow(name, xSpacer, ySpacer + textBaselineOffset, -1);
            }

            /* Chat-revealed players are not on the scoreboard, so there is nothing to draw for them. */
            if (scoreObjectiveIn != null && chatRow == null && playerInfo.getGameType() != WorldSettings.GameType.SPECTATOR) {
                this.drawScoreboardValues(scoreObjectiveIn, ySpacer, gameProfile.getName(), startingX - objectiveLabelWidth, startingX - 5, playerInfo);
            }

            ySpacer += this.entryHeight + 1;
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (playerListSize > maxVisiblePlayers) {
            int indicatorX = contentRight - 10;

            if (startIndex > 0) {
                this.mc.fontRendererObj.drawStringWithShadow(ChatColor.WHITE + "▲", indicatorX, startingY + this.entryHeight + 2, textColor);
            }

            if (endIndex < playerListSize) {
                this.mc.fontRendererObj.drawStringWithShadow(
                        ChatColor.WHITE + "▼",
                        indicatorX,
                        startingY + this.entryHeight + 1 + (visiblePlayerCount * (this.entryHeight + 1)) - 10,
                        textColor
                );
            }
        }

        int footerY = startingY + playerSectionHeight + footerSpacing;
        drawCenteredLines(footerBlock, footerY, contentCenterX, fontHeight, textColor);
    }

    /** Looks a row's player up once per frame; spectators get no stats, as before. */
    private Row resolveRow(NetworkPlayerInfo playerInfo, StatWorld statWorld, Map<UUID, ChatRevealedPlayer> chatRows, String gamemode) {
        GameProfile gameProfile = playerInfo.getGameProfile();
        ChatRevealedPlayer chatRow = chatRows.get(gameProfile.getId());
        if (statWorld == null || playerInfo.getGameType() == WorldSettings.GameType.SPECTATOR) {
            return new Row(playerInfo, null, chatRow, Collections.<String, Stat>emptyMap());
        }

        IChatComponent displayName = playerInfo.getDisplayName();
        HPlayer hPlayer = statWorld.getPlayerByIdentity(
                gameProfile.getId(),
                displayName != null ? displayName.getFormattedText() : null,
                gameProfile.getName()
        );

        // Only the current gamemode's stats: someone without any shows just their name
        Map<String, Stat> stats = hPlayer == null || gamemode == null
                ? Collections.<String, Stat>emptyMap()
                : hPlayer.getGameStatsByKey(gamemode);
        return new Row(playerInfo, hPlayer, chatRow, stats);
    }

    private TextBlock createTextBlock(IChatComponent component) {
        if (component == null) {
            return TextBlock.EMPTY;
        }

        String[] lines = StringUtils.splitPreserveAllTokens(component.getFormattedText(), '\n');
        if (lines == null || lines.length == 0) {
            return TextBlock.EMPTY;
        }

        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, this.mc.fontRendererObj.getStringWidth(line));
        }

        return new TextBlock(lines, maxWidth);
    }

    private void drawCenteredLines(TextBlock block, int startY, int centerX, int fontHeight, int color) {
        if (!block.hasLines()) {
            return;
        }

        for (String line : block.getLines()) {
            this.drawCenteredString(this.mc.fontRendererObj, line, centerX, startY, color);
            startY += fontHeight;
        }
    }

    /** Each column is as wide as its header or its widest value, whichever is wider. */
    private List<StatColumn> buildStatColumns(List<StatColumnLayout.Column> columns, List<Row> rows) {
        if (columns == null || columns.isEmpty()) {
            return Collections.emptyList();
        }

        List<StatColumn> statColumns = new ArrayList<>(columns.size());
        for (StatColumnLayout.Column column : columns) {
            String key = column.getKey();
            String label = ChatColor.BOLD + key;
            int widest = textWidth(label);
            for (Row row : rows) {
                Stat stat = row.stats.get(key);
                if (stat != null) {
                    widest = Math.max(widest, textWidth(StatFormatting.valueOf(stat)));
                }
            }
            statColumns.add(new StatColumn(key, label, widest + 10));
        }

        return statColumns;
    }

    private void drawStatHeaders(List<StatColumn> columns, int startX, int y, int color) {
        int x = startX;
        for (StatColumn column : columns) {
            this.mc.fontRendererObj.drawStringWithShadow(column.label, x, y, color);
            x += column.width;
        }
    }

    /** Draws each stat under the column of the same name; a column the player has nothing for stays empty. */
    private void drawPlayerStats(Map<String, Stat> stats, List<StatColumn> columns, int startX, int baselineY, int color) {
        int x = startX;
        for (StatColumn column : columns) {
            Stat stat = stats.get(column.key);
            if (stat != null) {
                this.mc.fontRendererObj.drawStringWithShadow(StatFormatting.valueOf(stat), x, baselineY, color);
            }
            x += column.width;
        }
    }

    private int textWidth(String text) {
        Integer cached = this.textWidths.get(text);
        if (cached != null) {
            return cached;
        }

        if (this.textWidths.size() >= MAX_CACHED_WIDTHS) {
            this.textWidths.clear();
        }

        int width = this.mc.fontRendererObj.getStringWidth(text);
        this.textWidths.put(text, width);
        return width;
    }

    /**
     * Appends a row for every player that is only known from chat. A pre-game lobby hides who is
     * in it, so a name written in chat is the only thing that puts that player on the list - which
     * is the point: their stats are visible while leaving the lobby is still an option.
     *
     * @return the appended rows keyed by UUID, so the draw loop can tell them apart from real
     *         tab entries
     */
    private Map<UUID, ChatRevealedPlayer> appendChatRevealed(List<NetworkPlayerInfo> playerList, StatWorld statWorld) {
        if (statWorld == null) {
            this.syntheticInfos.clear();
            return Collections.emptyMap();
        }

        List<ChatRevealedPlayer> revealed = statWorld.getChatRevealedPlayers();
        if (revealed.isEmpty()) {
            this.syntheticInfos.clear();
            return Collections.emptyMap();
        }

        Set<UUID> presentIds = new HashSet<>();
        Set<String> presentNames = new HashSet<>();
        for (NetworkPlayerInfo info : playerList) {
            GameProfile profile = info.getGameProfile();
            if (profile == null) {
                continue;
            }

            if (profile.getId() != null) {
                presentIds.add(profile.getId());
            }

            if (profile.getName() != null) {
                presentNames.add(profile.getName().toLowerCase(Locale.ROOT));
            }
        }

        Map<UUID, ChatRevealedPlayer> rows = new HashMap<>();
        for (ChatRevealedPlayer player : revealed) {
            UUID uuid = player.getUuid();
            String playerName = player.getName();
            if (uuid == null || playerName == null) {
                continue;
            }

            /*
             * The server lists them itself now, so it has stopped hiding them - which is what
             * happens the moment the game starts. Retire the reveal and let their own tab entry,
             * drawn above with the server's formatting, take over.
             */
            if (presentIds.contains(uuid) || presentNames.contains(playerName.toLowerCase(Locale.ROOT))) {
                statWorld.hideFromChat(playerName);
                continue;
            }

            playerList.add(syntheticInfo(uuid, playerName));
            rows.put(uuid, player);
        }

        this.syntheticInfos.keySet().retainAll(rows.keySet());
        return rows;
    }

    private NetworkPlayerInfo syntheticInfo(UUID uuid, String name) {
        NetworkPlayerInfo info = this.syntheticInfos.get(uuid);
        if (info == null) {
            info = new NetworkPlayerInfo(new GameProfile(uuid, name));
            this.syntheticInfos.put(uuid, info);
        }

        return info;
    }

    /**
     * Chat-revealed players have no tab entry and therefore no team prefix to preserve, so their
     * name is drawn from the API rank instead.
     */
    private String formatChatRevealedName(HPlayer hPlayer, ChatRevealedPlayer row) {
        if (!row.hasRealProfile()) {
            /* Nobody owns that name, so there are no stats to wait for either. */
            return ChatColor.WHITE + "[" + ChatColor.RED + "NICKED" + ChatColor.WHITE + "] "
                    + ChatColor.WHITE + row.getName();
        }

        if (hPlayer == null) {
            /* Stats are still on their way in. */
            return ChatColor.GRAY + row.getName();
        }

        String rank = hPlayer.getPlayerRank();
        String playerName = hPlayer.getPlayerName() != null ? hPlayer.getPlayerName() : row.getName();
        return (rank == null ? "" : rank) + playerName;
    }

    private List<NetworkPlayerInfo> collectEligiblePlayers(NetHandlerPlayClient netHandler, StatWorld statWorld) {
        List<NetworkPlayerInfo> sortedPlayers = field_175252_a.sortedCopy(netHandler.getPlayerInfoMap());
        List<NetworkPlayerInfo> filtered = new ArrayList<>(sortedPlayers.size());
        for (NetworkPlayerInfo info : sortedPlayers) {
            if (isEligiblePlayer(info, statWorld)) {
                filtered.add(info);
            }
        }
        return filtered;
    }

    private boolean isEligiblePlayer(NetworkPlayerInfo playerInfo, StatWorld statWorld) {
        GameProfile profile = playerInfo.getGameProfile();
        if (profile == null) {
            return false;
        }

        UUID playerUuid = profile.getId();
        if (playerUuid == null) {
            return false;
        }

        int uuidVersion = playerUuid.version();
        if (uuidVersion != 4 && uuidVersion != 1 && uuidVersion != 2) {
            return false;
        }

        if (uuidVersion == 2) {
            if (statWorld == null || statWorld.getPlayerByUUID(playerUuid) == null) {
                return false;
            }
        }

        String strippedName = ChatColor.stripColor(this.getPlayerName(playerInfo));
        if (strippedName != null && strippedName.trim().startsWith("[NPC]")) {
            return false;
        }

        String profileName = profile.getName();
        return profileName != null && VALID_USERNAME.matcher(profileName).matches();
    }

    private void drawScoreboardValues(ScoreObjective objectiveIn, int y, String playerName, int startX, int endX, NetworkPlayerInfo playerInfo) {
        int i = objectiveIn.getScoreboard().getValueFromObjective(playerName, objectiveIn).getScorePoints();

        if (objectiveIn.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS) {
            this.mc.getTextureManager().bindTexture(icons);

            if (this.lastTimeOpened == playerInfo.func_178855_p()) {
                if (i < playerInfo.func_178835_l()) {
                    playerInfo.func_178846_a(Minecraft.getSystemTime());
                    playerInfo.func_178844_b((long)(this.guiIngame.getUpdateCounter() + 20));
                } else if (i > playerInfo.func_178835_l()) {
                    playerInfo.func_178846_a(Minecraft.getSystemTime());
                    playerInfo.func_178844_b((long)(this.guiIngame.getUpdateCounter() + 10));
                }
            }

            if (Minecraft.getSystemTime() - playerInfo.func_178847_n() > 1000L || this.lastTimeOpened != playerInfo.func_178855_p()) {
                playerInfo.func_178836_b(i);
                playerInfo.func_178857_c(i);
                playerInfo.func_178846_a(Minecraft.getSystemTime());
            }

            playerInfo.func_178843_c(this.lastTimeOpened);
            playerInfo.func_178836_b(i);
            int j = MathHelper.ceiling_float_int((float)Math.max(i, playerInfo.func_178860_m()) / 2.0F);
            int k = Math.max(MathHelper.ceiling_float_int((float)(i / 2)), Math.max(MathHelper.ceiling_float_int((float)(playerInfo.func_178860_m() / 2)), 10));
            boolean flag = playerInfo.func_178858_o() > (long)this.guiIngame.getUpdateCounter() && (playerInfo.func_178858_o() - (long)this.guiIngame.getUpdateCounter()) / 3L % 2L == 1L;

            if (j > 0) {
                float f = Math.min((float)(endX - startX - 4) / (float)k, 9.0F);

                if (f > 3.0F) {
                    for (int l = j; l < k; ++l) {
                        this.drawTexturedModalRect((float)startX + (float)l * f, (float)y, flag ? 25 : 16, 0, 9, 9);
                    }

                    for (int j1 = 0; j1 < j; ++j1) {
                        this.drawTexturedModalRect((float)startX + (float)j1 * f, (float)y, flag ? 25 : 16, 0, 9, 9);

                        if (flag) {
                            if (j1 * 2 + 1 < playerInfo.func_178860_m()) {
                                this.drawTexturedModalRect((float)startX + (float)j1 * f, (float)y, 70, 0, 9, 9);
                            }

                            if (j1 * 2 + 1 == playerInfo.func_178860_m()) {
                                this.drawTexturedModalRect((float)startX + (float)j1 * f, (float)y, 79, 0, 9, 9);
                            }
                        }

                        if (j1 * 2 + 1 < i) {
                            this.drawTexturedModalRect((float)startX + (float)j1 * f, (float)y, j1 >= 10 ? 160 : 52, 0, 9, 9);
                        }

                        if (j1 * 2 + 1 == i) {
                            this.drawTexturedModalRect((float)startX + (float)j1 * f, (float)y, j1 >= 10 ? 169 : 61, 0, 9, 9);
                        }
                    }
                } else {
                    float f1 = MathHelper.clamp_float((float)i / 20.0F, 0.0F, 1.0F);
                    String s = "" + (float)i / 2.0F;

                    if (endX - this.mc.fontRendererObj.getStringWidth(s + "hp") >= startX) {
                        s = s + "hp";
                    }

                    this.mc.fontRendererObj.drawStringWithShadow(
                            s,
                            (float)((endX + startX) / 2 - this.mc.fontRendererObj.getStringWidth(s) / 2),
                            (float)y,
                            (int)((1.0F - f1) * 255.0F) << 16 | (int)(f1 * 255.0F) << 8
                    );
                }
            }
        } else {
            /* This is where Hypixel usually has Client draw Scoreboard Stats */

            String s1 = EnumChatFormatting.YELLOW + "" + i;
            this.mc.fontRendererObj.drawStringWithShadow(s1, (float)(endX - this.mc.fontRendererObj.getStringWidth(s1)), (float)y + (this.entryHeight / 2 - 4), 16777215);
//            drawRect(endX - this.mc.fontRendererObj.getStringWidth(objectiveIn.getDisplayName()), y, endX, y + this.entryHeight, 553648127);
        }
    }

    private static final class TextBlock {
        private static final TextBlock EMPTY = new TextBlock(new String[0], 0);
        private final String[] lines;
        private final int maxWidth;

        private TextBlock(String[] lines, int maxWidth) {
            this.lines = lines;
            this.maxWidth = maxWidth;
        }

        private boolean hasLines() {
            return this.lines.length > 0;
        }

        private int height(int lineHeight) {
            return this.lines.length * lineHeight;
        }

        private int getMaxWidth() {
            return this.maxWidth;
        }

        private String[] getLines() {
            return this.lines;
        }
    }

    private static final class StatColumn {
        private final String key;
        private final String label;
        private final int width;

        private StatColumn(String key, String label, int width) {
            this.key = key;
            this.label = label;
            this.width = width;
        }
    }

    /** One tab entry with everything the draw loop needs to know about it. */
    private static final class Row {
        private final NetworkPlayerInfo info;
        private final HPlayer hPlayer;
        private final ChatRevealedPlayer chatRow;
        private final Map<String, Stat> stats;

        private Row(NetworkPlayerInfo info, HPlayer hPlayer, ChatRevealedPlayer chatRow, Map<String, Stat> stats) {
            this.info = info;
            this.hPlayer = hPlayer;
            this.chatRow = chatRow;
            this.stats = stats;
        }
    }

    static class PlayerComparator implements Comparator<NetworkPlayerInfo> {
        private PlayerComparator() {
        }

        public int compare(NetworkPlayerInfo p_compare_1_, NetworkPlayerInfo p_compare_2_) {
            ScorePlayerTeam scoreplayerteam = p_compare_1_.getPlayerTeam();
            ScorePlayerTeam scoreplayerteam1 = p_compare_2_.getPlayerTeam();
            return ComparisonChain.start().compareTrueFirst(p_compare_1_.getGameType() != WorldSettings.GameType.SPECTATOR, p_compare_2_.getGameType() != WorldSettings.GameType.SPECTATOR).compare(scoreplayerteam != null ? scoreplayerteam.getRegisteredName() : "", scoreplayerteam1 != null ? scoreplayerteam1.getRegisteredName() : "").compare(p_compare_1_.getGameProfile().getName(), p_compare_2_.getGameProfile().getName()).result();
        }
    }

    /* Name of a nicked player: the tag stays white, but the nick itself keeps the team color so teams stay readable. */
    public String getHPlayerName(NetworkPlayerInfo playerInfo, HPlayer hPlayer) {
        ScorePlayerTeam team = playerInfo.getPlayerTeam();
        String teamPrefix = team != null ? team.getColorPrefix() : "";
        String teamSuffix = team != null ? team.getColorSuffix() : "";
        String nameColor = teamPrefix.isEmpty() ? ChatColor.WHITE.toString() : teamPrefix;
        return ChatColor.WHITE + "[" + ChatColor.RED + "NICKED" + ChatColor.WHITE + "] " + nameColor + playerInfo.getGameProfile().getName() + teamSuffix;
    }

    /**
     * Entry point used by vanilla GuiIngame. This object replaces the vanilla
     * GuiPlayerTabOverlay, so this override is what draws the stats tab list. Falls back to the
     * vanilla list whenever the mod is disabled.
     */
    @Override
    public void renderPlayerlist(int width, Scoreboard scoreboardIn, ScoreObjective scoreObjectiveIn) {
        TabStats tabStats = TabStats.getTabStats();
        GameOverlayListener listener = tabStats == null ? null : tabStats.getGameOverlayListener();

        if (listener == null || !listener.renderTab(scoreboardIn, scoreObjectiveIn)) {
            super.renderPlayerlist(width, scoreboardIn, scoreObjectiveIn);
        }
    }
}
