package aijob.exception;

public class ResumeTextExtractionException extends RuntimeException {

    public ResumeTextExtractionException(String message) {
        super(message);
    }

    public ResumeTextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
