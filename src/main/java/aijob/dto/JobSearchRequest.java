package aijob.dto;

import java.util.Set;

public record JobSearchRequest(
        String keywords,
        String location,
        Integer postedWithinDays,
        int page,
        int pageSize
) {
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 20;
    public static final Set<Integer> ALLOWED_POSTED_WITHIN_DAYS = Set.of(1, 3, 7, 14, 30);

    public JobSearchRequest {
        keywords = keywords == null ? "" : keywords.strip();
        location = location == null || location.isBlank() ? null : location.strip();
        if (postedWithinDays != null && postedWithinDays <= 0) {
            postedWithinDays = null;
        }
        page = page < 1 ? DEFAULT_PAGE : page;
        pageSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
