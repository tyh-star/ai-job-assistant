package aijob.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BulkDeleteJobsRequest {

    @NotNull(message = "Job IDs are required")
    private List<Long> jobIds;
}
