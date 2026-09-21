package tabstats.listener;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.playerapi.WorldLoader;
import tabstats.util.ChatNameParser;
import tabstats.util.Gamemodes;
import tabstats.util.HypixelLocation;

/**
 * Feeds names written in chat into the stat world, so players that a pre-game lobby keeps hidden
 * show up in the tab list as soon as they say anything.
 */
public class ChatListener {

    @SubscribeEvent
    public void onChatReceived(ChatEvent.Received event) {
        IChatComponent message = event.getMessage();
        if (message == null) {
            return;
        }

        /* The answer to the mod's own /locraw: consumed here, never shown to the player. */
        if (HypixelLocation.handleChatMessage(message.getUnformattedText())) {
            event.setCancelled(true);
            return;
        }

        ModConfig config = ModConfig.getInstance();
        if (!config.isModEnabled()) {
            return;
        }

        TabStats tabStats = TabStats.getTabStats();
        WorldLoader statWorld = tabStats == null ? null : tabStats.getStatWorld();
        if (statWorld == null) {
            return;
        }

        ChatNameParser.Reveal reveal = ChatNameParser.parse(message.getFormattedText());
        if (reveal == null) {
            return;
        }

        /* Dropping names is never gated - the lobby they belong to is gone either way. */
        if (reveal.getKind() == ChatNameParser.Kind.CLEAR) {
            statWorld.clearChatRevealed();
            return;
        }

        String name = reveal.getName();

        if (reveal.getKind() == ChatNameParser.Kind.HIDE) {
            statWorld.hideFromChat(name);
            return;
        }

        if (isSelf(name)) {
            return;
        }

        /* The server lists them itself, so there is nothing left to uncover. */
        if (listedInTab(name)) {
            return;
        }

        /* A lobby chats far more than a game does, and none of it is worth an API call. */
        if (!Gamemodes.inSupportedGame()) {
            return;
        }

        statWorld.revealFromChat(name);
    }

    private boolean isSelf(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private boolean listedInTab(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) {
            return false;
        }

        for (NetworkPlayerInfo info : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            GameProfile profile = info == null ? null : info.getGameProfile();
            if (profile != null && name.equalsIgnoreCase(profile.getName())) {
                return true;
            }
        }

        return false;
    }
}
