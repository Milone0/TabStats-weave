package tabstats.playerapi.exception;

public class ApiThrottleException extends ApiRequestException {
    private final long retryAfterMs;

    /**
     * @param global whether Hypixel is throttling everyone, rather than just this key
     * @param retryAfterMs how long no request should go out, as far as the API told us
     */
    public ApiThrottleException(boolean global, long retryAfterMs) {
        super(global ? "Hypixel API is currently throttling all requests" : "Hypixel API key is being throttled");
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
