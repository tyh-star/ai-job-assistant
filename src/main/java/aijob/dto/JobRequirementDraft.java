package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobRequirementDraft {

    @JsonPropertyDescription("The requirement as stated in the job description")
    public String requirement;

    @JsonPropertyDescription("HARD_SKILL, RESPONSIBILITY, QUALIFICATION, DOMAIN, SOFT_SKILL, or OTHER_KEYWORD")
    public String category;

    @JsonPropertyDescription("MUST_HAVE, PREFERRED, or OPTIONAL")
    public String priority;

    @JsonPropertyDescription("Exact technology words or key phrases copied from the job description")
    public List<String> exactTerms = new ArrayList<>();
}
