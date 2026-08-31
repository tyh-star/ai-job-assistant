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
public class ConservativeRewriteCandidate {

    @JsonPropertyDescription("Rewritten resume bullet that preserves every fact from originalText")
    public String newText;

    @JsonPropertyDescription("Short human-readable explanation of why this wording is better")
    public String reason;

    @JsonPropertyDescription("Improvements claimed for this candidate, such as tense, grammar, or clarity")
    public List<String> claimedImprovements = new ArrayList<>();

    @JsonPropertyDescription("True if the generator recommends this candidate first")
    public Boolean recommendedByGenerator;

    @JsonPropertyDescription("Node ids from the same project or job entry whose original facts were used")
    public List<String> usedFactNodeIds = new ArrayList<>();

    @JsonPropertyDescription("Must equal the current plan targetNodeId")
    public String nodeId;

    @JsonPropertyDescription("Supporting node ids actually used. Must be a subset of EvidencePacket.supportingNodeIds")
    public List<String> usedSupportingNodeIds = new ArrayList<>();

    @JsonPropertyDescription("SAME_ENTRY_EVIDENCE_ENRICHMENT, JD_RELEVANCE, CLARITY_IMPROVEMENT, GRAMMAR_FIX, or SUMMARY_REWRITE")
    public String improvementIntent;

    @JsonPropertyDescription("Facts claimed to appear in newText. The server re-checks these against the EvidencePacket")
    public List<String> claimedFacts = new ArrayList<>();
}
