package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The resume's OWN structure/content-completeness score -- never a match score against
 * any specific job, and never any measure of hiring probability. See
 * {@link aijob.util.ResumeQualityScorer} for the full deterministic rubric this is
 * computed from: same resume text in, same result out, every time, with no call to
 * OpenAI or any other AI service anywhere in this path.
 * <p>
 * When {@link #scorable} is false (the resume's extracted text is empty -- typically an
 * image-only scan with nothing for the rubric to read), {@link #score}, {@link #level},
 * {@link #categories}, {@link #strengths} and {@link #suggestions} are all left at
 * their empty/null defaults and {@link #reasonCode} names the real reason. This is
 * NEVER represented as a scored 0 -- a 0 would falsely claim the rubric evaluated the
 * resume and found nothing of value, when what actually happened is there was nothing
 * to evaluate at all.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeQualityScoreResponse {

    public static final String RUBRIC_VERSION = "v1";

    private boolean scorable;

    /** Set only when {@link #scorable} is false, e.g. "TEXT_NOT_AVAILABLE". */
    private String reasonCode;

    /** Null when {@link #scorable} is false. Otherwise 0-100, and always exactly the sum of {@link #categories}. */
    private Integer score;

    /** Null when {@link #scorable} is false. One of EXCELLENT / GOOD / FAIR / NEEDS_IMPROVEMENT. */
    private String level;

    private String rubricVersion;

    /** Every category's maxScore sums to exactly 100. Empty when {@link #scorable} is false. */
    private List<ResumeQualityCategoryScore> categories;

    /** Strength codes the frontend maps to i18n text -- see ResumeQualityScorer's javadoc for the full set. */
    private List<String> strengths;

    /** Suggestion codes, already ordered highest-priority first -- the frontend shows the top 3. */
    private List<String> suggestions;
}
