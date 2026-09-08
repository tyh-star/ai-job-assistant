package aijob.dto;

/**
 * Criteria from an explicit catalog search (Search click or Enter), matching the fields
 * {@code JobCatalogSearchRequest} actually sends -- never the page number. Empty
 * criteria are a no-op, not an error: recording must never fail the search itself.
 */
public record RecordJobSearchHistoryRequest(
        String keywords,
        String city,
        String cityCountry,
        String country,
        String remoteType,
        String employmentType,
        Integer postedWithinDays,
        String sort
) {
}
