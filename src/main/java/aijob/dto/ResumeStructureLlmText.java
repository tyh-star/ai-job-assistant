package aijob.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("A value copied from one or more layout blocks, with source citations.")
public class ResumeStructureLlmText {

    @JsonPropertyDescription("Exact substring copied from the cited block text. Empty if none.")
    public String value;

    @JsonPropertyDescription("Block ids such as b-p1-3 that contain this value. Empty if none.")
    public List<String> sourceBlockIds = new ArrayList<>();

    @JsonPropertyDescription("1-based page of the cited blocks. 0 if none.")
    public Integer sourcePage;

    @JsonPropertyDescription("Exact excerpt copied from the cited blocks. Empty if none.")
    public String sourceText;

    @JsonPropertyDescription("HIGH, MEDIUM, or LOW")
    public String confidence;
}
