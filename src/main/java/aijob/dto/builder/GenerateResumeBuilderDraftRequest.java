package aijob.dto.builder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code idempotencyKey} must be generated ONCE by the client per generate action
 * (e.g. {@code crypto.randomUUID()}) and resent verbatim on any retry of that same
 * action -- never a fresh key per HTTP attempt. See
 * schema/resume_builder.sql and ResumeBuilderServiceImpl#generateFromDraft.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateResumeBuilderDraftRequest {

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;
}
