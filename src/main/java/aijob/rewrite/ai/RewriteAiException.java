package aijob.rewrite.ai;

import org.springframework.http.HttpStatus;

/**
 * A resume-rewrite AI failure the frontend can tell apart by {@link #code()}. Never
 * carries the API key, the prompt or resume text.
 */
public class RewriteAiException extends RuntimeException {

    public static final String NOT_CONFIGURED = "AI_NOT_CONFIGURED";
    public static final String RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String TIMEOUT = "AI_TIMEOUT";
    public static final String UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";
    public static final String INVALID_OUTPUT = "AI_INVALID_OUTPUT";

    private final String code;
    private final HttpStatus status;

    public RewriteAiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static RewriteAiException notConfigured() {
        return new RewriteAiException(NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                "Resume rewrite AI is not configured on this server");
    }

    public static RewriteAiException rateLimited() {
        return new RewriteAiException(RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                "The AI provider rate limit was reached. Please try again later.");
    }

    public static RewriteAiException timeout() {
        return new RewriteAiException(TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
                "The AI provider did not answer in time. Please try again.");
    }

    public static RewriteAiException upstream(int upstreamStatus) {
        return new RewriteAiException(UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY,
                "The AI provider returned an error (HTTP " + upstreamStatus + ").");
    }

    public static RewriteAiException invalidOutput(String detail) {
        return new RewriteAiException(INVALID_OUTPUT, HttpStatus.BAD_GATEWAY,
                "The AI returned output that could not be used: " + detail);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
