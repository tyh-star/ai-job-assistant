package aijob.exception;

/** The typed draft content failed validation (a string too long, a bad URL scheme, an
 * out-of-range number, a malformed section order, an illegal control/XML character,
 * ...). See ResumeBuilderDraftValidator. */
public class InvalidResumeBuilderDraftException extends RuntimeException {

    public InvalidResumeBuilderDraftException(String message) {
        super(message);
    }
}
