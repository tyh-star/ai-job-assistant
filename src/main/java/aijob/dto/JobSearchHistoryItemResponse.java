package aijob.dto;

import java.time.LocalDateTime;

public record JobSearchHistoryItemResponse(
        Long id,
        String keywords,
        String city,
        String cityCountry,
        String country,
        String workMode,
        String employmentType,
        Integer postedWithinDays,
        String sortBy,
        int searchCount,
        LocalDateTime lastSearchedAt
) {
}
