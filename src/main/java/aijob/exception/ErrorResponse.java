package aijob.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ErrorResponse {

    private int status;
    private String error;
    private String message;
    private String path;
    private LocalDateTime timestamp;

    /** Stable machine code such as {@code WEAK_PASSWORD}. Omitted when null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public ErrorResponse(int status, String error, String message, String path, LocalDateTime timestamp) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.timestamp = timestamp;
    }

    public ErrorResponse withCode(String code) {
        this.code = code;
        return this;
    }
}
