package aijob.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Catalog search row. Carries no raw provider payload and no unsanitized HTML.
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
        String applyUrl,
        String canonicalUrl,
        String source,
        LocalDateTime postedAt,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        String status,
        String descriptionSnippet,
        boolean saved,
        Long personalJobId
) {
}
