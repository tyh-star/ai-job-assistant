package aijob.exception;

/** No feedback exists yet for the caller (GET/DELETE before ever PUT-ing one). */
public class FeedbackNotFoundException extends RuntimeException {

    public FeedbackNotFoundException(String message) {
        super(message);
    }
}
