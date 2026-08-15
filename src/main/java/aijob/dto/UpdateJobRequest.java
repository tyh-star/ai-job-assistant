package aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateJobRequest {

    @NotBlank(message = "Company is required")
    @Size(max = 255, message = "Company cannot exceed 255 characters")
    private String company;

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title cannot exceed 255 characters")
    private String title;

    @Size(max = 255, message = "Location cannot exceed 255 characters")
    private String location;

    @Size(max = 1000, message = "Job URL cannot exceed 1000 characters")
    private String jobUrl;

    @NotBlank(message = "Description is required")
    private String description;
}
