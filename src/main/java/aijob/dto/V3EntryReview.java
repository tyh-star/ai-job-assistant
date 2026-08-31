package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V3EntryReview {

    private String canonicalEntryKey = "";
    private String entryTitle = "";
    private List<V3ReviewNode> originalNodes = new ArrayList<>();
    private List<V3ReviewNode> proposedNodes = new ArrayList<>();
    private String whyBetter = "";
    private String whyBetterZh = "";
    private String suggestionType = "EVIDENCE_ENHANCED";
    private List<Long> usedEvidenceItemIds = new ArrayList<>();
}
