package aijob.dto;

import java.util.Set;

/**
 * User-supplied catalog search parameters, before normalization.
 */
public record JobCatalogSearchRequest(
        String q,
        String location,
        String country,
        String remoteType,
        String employmentType,
        Integer postedWithinDays,
        Integer page,
        Integer size,
        String sort
) {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final Set<Integer> ALLOWED_POSTED_WITHIN_DAYS = Set.of(1, 3, 7, 14, 30);
    public static final String SORT_RELEVANCE = "RELEVANCE";
    public static final String SORT_POSTED_AT = "POSTED_AT";
}
