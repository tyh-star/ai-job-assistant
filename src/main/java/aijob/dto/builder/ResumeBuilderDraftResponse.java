package aijob.dto.builder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Full draft, including its typed content -- returned by create/get/update/duplicate. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeBuilderDraftResponse {
    private Long id;
    private String title;
    private String templateType;
    private String targetRole;
    private String locale;
    private Integer schemaVersion;
    private Long version;
    private ResumeBuilderDraftContent content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
