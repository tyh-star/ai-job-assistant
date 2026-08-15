package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorChange {

    public static final String EVIDENCE_VERIFIED = "VERIFIED";
    public static final String EVIDENCE_INFERRED = "INFERRED";

    @JsonPropertyDescription("Stable document node id from parseDocx, such as p-22 or t-0-r-2-c-1-p-0")
    public String nodeId;

    @JsonPropertyDescription("Exact current text of that node. Must match the provided node text.")
    public String originalText;

    @JsonPropertyDescription("Rewritten text for that node")
    public String newText;

    @JsonPropertyDescription("Short reason for the rewrite")
    public String reason;

    @JsonPropertyDescription("VERIFIED if the resume already contains evidence; INFERRED if this is a plausible adjacent skill that needs user confirmation")
    public String evidenceType;

    @JsonPropertyDescription("The adjacent skill being inferred. Empty when evidenceType is VERIFIED")
    public String inferredSkill;

    @JsonPropertyDescription("True when the user must confirm this change before it can be written to the DOCX")
    public Boolean requiresConfirmation;

    @JsonPropertyDescription("True when this change was written into the generated DOCX")
    public Boolean applied;

    @JsonPropertyDescription("Node ids whose current resume text supports a VERIFIED rewrite. Required for VERIFIED; empty for INFERRED.")
    public List<String> evidenceNodeIds;

    @JsonPropertyDescription("Short quote copied from the referenced evidence nodes. Required for VERIFIED; must appear in those nodes' original text.")
    public String evidenceQuote;

    public boolean inferred() {
        return EVIDENCE_INFERRED.equalsIgnoreCase(evidenceType);
    }
}
