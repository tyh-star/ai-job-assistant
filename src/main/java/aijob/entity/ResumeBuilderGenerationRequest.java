package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One idempotency claim for POST /resume-builder/drafts/{id}/generate. A row is
 * inserted (with {@code resumeId} null) as the FIRST step of generating, then updated
 * with the new resume's id once it exists. The unique key on (userId, idempotencyKey)
 * is what a retry -- or two racing tabs sending the same client-generated key -- loses
 * a race on, so a generate action can never produce two {@code resumes} rows. See
 * ResumeBuilderServiceImpl#generateFromDraft.
 */
@Data
public class ResumeBuilderGenerationRequest {

    private Long id;

    private Long userId;

    private Long draftId;

    private String idempotencyKey;

    private Long resumeId;

    private LocalDateTime createdAt;
}
