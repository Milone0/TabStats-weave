package tabstats.shout;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import tabstats.util.Handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends chat messages one after another with a pause in between, so a shout of eight teams does
 * not trip Hypixel's spam protection.
 *
 * <p>A new batch replaces whatever is still waiting, and a world change drops the rest - the
 * lines belong to the game that was just left. Everything runs on the client thread.
 */
final class ChatQueue {
    /** Hypixel documents no limit; a second apart has not been a problem for chat mods. */
    private static final long DELAY_MS = 1_000L;

    private static int generation;

    private ChatQueue() {
    }

    static void send(List<String> messages) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || messages == null || messages.isEmpty()) {
            return;
        }

        int batch = ++generation;
        sendFrom(new ArrayList<>(messages), 0, batch, mc.theWorld);
    }

    private static void sendFrom(List<String> messages, int index, int batch, World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (batch != generation || mc.thePlayer == null || mc.theWorld == null || mc.theWorld != world) {
            return;
        }

        mc.thePlayer.sendChatMessage(messages.get(index));

        int next = index + 1;
        if (next < messages.size()) {
            Handler.schedule(() -> Handler.onClientThread(() -> sendFrom(messages, next, batch, world)), DELAY_MS);
        }
    }
}
