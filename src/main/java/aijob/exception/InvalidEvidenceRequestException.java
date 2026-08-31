package aijob.exception;

public class InvalidEvidenceRequestException extends RuntimeException {

    private final String reasonCode;

    public InvalidEvidenceRequestException(String message) {
        super(message);
        this.reasonCode = null;
    }

    public InvalidEvidenceRequestException(String reasonCode, String message) {
        super(reasonCode + (message == null || message.isBlank() ? "" : ": " + message));
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
