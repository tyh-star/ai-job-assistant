package aijob.collector;

import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The single shape every ATS adapter produces. Adding a board means producing this
 * record; nothing downstream of the adapter knows which provider it came from.
 */
public record NormalizedJob(
        String provider,
        String tenant,
        String externalJobId,
        String title,
        String company,
        String descriptionText,
        String descriptionHtml,
        String location,
        String country,
        RemoteType remoteType,
        EmploymentType employmentType,
        SalaryRange salary,
        String canonicalUrl,
        String applyUrl,
        LocalDateTime postedAt,
        String rawPayload
) {
    public record SalaryRange(BigDecimal min, BigDecimal max, String currency) {
    }

    public boolean usable() {
        return externalJobId != null && !externalJobId.isBlank()
                && title != null && !title.isBlank()
                && company != null && !company.isBlank();
    }
}
