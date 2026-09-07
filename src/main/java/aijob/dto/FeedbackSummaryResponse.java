package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The PUBLIC site-wide summary -- deliberately only these two numbers. Never a user's
 * email, name, or comment text; see FeedbackMapper#getSummary, which reads only
 * AVG(rating) and COUNT(*) and never selects any other column.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackSummaryResponse {

    /** 0.0 when {@link #reviewerCount} is 0 -- never null, and never a bare average of nothing. */
    private double averageRating;

    private long reviewerCount;
}
