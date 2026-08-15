package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
public class ResumeAnalysisResponse {

    @Schema(minimum = "0", maximum = "100")
    @JsonPropertyDescription("Overall resume quality score from 0 to 100 for a software engineering or technical job seeker")
    public Integer overallScore;

    @JsonPropertyDescription("Concise summary of the resume's positioning, experience, and overall quality")
    public String summary;

    @JsonPropertyDescription("Clear strengths supported by evidence in the resume")
    public List<String> strengths;

    @JsonPropertyDescription("Clear weaknesses or gaps supported by evidence in the resume")
    public List<String> weaknesses;

    @JsonPropertyDescription("Technical skills that are missing or underrepresented for a typical software engineering or technical job search. Do not invent a specific job description")
    public List<String> missingSkills;

    @JsonPropertyDescription("Actionable suggestions to improve the resume")
    public List<String> suggestions;
}
