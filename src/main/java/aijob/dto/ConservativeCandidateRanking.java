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
public class ConservativeCandidateRanking {

    @JsonPropertyDescription("Zero-based index of the selected passing candidate")
    public Integer selectedIndex;

    @JsonPropertyDescription("Short reason for the ranking decision")
    public String reason;

    @JsonPropertyDescription("Score breakdown for each candidate")
    public List<ConservativeCandidateScore> scores = new ArrayList<>();
}
