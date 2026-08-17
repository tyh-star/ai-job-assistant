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
public class FieldProvenance {

    private List<String> sourceBlockIds;
    private Integer sourcePage;
    private String sourceText;
    private String confidence;
    private String provenance;

    public void normalize() {
        if (sourceBlockIds == null) {
            sourceBlockIds = new ArrayList<>();
        }
        if (sourceText == null) {
            sourceText = "";
        }
        if (confidence == null || confidence.isBlank()) {
            confidence = ResumeTextBlock.CONFIDENCE_LOW;
        }
        if (provenance == null) {
            provenance = "";
        }
    }

    public static FieldProvenance llm(
            List<String> sourceBlockIds,
            Integer sourcePage,
            String sourceText,
            String confidence) {
        FieldProvenance provenance = new FieldProvenance();
        provenance.setSourceBlockIds(sourceBlockIds == null ? new ArrayList<>() : new ArrayList<>(sourceBlockIds));
        provenance.setSourcePage(sourcePage);
        provenance.setSourceText(sourceText == null ? "" : sourceText);
        provenance.setConfidence(confidence == null || confidence.isBlank()
                ? ResumeTextBlock.CONFIDENCE_MEDIUM
                : confidence);
        provenance.setProvenance(ResumeTextBlock.PROVENANCE_LLM);
        return provenance;
    }
}
