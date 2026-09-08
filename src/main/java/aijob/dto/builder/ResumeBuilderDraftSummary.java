package aijob.dto.builder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One row of the draft list -- deliberately excludes {@code draft_json}, which can be
 * large; the list page only ever needs title/template/target role/timestamps. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeBuilderDraftSummary {
    private Long id;
    private String title;
    private String templateType;
    private String targetRole;
    private String locale;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
