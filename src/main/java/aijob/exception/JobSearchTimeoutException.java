package aijob.exception;

public class JobSearchTimeoutException extends RuntimeException {

    public JobSearchTimeoutException(String message) {
        super(message);
    }

    public JobSearchTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
