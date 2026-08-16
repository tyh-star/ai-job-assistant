package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportJobsResponse {

    private int importedCount;
    private int skippedDuplicateCount;
    private int skippedNoTitleCount;
    private int skippedNoUrlCount;
    private int skippedNoDescriptionCount;
    private int skippedOverLimitCount;
    private List<JobResponse> jobs = new ArrayList<>();
}
