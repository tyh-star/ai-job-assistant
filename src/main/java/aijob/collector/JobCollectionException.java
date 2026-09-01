package aijob.collector;

/**
 * A source could not be collected. The message is a short classified reason that is
 * safe to persist in {@code job_ingest_runs.error_summary}: never a URL with
 * credentials, an API key, a response body, or personal data.
 */
public class JobCollectionException extends RuntimeException {

    private final String reason;

    public JobCollectionException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public JobCollectionException(String reason, Throwable cause) {
        // Deliberately does not include the cause's message: adapter causes can carry
        // response fragments, and this string is stored and shown to operators.
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
