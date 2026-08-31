package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorAnalysisRequirement {

    private String id;
    private String category;
    private String priority;
    private String requirement;
    private List<String> exactTerms = new ArrayList<>();
    private String status;
    private String evidenceKind;
    private String evidenceAvailability;
    private List<Long> evidenceItemIds = new ArrayList<>();
    private List<String> evidenceNodeIds = new ArrayList<>();
    private List<String> evidenceQuotes = new ArrayList<>();
    private String coverageReason;
}
