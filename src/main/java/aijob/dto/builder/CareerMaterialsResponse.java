package aijob.dto.builder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Five optional, separately-generated career materials -- NEVER auto-inserted into the
 * formal ATS resume (see NormalizedResume/ResumeBuilderDocxServiceImpl, neither of
 * which reference this class at all). Built purely by
 * {@link aijob.util.CareerMaterialsGenerator} from facts the user actually typed on the
 * "职业材料" tab -- deterministic, zero OpenAI calls, zero AI quota. When a section's
 * underlying facts are blank, its text says what to fill in rather than inventing
 * content.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareerMaterialsResponse {
    private String positioningSummary;
    private String selfIntroduction;
    private String aiReflection;
    private String strengthsAndWeaknesses;
    private String developmentPlan;
}
