package tabstats.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Whether the client is connected to Hypixel at all. Everywhere else the mod stays dark: its
 * stats, its {@code /locraw} and its commands only make sense there.
 *
 * <p>The address the player connected to answers this right away. The brand the server announces
 * covers the rest - a direct IP, or a DNS name of the player's own pointing at Hypixel - though it
 * only arrives a moment after joining.
 */
public final class HypixelServer {

    private HypixelServer() {
    }

    public static boolean isOnHypixel() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }

        ServerData serverData = mc.getCurrentServerData();
        if (serverData != null && isHypixelAddress(serverData.serverIP)) {
            return true;
        }

        EntityPlayerSP player = mc.thePlayer;
        String brand = player == null ? null : player.getClientBrand();
        return brand != null && brand.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    /** {@code mc.hypixel.net}, {@code hypixel.net:25565} and the like. */
    private static boolean isHypixelAddress(String address) {
        if (address == null) {
            return false;
        }

        String host = address.trim().toLowerCase(Locale.ROOT);
        int port = host.lastIndexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }

        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }
}
