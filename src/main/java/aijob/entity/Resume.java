package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Resume {

    private Long id;

    private Long userId;

    private String fileName;

    private String fileType;

    private String filePath;

    private String extractedText;

    private Long parentResumeId;

    private Long canonicalResumeId;

    private String origin;

    private String reviewStatus;

    private String structuredJson;

    private String layoutJson;

    private String extractionStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
