package tabstats.playerapi.exception;

public class ApiThrottleException extends ApiRequestException {
    private final boolean global;
    private final long retryAfterMs;

    /**
     * @param retryAfterMs how long no request should go out, as far as the API told us
     */
    public ApiThrottleException(boolean global, long retryAfterMs) {
        super(global ? "Hypixel API is currently throttling all requests" : "Hypixel API key is being throttled");
        this.global = global;
        this.retryAfterMs = retryAfterMs;
    }

    public boolean isGlobal() {
        return global;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
