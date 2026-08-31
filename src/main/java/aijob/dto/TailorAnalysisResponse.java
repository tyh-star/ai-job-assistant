package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorAnalysisResponse {

    private Long resumeId;
    private Long jobId;
    private int overallScore;
    private String scoreBand;
    private TailorAnalysisDimensions dimensions;
    private TailorAnalysisExplanation scoreExplanation;
    private List<TailorAnalysisRequirement> requirements = new ArrayList<>();
    private List<EvidenceQuestion> evidenceQuestions = new ArrayList<>();
    private TailorAnalysisSummary summary;
    private boolean requirementsFixed;
    private String profileUpdatedAt;
}
