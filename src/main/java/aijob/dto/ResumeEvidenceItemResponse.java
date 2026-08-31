package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeEvidenceItemResponse {

    private Long id;
    private Long resumeId;
    private String topic;
    private String topicKey;
    private String category;
    private String answerStatus;
    private String entryKey;
    private String entryLabel;
    private List<String> relatedNodeIds = new ArrayList<>();
    private String context;
    private String action;
    private String result;
    private String metric;
    private String provenance;
    private String validationStatus;
    private String validationReasonCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
