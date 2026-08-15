package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
public class JobMatchResponse {

    @Schema(minimum = "0", maximum = "100")
    @JsonPropertyDescription("Match score from 0 to 100 for how well this specific resume fits this specific job description")
    public Integer matchScore;

    @JsonPropertyDescription("Concise summary of how the resume matches this job description")
    public String summary;

    @JsonPropertyDescription("Skills required by the job description that are supported by evidence in the resume")
    public List<String> matchedSkills;

    @JsonPropertyDescription("Skills clearly required or emphasized by the job description that are not supported by evidence in the resume")
    public List<String> missingSkills;

    @JsonPropertyDescription("Candidate strengths that are relevant to this specific job")
    public List<String> strengths;

    @JsonPropertyDescription("Gaps between the resume and this specific job description")
    public List<String> gaps;

    @JsonPropertyDescription("Recommendations for how to tailor the resume to this job description without inventing experience")
    public List<String> recommendations;
}
