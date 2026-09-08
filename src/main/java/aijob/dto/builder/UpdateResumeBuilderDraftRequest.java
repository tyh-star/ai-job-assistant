package aijob.dto.builder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResumeBuilderDraftRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String templateType;

    @Size(max = 200)
    private String targetRole;

    @NotBlank
    private String locale;

    @NotNull
    @Valid
    private ResumeBuilderDraftContent content;

    /** The {@code version} this update was based on -- see ResumeBuilderDraft's
     * javadoc. Required: a client that never read the draft has nothing to base an
     * edit on. */
    @NotNull
    private Long version;
}
