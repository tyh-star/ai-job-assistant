package aijob.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FeedbackRequest {

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    private Integer rating;

    /**
     * Always treated as plain text end-to-end -- stored as-is, never interpreted as
     * HTML or markup anywhere on the backend, and the frontend renders it as a plain
     * text node (never {@code dangerouslySetInnerHTML}), which is what actually
     * prevents a comment from ever executing as script in another visitor's browser.
     */
    @Size(max = 1000, message = "Comment cannot exceed 1000 characters")
    private String comment;
}
