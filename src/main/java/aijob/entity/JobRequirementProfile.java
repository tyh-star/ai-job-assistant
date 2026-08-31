package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobRequirementProfile {

    private Long id;
    private Long userId;
    private Long jobId;
    private String descriptionSha256;
    private Integer schemaVersion;
    private String extractionModel;
    private String requirementsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
