package aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SaveJobSearchResultRequest {

    @Size(max = 64)
    private String externalJobId;

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    @NotBlank(message = "Company is required")
    @Size(max = 255)
    private String company;

    @Size(max = 255)
    private String location;

    private String postedAt;

    private String descriptionSnippet;

    private String source;

    @NotBlank(message = "Original job URL is required")
    @Size(max = 1000)
    private String sourceUrl;

    @NotBlank(message = "Full job description is required")
    private String fullDescription;
}
