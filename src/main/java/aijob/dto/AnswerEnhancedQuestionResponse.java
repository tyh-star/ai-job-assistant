package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerEnhancedQuestionResponse {

    private String questionId;
    private String answerStatus;
    private String canonicalEntryKey;
    private ResumeEvidenceItemResponse evidence;
    private boolean skipped;
}
