package aijob.exception;

public class JobSearchRateLimitedException extends RuntimeException {

    public JobSearchRateLimitedException(String message) {
        super(message);
    }
}
