package aijob.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmTailorProposalRequest {

    private List<String> selectedNodeIds;

    @Valid
    private List<EditedTailorChange> editedChanges;

    private List<String> selectedEntryKeys;

    @Valid
    private List<EditedTailorEntry> editedEntries;

    private List<String> selectedSkillReviewIds;

    @Valid
    private List<EditedSkillReview> editedSkillReviews;
}
