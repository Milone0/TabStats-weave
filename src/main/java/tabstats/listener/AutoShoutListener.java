package tabstats.listener;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.shout.Shouter;
import tabstats.shout.TabSnapshot;
import tabstats.util.Gamemodes;

/**
 * Runs {@code /bw shout} by itself once per Bed Wars game, when that is switched on in the
 * settings.
 *
 * <p>The start of a game is when the tab list shows teams - a pre-game lobby has none. The shout
 * then waits a moment, so it does not land in the middle of the start banner, and for the stats of
 * everyone in the tab; whoever is still loading after {@link #MAX_WAIT_MS} is left out.
 */
public class AutoShoutListener {
    private static final String GAMEMODE = "BEDWARS";
    /** Lets the start banner and the first chat of the game go by. */
    private static final long MIN_WAIT_MS = 3_000L;
    private static final long MAX_WAIT_MS = 12_000L;
    /** A snapshot walks the whole tab list, which every tick would be wasted on. */
    private static final int CHECK_EVERY_TICKS = 10;

    private World lastWorld;
    private boolean doneThisWorld;
    private long teamsSeenAt;
    private int ticks;

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != this.lastWorld) {
            this.lastWorld = mc.theWorld;
            this.doneThisWorld = false;
            this.teamsSeenAt = 0L;
        }

        if (this.doneThisWorld || mc.thePlayer == null || ++this.ticks % CHECK_EVERY_TICKS != 0) {
            return;
        }

        if (!ModConfig.getInstance().getShoutSettings().isAutoShout() || !TabStats.isActive()
                || !Gamemodes.inSupportedGame() || !GAMEMODE.equals(Gamemodes.resolveCurrent())) {
            this.teamsSeenAt = 0L;
            return;
        }

        TabSnapshot snapshot = TabSnapshot.take(GAMEMODE);
        if (snapshot.getTeams().size() < 2) {
            this.teamsSeenAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (this.teamsSeenAt == 0L) {
            this.teamsSeenAt = now;
        }

        long waited = now - this.teamsSeenAt;
        if (waited < MIN_WAIT_MS || (waited < MAX_WAIT_MS && !snapshot.allLoaded())) {
            return;
        }

        this.doneThisWorld = true;
        Shouter.run(GAMEMODE, new String[0], false, true);
    }
}
