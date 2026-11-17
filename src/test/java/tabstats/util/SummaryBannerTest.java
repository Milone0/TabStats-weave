package tabstats.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryBannerTest {
    private static final String BAR = "§a§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    /** Feeds the lines at one moment and says whether the last one ended the waiting phase. */
    private static boolean feed(SummaryBanner banner, long now, String... lines) {
        boolean last = false;
        for (String line : lines) {
            last = banner.offer(line, now);
        }
        return last;
    }

    @Test
    void theGameStartSummaryEndsTheWaitingPhase() {
        SummaryBanner banner = new SummaryBanner();

        assertFalse(banner.offer(BAR, 0L), "the opening bar alone decides nothing");
        assertFalse(banner.offer("§f§l                     Bed Wars", 0L));
        assertFalse(banner.offer("", 0L));
        assertFalse(banner.offer("     §e§lProtect your bed and destroy the enemy beds.", 0L));
        assertTrue(banner.offer(BAR, 0L));
    }

    @Test
    void aChallengeBannerInThePreGameLobbyDoesNot() {
        SummaryBanner banner = new SummaryBanner();

        assertFalse(feed(banner, 0L,
                BAR,
                "§f§l                      TEAM CHALLENGE ACTIVE!",
                "§e§l                                Quick Maths",
                "§7Challenge Rules:",
                "§7  1. Answer math questions that will occasionally show up in chat",
                BAR));

        // The real start summary later on still counts
        assertTrue(feed(banner, 20_000L, BAR, "Bed Wars", BAR));
    }

    @Test
    void chatBetweenBannersIsNotPartOfOne() {
        SummaryBanner banner = new SummaryBanner();

        assertFalse(feed(banner, 0L, "§7Hans§7: TEAM CHALLENGE ACTIVE! lol", "Klaus: hi"));
        assertTrue(feed(banner, 0L, BAR, "Bed Wars", BAR));
    }

    @Test
    void aBarWhoseBannerNeverClosedStartsAFreshOne() {
        SummaryBanner banner = new SummaryBanner();

        banner.offer(BAR, 0L);
        banner.offer("TEAM CHALLENGE ACTIVE!", 0L);

        // Seconds later: this bar opens the game summary rather than closing the challenge
        assertFalse(banner.offer(BAR, 30_000L));
        assertFalse(banner.offer("Bed Wars", 30_000L));
        assertTrue(banner.offer(BAR, 30_000L));
    }
}
