package aijob.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Catalog detail. {@code descriptionHtml} has already been run through
 * {@code HtmlSanitizer}; the stored provider payload is never exposed.
 */
public record JobCatalogDetail(
        Long id,
        String title,
        String companyName,
        String descriptionText,
        String descriptionHtml,
        String locationText,
        String city,
        String region,
        String countryCode,
        String remoteType,
        String employmentType,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String canonicalUrl,
        String applyUrl,
        String source,
        LocalDateTime postedAt,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        String status,
        boolean saved,
        Long personalJobId
) {
}
