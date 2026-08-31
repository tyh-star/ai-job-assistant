package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorAnalysisSummary {

    private int supportedMustHaveCount;
    private int partiallySupportedMustHaveCount;
    private int missingMustHaveCount;
    private int totalMustHaveCount;
    private int supportedPreferredCount;
    private int partiallySupportedPreferredCount;
    private int missingPreferredCount;
    private int totalPreferredCount;
    private int missingEvidenceCount;
}
