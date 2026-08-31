package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorAnalysisDimensions {

    private Integer hardSkills;
    private Integer responsibilities;
    private Integer qualifications;
    private Integer evidenceStrength;
    private Integer atsSearchability;
}
