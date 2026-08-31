package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConservativeCandidateScore {

    @JsonPropertyDescription("Zero-based index into the candidates list")
    public Integer index;

    @JsonPropertyDescription("0-100 grammar quality")
    public Integer grammarScore;

    @JsonPropertyDescription("0-100 clarity")
    public Integer clarityScore;

    @JsonPropertyDescription("0-100 professional action-verb quality")
    public Integer actionVerbScore;

    @JsonPropertyDescription("0-100 specificity")
    public Integer specificityScore;

    @JsonPropertyDescription("0-100 conciseness")
    public Integer concisenessScore;

    @JsonPropertyDescription("0-100 job relevance without stuffing")
    public Integer jobRelevanceScore;

    @JsonPropertyDescription("0-100 factual preservation. Below 60 means do not select.")
    public Integer factualPreservationScore;

    @JsonPropertyDescription("Weighted local total on a 100-point T2C scale")
    public Integer totalScore;

    @JsonPropertyDescription("0-100 substantive improvement before the 20-point weight")
    public Integer substantiveImprovementScore;

    @JsonPropertyDescription("Local total minus the original-text baseline")
    public Integer improvementMargin;

    @JsonPropertyDescription("True when the rewrite adds a real same-entry, clarity, grammar, or JD mapping improvement")
    public Boolean substantiveImprovement;
}
