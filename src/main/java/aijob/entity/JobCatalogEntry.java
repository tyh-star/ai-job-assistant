package aijob.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A normalized posting in the shared catalog. Never owned by a user; a user who
 * saves one gets their own {@link Job} snapshot instead.
 */
@Data
public class JobCatalogEntry {

    private Long id;

    private String title;

    private String companyName;

    private String descriptionText;

    private String descriptionHtml;

    private String locationText;

    private String city;

    private String region;

    private String countryCode;

    private String remoteType;

    private String employmentType;

    private BigDecimal salaryMin;

    private BigDecimal salaryMax;

    private String currency;

    private String canonicalUrl;

    private String applyUrl;

    /** Normalized form of {@link #canonicalUrl}, used only for de-duplication lookups. */
    private String canonicalUrlNorm;

    /** Normalized form of {@link #applyUrl}, used only for de-duplication lookups. */
    private String applyUrlNorm;

    private LocalDateTime postedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private String status;

    private String fingerprint;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
