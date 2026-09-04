package aijob.exception;

public class JobSearchRateLimitedException extends RuntimeException {

    /**
     * The upstream's {@code Retry-After} value in whole seconds, when it sent one.
     * Null when the upstream gave no usable hint — callers must not invent a number
     * in that case, only report that a limit was hit.
     */
    private final Integer retryAfterSeconds;

    public JobSearchRateLimitedException(String message) {
        this(message, null);
    }

    public JobSearchRateLimitedException(String message, Integer retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
