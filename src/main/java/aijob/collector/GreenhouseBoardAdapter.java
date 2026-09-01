package aijob.collector;

import aijob.entity.JobSource;
import aijob.enums.AtsProviderType;
import aijob.enums.EmploymentType;
import aijob.enums.RemoteType;
import aijob.util.HtmlSanitizer;
import aijob.util.HtmlTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a public Greenhouse job board.
 *
 * <p>{@code GET /v1/boards/{token}/jobs?content=true} is Greenhouse's documented,
 * unauthenticated board endpoint — the same data the company's own careers page
 * renders. Only the application-submission endpoint requires a key, and this adapter
 * never touches it.
 */
@Component
public class GreenhouseBoardAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseBoardAdapter.class);

    private final RestClient client;
    private final JobCollectorProperties properties;
    private final CollectorRetry retry;

    @Autowired
    public GreenhouseBoardAdapter(@Qualifier("greenhouseCollectorClient") RestClient client,
                                 JobCollectorProperties properties) {
        this(client, properties, new CollectorRetry(properties));
    }

    GreenhouseBoardAdapter(RestClient client,
                           JobCollectorProperties properties,
                           CollectorRetry retry) {
        this.client = client;
        this.properties = properties;
        this.retry = retry;
    }

    @Override
    public AtsProviderType providerType() {
        return AtsProviderType.GREENHOUSE;
    }

    @Override
    public List<NormalizedJob> fetch(JobSource source) {
        String tenant = AtsHostAllowlist.requireTenantKey(AtsProviderType.GREENHOUSE, source.getTenantKey());

        JsonNode board = retry.call("greenhouse-board:" + tenant, () -> client.get()
                .uri("/{tenant}", tenant)
                .retrieve()
                .body(JsonNode.class));
        JsonNode jobsRoot = retry.call("greenhouse-jobs:" + tenant, () -> client.get()
                .uri(builder -> builder.path("/{tenant}/jobs")
                        .queryParam("content", "true")
                        .build(tenant))
                .retrieve()
                .body(JsonNode.class));

        String companyFallback = text(board, "name");
        if (companyFallback == null || companyFallback.isBlank()) {
            companyFallback = source.getDisplayName();
        }

        JsonNode jobs = jobsRoot == null ? null : jobsRoot.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new JobCollectionException("SOURCE_PAYLOAD_UNEXPECTED");
        }

        List<NormalizedJob> normalized = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (normalized.size() >= properties.getMaxJobsPerSource()) {
                log.warn("Greenhouse source {} hit the per-run cap of {} jobs",
                        source.getId(), properties.getMaxJobsPerSource());
                break;
            }
            NormalizedJob candidate = toNormalized(job, tenant, companyFallback);
            if (candidate != null && candidate.usable()) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }

    private NormalizedJob toNormalized(JsonNode job, String tenant, String companyFallback) {
        if (job == null || job.isNull()) {
            return null;
        }
        String externalJobId = text(job, "id");
        if (externalJobId == null) {
            return null;
        }

        String rawHtml = text(job, "content");
        String descriptionText = CollectorText.truncate(
                HtmlTextExtractor.toPlainText(rawHtml), properties.getMaxDescriptionChars());
        if (descriptionText == null || descriptionText.isBlank()) {
            // Match and Tailor need a description; a title-only posting is not useful.
            return null;
        }
        String descriptionHtml = CollectorText.truncate(
                HtmlSanitizer.sanitize(rawHtml), properties.getMaxDescriptionChars());

        String locationText = text(job.get("location"), "name");
        String countryCode = LocationParser.countryCode(locationText);
        if (countryCode == null) {
            countryCode = LocationParser.countryCode(firstOfficeLocation(job));
        }

        String url = text(job, "absolute_url");
        LocalDateTime postedAt = CollectorText.parseIsoDateTime(
                firstNonBlank(text(job, "first_published"), text(job, "updated_at")));

        return new NormalizedJob(
                AtsProviderType.GREENHOUSE.name(),
                tenant,
                externalJobId,
                LocationParser.truncate(text(job, "title"), 500),
                LocationParser.truncate(companyFallback, 255),
                descriptionText,
                descriptionHtml,
                LocationParser.truncate(locationText, 500),
                countryCode,
                LocationParser.remoteTypeFromText(locationText),
                // The board API does not publish an employment type.
                EmploymentType.UNSPECIFIED,
                null,
                url,
                url,
                postedAt,
                CollectorText.truncate(job.toString(), properties.getMaxRawPayloadChars()));
    }

    private static String firstOfficeLocation(JsonNode job) {
        JsonNode offices = job.get("offices");
        if (offices == null || !offices.isArray()) {
            return null;
        }
        for (JsonNode office : offices) {
            String location = text(office, "location");
            if (location != null && !location.isBlank()) {
                return location;
            }
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.textValue() : value.asText();
    }

    /** Greenhouse remote type is only ever inferred from text; kept for readability. */
    static RemoteType remoteTypeOf(String locationText) {
        return LocationParser.remoteTypeFromText(locationText);
    }
}
