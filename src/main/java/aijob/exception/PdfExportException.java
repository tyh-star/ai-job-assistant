package aijob.exception;

public class PdfExportException extends RuntimeException {

    public PdfExportException(String message) {
        super(message);
    }

    public PdfExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
