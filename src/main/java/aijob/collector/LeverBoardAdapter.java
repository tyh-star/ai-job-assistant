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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a public Lever postings board.
 *
 * <p>{@code GET /v0/postings/{site}?mode=json} is Lever's documented, unauthenticated
 * postings API. Two Lever quirks are handled explicitly: {@code createdAt} is epoch
 * milliseconds, and the posting body is split across {@code descriptionPlain},
 * {@code lists} and {@code additionalPlain} — the requirements live in {@code lists},
 * so the intro alone is not the posting.
 */
@Component
public class LeverBoardAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(LeverBoardAdapter.class);
    private static final int PAGE_LIMIT = 100;

    private final RestClient client;
    private final JobCollectorProperties properties;
    private final CollectorRetry retry;

    @Autowired
    public LeverBoardAdapter(@Qualifier("leverCollectorClient") RestClient client,
                             JobCollectorProperties properties) {
        this(client, properties, new CollectorRetry(properties));
    }

    LeverBoardAdapter(RestClient client,
                      JobCollectorProperties properties,
                      CollectorRetry retry) {
        this.client = client;
        this.properties = properties;
        this.retry = retry;
    }

    @Override
    public AtsProviderType providerType() {
        return AtsProviderType.LEVER;
    }

    @Override
    public List<NormalizedJob> fetch(JobSource source) {
        String tenant = AtsHostAllowlist.requireTenantKey(AtsProviderType.LEVER, source.getTenantKey());

        List<NormalizedJob> normalized = new ArrayList<>();
        int skip = 0;
        while (normalized.size() < properties.getMaxJobsPerSource()) {
            final int offset = skip;
            JsonNode postings = retry.call("lever:" + tenant, () -> client.get()
                    .uri(builder -> builder.path("/{tenant}")
                            .queryParam("mode", "json")
                            .queryParam("limit", PAGE_LIMIT)
                            .queryParam("skip", offset)
                            .build(tenant))
                    .retrieve()
                    .body(JsonNode.class));

            if (postings == null || !postings.isArray()) {
                throw new JobCollectionException("SOURCE_PAYLOAD_UNEXPECTED");
            }
            if (postings.isEmpty()) {
                break;
            }
            for (JsonNode posting : postings) {
                if (normalized.size() >= properties.getMaxJobsPerSource()) {
                    log.warn("Lever source {} hit the per-run cap of {} jobs",
                            source.getId(), properties.getMaxJobsPerSource());
                    break;
                }
                NormalizedJob candidate = toNormalized(posting, tenant, source.getDisplayName());
                if (candidate != null && candidate.usable()) {
                    normalized.add(candidate);
                }
            }
            if (postings.size() < PAGE_LIMIT) {
                break;
            }
            skip += PAGE_LIMIT;
        }
        return normalized;
    }

    private NormalizedJob toNormalized(JsonNode posting, String tenant, String company) {
        if (posting == null || posting.isNull()) {
            return null;
        }
        String externalJobId = text(posting, "id");
        if (externalJobId == null) {
            return null;
        }

        JsonNode categories = posting.get("categories");
        String locationText = locationOf(categories);

        String descriptionText = CollectorText.truncate(
                plainDescription(posting), properties.getMaxDescriptionChars());
        if (descriptionText == null || descriptionText.isBlank()) {
            return null;
        }
        String descriptionHtml = CollectorText.truncate(
                HtmlSanitizer.sanitize(htmlDescription(posting)), properties.getMaxDescriptionChars());

        String hostedUrl = text(posting, "hostedUrl");
        String applyUrl = text(posting, "applyUrl");
        String countryCode = normalizeCountry(text(posting, "country"));
        if (countryCode == null) {
            countryCode = LocationParser.countryCode(locationText);
        }

        RemoteType remoteType = RemoteType.fromNullable(text(posting, "workplaceType"));
        if (remoteType == RemoteType.UNSPECIFIED) {
            remoteType = LocationParser.remoteTypeFromText(locationText);
        }

        return new NormalizedJob(
                AtsProviderType.LEVER.name(),
                tenant,
                externalJobId,
                LocationParser.truncate(text(posting, "text"), 500),
                LocationParser.truncate(company, 255),
                descriptionText,
                descriptionHtml,
                LocationParser.truncate(locationText, 500),
                countryCode,
                remoteType,
                EmploymentType.fromNullable(text(categories, "commitment")),
                salaryOf(posting.get("salaryRange")),
                hostedUrl,
                applyUrl != null ? applyUrl : hostedUrl,
                CollectorText.fromEpochMillis(longValue(posting, "createdAt")),
                CollectorText.truncate(posting.toString(), properties.getMaxRawPayloadChars()));
    }

    private static String locationOf(JsonNode categories) {
        if (categories == null) {
            return null;
        }
        Set<String> locations = new LinkedHashSet<>();
        String primary = text(categories, "location");
        if (primary != null && !primary.isBlank()) {
            locations.add(primary.strip());
        }
        JsonNode all = categories.get("allLocations");
        if (all != null && all.isArray()) {
            for (JsonNode node : all) {
                String value = node == null || node.isNull() ? null : node.asText();
                if (value != null && !value.isBlank()) {
                    locations.add(value.strip());
                }
            }
        }
        return locations.isEmpty() ? null : String.join(" | ", locations);
    }

    private static String plainDescription(JsonNode posting) {
        StringBuilder text = new StringBuilder();
        append(text, text(posting, "descriptionPlain"));
        JsonNode lists = posting.get("lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode section : lists) {
                append(text, text(section, "text"));
                append(text, HtmlTextExtractor.toPlainText(text(section, "content")));
            }
        }
        append(text, text(posting, "additionalPlain"));
        return text.toString().strip();
    }

    private static String htmlDescription(JsonNode posting) {
        StringBuilder html = new StringBuilder();
        append(html, text(posting, "description"));
        JsonNode lists = posting.get("lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode section : lists) {
                String heading = text(section, "text");
                if (heading != null && !heading.isBlank()) {
                    html.append("<h3>").append(heading).append("</h3>\n");
                }
                String content = text(section, "content");
                if (content != null && !content.isBlank()) {
                    html.append("<ul>").append(content).append("</ul>\n");
                }
            }
        }
        append(html, text(posting, "additional"));
        return html.toString().strip();
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            if (!target.isEmpty()) {
                target.append("\n\n");
            }
            target.append(value.strip());
        }
    }

    private static NormalizedJob.SalaryRange salaryOf(JsonNode salaryRange) {
        if (salaryRange == null || salaryRange.isNull()) {
            return null;
        }
        BigDecimal min = decimal(salaryRange, "min");
        BigDecimal max = decimal(salaryRange, "max");
        String currency = text(salaryRange, "currency");
        if (min == null && max == null) {
            return null;
        }
        return new NormalizedJob.SalaryRange(
                min,
                max,
                currency == null ? null : currency.strip().toUpperCase(Locale.ROOT));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    private static String normalizeCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (code.matches("[A-Z]{2}")) {
            return code;
        }
        return LocationParser.countryCode(value);
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        return value.longValue();
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
}
