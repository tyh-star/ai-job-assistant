package aijob.exception;

/** {@code days} was not 7/30/90, or {@code timeZone} is not a real IANA zone. */
public class InvalidApplicationStatsException extends RuntimeException {

    public InvalidApplicationStatsException(String message) {
        super(message);
    }
}
