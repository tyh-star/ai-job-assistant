package aijob.dto;

import lombok.Data;

@Data
public class AnswerEnhancedQuestionRequest {

    private String questionId;
    private String answerStatus;
    private String answerText;
    private String entryKey;
}
