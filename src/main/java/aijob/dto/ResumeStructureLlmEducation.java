package aijob.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("One education entry copied from cited layout blocks.")
public class ResumeStructureLlmEducation {

    @JsonPropertyDescription("School name copied from cited blocks. Empty if none.")
    public String school;

    @JsonPropertyDescription("Degree copied from cited blocks. Empty if none.")
    public String degree;

    @JsonPropertyDescription("Dates copied from cited blocks. Empty if none.")
    public String dates;

    @JsonPropertyDescription("Relevant Courses or other extra lines copied from cited blocks. Empty if none.")
    public String details;

    @JsonPropertyDescription("Block ids such as b-p1-3 that contain this education. Empty if none.")
    public List<String> sourceBlockIds = new ArrayList<>();

    @JsonPropertyDescription("1-based page of the cited blocks. 0 if none.")
    public Integer sourcePage;

    @JsonPropertyDescription("Exact excerpt copied from the cited blocks. Empty if none.")
    public String sourceText;

    @JsonPropertyDescription("HIGH, MEDIUM, or LOW")
    public String confidence;
}
