package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResumeEvidenceItem {

    private Long id;
    private Long userId;
    private Long resumeId;
    private String topicKey;
    private String topicLabel;
    private String category;
    private String answerStatus;
    private String entryKey;
    private String entryLabel;
    private String relatedNodeIdsJson;
    private String contextText;
    private String actionText;
    private String resultText;
    private String metricText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
