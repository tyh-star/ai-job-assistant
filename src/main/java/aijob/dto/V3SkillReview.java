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
public class V3SkillReview {

    public static final String REVIEW_ID = "skills:technical";

    private String skillReviewId = REVIEW_ID;
    private String originalSkills = "";
    private String proposedSkills = "";
    private List<String> addedSkills = new ArrayList<>();
    private List<Long> evidenceItemIds = new ArrayList<>();
    private String whyBetter = "";
    private boolean admitted = true;
    private List<V3ReviewNode> originalNodes = new ArrayList<>();
    private List<V3ReviewNode> proposedNodes = new ArrayList<>();
}
