package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportSearchResultsResponse {

    private List<ImportedSearchJob> imported = new ArrayList<>();
    private List<ImportedSearchJob> existing = new ArrayList<>();
    private List<FailedSearchImport> failed = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportedSearchJob {
        private String externalJobId;
        private Long jobId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedSearchImport {
        private String externalJobId;
        private String reason;
    }
}
