package tabstats.util;

import java.util.Locale;

/**
 * Tells from the chat when the waiting phase of a pre-game lobby is over.
 *
 * <p>Hypixel frames a game's start and end summary with a {@code ▬▬▬…} bar above and below, and
 * that summary is the one chat signal for a game starting - a pre-game lobby does not always sit
 * on its own world. The same bars frame other banners too, though: a lobby that rolls a Bed Wars
 * challenge greets you with
 *
 * <pre>
 * ▬▬▬▬▬▬▬▬▬▬▬▬
 *         TEAM CHALLENGE ACTIVE!
 *              Quick Maths
 * Challenge Rules: ...
 * ▬▬▬▬▬▬▬▬▬▬▬▬
 * </pre>
 *
 * while the lobby is still filling up. So a bar alone decides nothing: the lines are collected
 * up to the closing bar, and only then is it known what the banner was.
 *
 * <p>Fed every chat line, in order, on the client thread.
 */
public final class SummaryBanner {
    /** A banner arrives in one burst; a bar that is followed by nothing for this long closed nothing. */
    private static final long MAX_OPEN_MS = 3_000L;
    /** Longer than any summary Hypixel sends, so a lone bar cannot keep a banner open for good. */
    private static final int MAX_LINES = 40;

    private boolean open;
    private boolean challenge;
    private int lines;
    private long openedAt;

    /**
     * @param formattedMessage a chat line as the server sent it
     * @return true when this line closes a banner that marks the waiting phase as over
     */
    public boolean offer(String formattedMessage) {
        return offer(formattedMessage, System.currentTimeMillis());
    }

    boolean offer(String formattedMessage, long now) {
        if (this.open && (now - this.openedAt > MAX_OPEN_MS || this.lines >= MAX_LINES)) {
            // Its closing bar never came - whatever it was, it is not told apart any more
            this.open = false;
        }

        if (ChatNameParser.isBannerBar(formattedMessage)) {
            if (this.open) {
                this.open = false;
                return !this.challenge;
            }

            this.open = true;
            this.challenge = false;
            this.lines = 0;
            this.openedAt = now;
            return false;
        }

        if (this.open) {
            this.lines++;
            if (isChallengeLine(formattedMessage)) {
                this.challenge = true;
            }
        }

        return false;
    }

    /** The headline of a challenge banner - "TEAM CHALLENGE ACTIVE!" in a team mode. */
    private static boolean isChallengeLine(String formattedMessage) {
        String stripped = ChatColor.stripColor(formattedMessage);
        return stripped != null && stripped.toLowerCase(Locale.ROOT).contains("challenge active");
    }
}
