package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** The CALLER'S OWN feedback only -- never another user's. See FeedbackController. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackResponse {

    private int rating;

    private String comment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
