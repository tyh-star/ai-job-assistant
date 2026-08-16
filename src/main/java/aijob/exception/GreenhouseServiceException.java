package aijob.exception;

public class GreenhouseServiceException extends RuntimeException {

    public GreenhouseServiceException(String message) {
        super(message);
    }

    public GreenhouseServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
