package aijob.exception;

public class JobSearchSessionExpiredException extends RuntimeException {

    public JobSearchSessionExpiredException(String message) {
        super(message);
    }
}
