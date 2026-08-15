package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TailorProposal {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMING = "CONFIRMING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private Long id;

    private Long userId;

    private Long resumeId;

    private Long jobId;

    private String mode;

    private String status;

    private LocalDateTime expiresAt;

    private String changesJson;

    private String rejectedJson;

    private String generatedFileName;

    private String generatedFilePath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
