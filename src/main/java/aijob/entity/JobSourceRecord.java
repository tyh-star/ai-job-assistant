package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobSourceRecord {

    private Long id;

    private Long sourceId;

    private String externalJobId;

    private Long catalogJobId;

    private String sourceUrl;

    private String applyUrl;

    private String payloadHash;

    private String rawPayload;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private Long lastSuccessfulRunId;

    private boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
