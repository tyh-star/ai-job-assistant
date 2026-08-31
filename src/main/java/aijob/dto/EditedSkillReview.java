package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditedSkillReview {

    private String skillReviewId;
    private String proposedSkills;

    @Valid
    private List<EditedTailorChange> nodes = new ArrayList<>();
}
