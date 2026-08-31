package aijob.jobsearch;

import java.util.List;

public record ProviderSearchPage(
        List<ProviderJobHit> results,
        Boolean hasMore,
        Long totalCount,
        String nextCursor
) {
    public ProviderSearchPage {
        results = results == null ? List.of() : List.copyOf(results);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.strip();
    }

    public ProviderSearchPage(List<ProviderJobHit> results, Boolean hasMore, Long totalCount) {
        this(results, hasMore, totalCount, null);
    }
}
