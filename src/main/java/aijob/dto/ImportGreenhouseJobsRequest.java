package aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ImportGreenhouseJobsRequest {

    @NotBlank(message = "Board token is required")
    @Size(max = 255, message = "Board token cannot exceed 255 characters")
    private String boardToken;
}
