package aijob.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Catalog detail. {@code descriptionHtml} has already been run through
 * {@code HtmlSanitizer}; the stored provider payload is never exposed.
 */
public record JobCatalogDetail(
        // An opaque catalog reference: a plain local catalog id ("42") in local_catalog
        // mode, or a namespaced upstream reference ("freehire:<public_slug>") in
        // freehire mode. Never parsed as a number by any caller outside the provider
        // that issued it.
        String id,
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
        Long personalJobId,
        boolean applied,
        LocalDateTime appliedAt
) {
}
