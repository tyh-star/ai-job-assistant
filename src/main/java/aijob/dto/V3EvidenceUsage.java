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
public class V3EvidenceUsage {

    public static final String USED_IN_ENTRY = "USED_IN_ENTRY";
    public static final String USED_IN_SKILLS = "USED_IN_SKILLS";
    public static final String USED_IN_ENTRY_AND_SKILLS = "USED_IN_ENTRY_AND_SKILLS";
    public static final String SAVED_NOT_USED = "SAVED_NOT_USED";
    public static final String USER_REJECTED = "USER_REJECTED";

    public static final String NO_ADMITTED_REWRITE = "NO_ADMITTED_REWRITE";
    public static final String FACT_ALREADY_PRESENT = "FACT_ALREADY_PRESENT";
    public static final String NOT_A_SKILL = "NOT_A_SKILL";
    public static final String NO_SKILLS_SECTION = "NO_SKILLS_SECTION";
    public static final String NOT_RELEVANT_TO_JD = "NOT_RELEVANT_TO_JD";
    public static final String VALIDATOR_REJECTED = "VALIDATOR_REJECTED";
    public static final String CONFIRMED_FACT_NOT_USED = "CONFIRMED_FACT_NOT_USED";

    private Long evidenceItemId;
    private String canonicalEntryKey = "";
    private String topicKey = "";
    private String usageStatus = "";
    private String reasonCode = "";
    private List<String> usedInEntryKeys = new ArrayList<>();
    private List<String> usedInSkillKeys = new ArrayList<>();
}
