package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobIngestRun {

    private Long id;

    private Long sourceId;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String status;

    private int fetchedCount;

    private int insertedCount;

    private int updatedCount;

    private int unchangedCount;

    private int expiredCount;

    private int failedCount;

    /** Short classified reason only. Never secrets, response bodies, or personal data. */
    private String errorSummary;
}
