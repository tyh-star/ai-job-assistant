package aijob.dto.builder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateResumeBuilderDraftRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    /** One of {@link aijob.enums.ResumeBuilderTemplateType}; validated by
     * ResumeBuilderDraftValidator (a plain 400, not an enum-binding 500, on a bad
     * value). */
    @NotBlank
    private String templateType;

    @NotBlank
    private String locale;
}
