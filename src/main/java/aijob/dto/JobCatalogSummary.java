package aijob.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Catalog search row. Compact list data only: no full job description, no apply URL,
 * no raw provider payload, and no unsanitized HTML. Those belong on
 * {@link JobCatalogDetail}, loaded when the caller asks for one posting.
 */
public record JobCatalogSummary(
        // See JobCatalogDetail.id: an opaque per-provider reference, never a bare
        // number a caller could reuse as a different provider's row id.
        String id,
        String title,
        String companyName,
        String locationText,
        String countryCode,
        String remoteType,
        String employmentType,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String canonicalUrl,
        String source,
        LocalDateTime postedAt,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        String status,
        boolean saved,
        Long personalJobId,
        boolean applied,
        LocalDateTime appliedAt,
        // Present only when this search was scoped by a structured `city` (see
        // JobCatalogSearchRequest#city). Confirmed live against real freehire.me
        // (2026-09-06): a multi-location posting's own `cities` array can list several
        // cities across DIFFERENT countries -- a Toronto search can genuinely return a
        // posting whose primary `locationText` above reads "Remote (US)" because that
        // same posting's `cities` happens to also include "Toronto". `cityMatch` makes
        // that fact visible instead of leaving the US-looking `locationText` to read as
        // "the city filter did not work": the caller can show "matched: Toronto" next
        // to "posting's own location: Remote (US)" instead of silently picking one.
        CityMatch cityMatch
) {
    /**
     * {@code city} is the value the request filtered by, confirmed present in this
     * hit's own {@code cities} array (see FreeHireJobCatalogService#toSummaries) --
     * never fabricated. {@code countryCode} is only ever the country the FRONTEND'S
     * OWN city suggestion carried (see JobCatalogSearchRequest#cityCountry) -- it is
     * NOT re-derived from this posting. {@code countryConfirmed} says whether this
     * SPECIFIC posting's own {@code countries} field actually contains that code too;
     * when false, {@code countryCode} must not be presented as this posting's
     * confirmed country -- see JobCatalogSearchRequest#cityCountry's javadoc for why a
     * bare {@code cities} match carries no country guarantee at all.
     */
    public record CityMatch(String city, String countryCode, boolean countryConfirmed) {
    }
}
