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
import tabstats.util.Debug;
import tabstats.util.Gamemodes;

/**
 * Feeds names written in chat into the stat world, so players that a pre-game lobby keeps hidden
 * show up in the tab list as soon as they say anything.
 */
public class ChatListener {

    @SubscribeEvent
    public void onChatReceived(ChatEvent.Received event) {
        ModConfig config = ModConfig.getInstance();
        if (!config.isModEnabled() || !config.isChatRevealEnabled()) {
            return;
        }

        IChatComponent message = event.getMessage();
        if (message == null) {
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
            Debug.chatReveal("game boundary in chat, dropping every reveal");
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
            Debug.chatReveal("skipping " + name + ": already in the tab list");
            return;
        }

        if (Gamemodes.resolveCurrent() == null) {
            Debug.chatReveal("skipping " + name + ": no supported game on the scoreboard");
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
