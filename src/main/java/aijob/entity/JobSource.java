package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobSource {

    private Long id;

    private String providerType;

    private String displayName;

    private String tenantKey;

    private String baseUrl;

    private boolean enabled;

    private boolean crawlAllowed;

    private String termsUrl;

    private int collectionIntervalMinutes;

    private LocalDateTime lastSuccessAt;

    private LocalDateTime nextRunAt;

    private int consecutiveFailures;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Collection is only ever attempted when the operator opted in on both flags. */
    public boolean collectable() {
        return enabled && crawlAllowed;
    }
}
