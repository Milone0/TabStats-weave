package tabstats.playerapi.exception;

/**
 * Hypixel refused one player because they were looked up a moment ago. Unlike
 * {@link ApiThrottleException} this says nothing about the key: every other player may still be
 * asked for right away.
 */
public class PlayerCooldownException extends ApiRequestException {
    private final long retryAfterMs;

    public PlayerCooldownException(long retryAfterMs) {
        super("Hypixel API was asked for this player too recently");
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
