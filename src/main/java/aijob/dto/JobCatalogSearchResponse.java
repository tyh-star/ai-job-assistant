package aijob.dto;

import java.util.List;

public record JobCatalogSearchResponse(
        int page,
        int size,
        long totalCount,
        int totalPages,
        boolean hasMore,
        List<JobCatalogSummary> results
) {
    public JobCatalogSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
