package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One rubric category's contribution to {@link ResumeQualityScoreResponse}. Every
 * category's {@code maxScore} across the whole rubric sums to exactly 100 -- see
 * {@link aijob.util.ResumeQualityScorer} for the full rule set and the reasoning
 * behind each category's weight.
 */
@Data
@AllArgsConstructor
public class ResumeQualityCategoryScore {

    /** One of {@link aijob.util.ResumeQualityScorer}'s category codes, e.g. "STRUCTURE". */
    private String code;

    private int score;

    private int maxScore;
}
