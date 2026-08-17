package aijob.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("One project copied from cited layout blocks.")
public class ResumeStructureLlmProject {

    @JsonPropertyDescription("Project name copied from cited blocks. Empty if none.")
    public String name;

    @JsonPropertyDescription("Organization or type copied as written, such as Personal Project or a school name. Empty if none.")
    public String organizationOrType;

    @JsonPropertyDescription("Date range copied as written, including original dashes. Empty if none.")
    public String dates;

    @JsonPropertyDescription("Bullet sentences copied from cited blocks. Do not include Project Link lines. Empty if none.")
    public List<String> bullets = new ArrayList<>();

    @JsonPropertyDescription("Project URLs copied from cited blocks, such as a Project Link. Empty if none.")
    public List<String> links = new ArrayList<>();

    @JsonPropertyDescription("Block ids such as b-p1-3 that contain this project. Empty if none.")
    public List<String> sourceBlockIds = new ArrayList<>();

    @JsonPropertyDescription("1-based page of the cited blocks. 0 if none.")
    public Integer sourcePage;

    @JsonPropertyDescription("Exact excerpt copied from the cited blocks. Empty if none.")
    public String sourceText;

    @JsonPropertyDescription("HIGH, MEDIUM, or LOW")
    public String confidence;
}
