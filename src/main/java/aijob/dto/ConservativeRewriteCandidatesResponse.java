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
public class ConservativeRewriteCandidatesResponse {

    @JsonPropertyDescription("The nodeId being rewritten")
    public String nodeId;

    @JsonPropertyDescription("Exactly three fact-preserving rewrites with different wording")
    public List<ConservativeRewriteCandidate> candidates = new ArrayList<>();
}
