package aijob.exception;

public class JobSearchUnavailableException extends RuntimeException {

    public static final String REASON_CONFIG_MISSING = "CONFIG_MISSING";
    public static final String REASON_UPSTREAM_STATUS = "UPSTREAM_STATUS";
    /**
     * The upstream answered 400: it is telling US our request was malformed, not
     * reporting its own trouble. That is a bug in how this backend built the request
     * (a contract drift, a bad param translation) -- never something a retry or a wait
     * fixes, and never the user's fault. Kept as its own reason code so it can be
     * logged and alerted on distinctly from a genuine upstream outage (401/403/5xx),
     * even though both still surface to our own caller as the same honest "search is
     * temporarily unavailable" -- the distinction matters for US finding the bug, not
     * for what the end user is told.
     */
    public static final String REASON_UPSTREAM_REJECTED_OUR_REQUEST = "UPSTREAM_REJECTED_OUR_REQUEST";

    private final String reasonCode;
    private final Integer upstreamStatus;

    public JobSearchUnavailableException(String message) {
        this(message, null, null, null);
    }

    public JobSearchUnavailableException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public JobSearchUnavailableException(String message, String reasonCode, Integer upstreamStatus) {
        this(message, reasonCode, upstreamStatus, null);
    }

    public JobSearchUnavailableException(
            String message,
            String reasonCode,
            Integer upstreamStatus,
            Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.upstreamStatus = upstreamStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }
}
