package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
public class JobRequirementsLlmResponse {

    @JsonPropertyDescription("At most 20 distinct requirements copied from this job description")
    public List<JobRequirementDraft> requirements = new ArrayList<>();
}
