package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One saved draft of the 通用简历生成器 (resume builder) form. A user may hold any
 * number of these -- {@code userId} is indexed, never unique. {@code draftJson} is the
 * serialized {@link aijob.dto.builder.ResumeBuilderDraftContent}; every read/write goes
 * through that typed DTO (see ResumeBuilderDraftValidator), never treated as opaque
 * text by the service layer. {@code version} is a plain optimistic-concurrency counter
 * -- see schema/resume_builder.sql's javadoc.
 */
@Data
public class ResumeBuilderDraft {

    private Long id;

    private Long userId;

    private String title;

    private String templateType;

    private String targetRole;

    private String locale;

    private Integer schemaVersion;

    private String draftJson;

    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
