package aijob.exception;

public class JobSearchUnavailableException extends RuntimeException {

    public static final String REASON_CONFIG_MISSING = "CONFIG_MISSING";
    public static final String REASON_UPSTREAM_STATUS = "UPSTREAM_STATUS";

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
