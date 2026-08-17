package aijob.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("One job copied from cited layout blocks.")
public class ResumeStructureLlmExperience {

    @JsonPropertyDescription("Employer name copied from cited blocks. Empty if none.")
    public String company;

    @JsonPropertyDescription("Job title copied from cited blocks. Empty if none.")
    public String title;

    @JsonPropertyDescription("Start date copied from cited blocks. Empty if none.")
    public String startDate;

    @JsonPropertyDescription("End date copied from cited blocks. Empty if none.")
    public String endDate;

    @JsonPropertyDescription("Location copied from cited blocks. Empty if none.")
    public String location;

    @JsonPropertyDescription("Bullet sentences copied from cited blocks. Empty if none.")
    public List<String> bullets = new ArrayList<>();

    @JsonPropertyDescription("Block ids such as b-p1-3 that contain this job. Empty if none.")
    public List<String> sourceBlockIds = new ArrayList<>();

    @JsonPropertyDescription("1-based page of the cited blocks. 0 if none.")
    public Integer sourcePage;

    @JsonPropertyDescription("Exact excerpt copied from the cited blocks. Empty if none.")
    public String sourceText;

    @JsonPropertyDescription("HIGH, MEDIUM, or LOW")
    public String confidence;
}
