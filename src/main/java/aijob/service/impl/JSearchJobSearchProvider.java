package aijob.service.impl;

import aijob.config.JSearchClientConfig;
import aijob.dto.JobSearchRequest;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import aijob.jobsearch.JobSearchCacheKeys;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.JSearchDatePosted;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderSearchPage;
import aijob.service.JobSearchProvider;
import aijob.util.HtmlTextExtractor;
import aijob.util.JobFingerprint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class JSearchJobSearchProvider implements JobSearchProvider {

    public static final String SOURCE = "JSEARCH";
    static final String UNAVAILABLE_MESSAGE = "Job search is temporarily unavailable.";
    static final int PAGE_SIZE = 10;
    static final int MAX_DESCRIPTION_CHARS = 100_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<JSearchJobJson>> JOBS_TYPE = new TypeReference<>() {
    };

    private static final Logger log = LoggerFactory.getLogger(JSearchJobSearchProvider.class);

    private final RestClient jsearchRestClient;
    private final Environment environment;
    private final Clock clock;

    public JSearchJobSearchProvider(
            @Qualifier("jsearchRestClient") RestClient jsearchRestClient,
            Environment environment,
            @Qualifier("jobSearchClock") Clock clock) {
        this.jsearchRestClient = jsearchRestClient;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public String providerName() {
        return SOURCE;
    }

    @Override
    public JobSearchCapability capability() {
        return JobSearchCapability.FULL_DESCRIPTION;
    }

    @Override
    public ProviderSearchPage search(JobSearchRequest request) {
        return search(request, null);
    }

    @Override
    public ProviderSearchPage search(JobSearchRequest request, String cursor) {
        if (request == null || request.keywords() == null || request.keywords().isBlank()) {
            throw new InvalidJobSearchException("Search keywords are required");
        }
        if (request.postedWithinDays() != null
                && !JobSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(request.postedWithinDays())) {
            throw new InvalidJobSearchException("postedWithinDays must be 1, 3, 7, 14, or 30");
        }
        String apiKey = secret(JSearchClientConfig.KEY_PROPERTY);
        if (apiKey == null) {
            log.warn("reasonCode={} provider={} missingFields=[{}]",
                    JobSearchUnavailableException.REASON_CONFIG_MISSING,
                    SOURCE,
                    JSearchClientConfig.KEY_PROPERTY);
            throw new JobSearchUnavailableException(
                    UNAVAILABLE_MESSAGE,
                    JobSearchUnavailableException.REASON_CONFIG_MISSING,
                    null);
        }
        String datePosted = JSearchDatePosted.queryValue(request.postedWithinDays());
        String query = composeQuery(request);
        String cursorValue = cursor == null || cursor.isBlank() ? null : cursor.strip();
        JSearchResponseJson body = execute(() -> jsearchRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(JSearchClientConfig.SEARCH_PATH)
                            .queryParam("query", query)
                            .queryParam("num_pages", 1)
                            .queryParam("country", JSearchClientConfig.COUNTRY)
                            .queryParam("language", JSearchClientConfig.LANGUAGE)
                            .queryParam("date_posted", datePosted);
                    if (cursorValue != null) {
                        uriBuilder.queryParam("cursor", cursorValue);
                    }
                    return uriBuilder.build();
                })
                .header("x-api-key", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 429, (req, response) -> {
                    logUpstream(response.getStatusCode().value());
                    throw new JobSearchRateLimitedException(
                            "Job search rate limit reached. Try again later.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, response) -> {
                    int status = response.getStatusCode().value();
                    logUpstream(status);
                    throw new JobSearchUnavailableException(
                            UNAVAILABLE_MESSAGE,
                            JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                            status);
                })
                .onStatus(HttpStatusCode::isError, (req, response) -> {
                    int status = response.getStatusCode().value();
                    logUpstream(status);
                    throw new JobSearchUnavailableException(
                            UNAVAILABLE_MESSAGE,
                            JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                            status);
                })
                .body(JSearchResponseJson.class));
        List<ProviderJobHit> mapped = mapAll(body, request);
        String nextCursor = nextCursor(body);
        log.info("reasonCode=SEARCH_OK provider={} queryHash={} cursorHash={} resultCount={}",
                SOURCE,
                queryHash(query, request, cursorValue),
                JobSearchCacheKeys.cursorHash(cursorValue),
                mapped.size());
        boolean hasMore = nextCursor != null;
        return new ProviderSearchPage(mapped, hasMore, null, nextCursor);
    }

    private List<ProviderJobHit> mapAll(JSearchResponseJson body, JobSearchRequest request) {
        List<ProviderJobHit> mapped = new ArrayList<>();
        for (JSearchJobJson job : jobs(body)) {
            ProviderJobHit hit = map(job);
            if (hit == null) {
                continue;
            }
            if (!JSearchDatePosted.withinPostedWindow(hit.postedAt(), request.postedWithinDays(), clock)) {
                continue;
            }
            mapped.add(hit);
        }
        return dedupe(mapped);
    }

    private static List<JSearchJobJson> jobs(JSearchResponseJson body) {
        if (body == null || body.data == null || body.data.isNull()) {
            return List.of();
        }
        JsonNode jobsNode = body.data.isArray() ? body.data : body.data.get("jobs");
        if (jobsNode == null || !jobsNode.isArray()) {
            return List.of();
        }
        List<JSearchJobJson> jobs = JSON.convertValue(jobsNode, JOBS_TYPE);
        return jobs == null ? List.of() : jobs;
    }

    private static String nextCursor(JSearchResponseJson body) {
        if (body == null) {
            return null;
        }
        String topLevel = blankToNull(body.cursor);
        if (topLevel != null) {
            return topLevel;
        }
        if (body.data != null && body.data.isObject()) {
            JsonNode cursor = body.data.get("cursor");
            if (cursor != null && cursor.isTextual()) {
                return blankToNull(cursor.asText());
            }
        }
        return null;
    }

    private static ProviderJobHit map(JSearchJobJson job) {
        if (job == null) {
            return null;
        }
        String title = blankToNull(job.jobTitle);
        String company = blankToNull(job.employerName);
        String rawId = blankToNull(job.jobId);
        String sourceUrl = chooseApplyUrl(job);
        if (title == null || company == null || rawId == null || sourceUrl == null) {
            return null;
        }
        String description = HtmlTextExtractor.toPlainText(job.jobDescription);
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS);
        }
        return new ProviderJobHit(
                rawId,
                title,
                company,
                location(job),
                blankToNull(job.jobPostedAtDatetimeUtc),
                description,
                sourceUrl,
                blankToNull(job.jobPublisher),
                SOURCE
        );
    }

    private static List<ProviderJobHit> dedupe(List<ProviderJobHit> hits) {
        Map<String, ProviderJobHit> unique = new LinkedHashMap<>();
        for (ProviderJobHit hit : hits) {
            String urlKey = canonicalApplyUrl(hit.sourceUrl());
            String key = urlKey.isBlank()
                    ? "fp:" + JobFingerprint.normalize(hit.company())
                    + "|" + JobFingerprint.normalize(hit.title())
                    + "|" + JobFingerprint.normalize(hit.location())
                    + "|" + JobFingerprint.normalize(hit.postedAt())
                    : "url:" + urlKey;
            unique.putIfAbsent(key, hit);
        }
        return new ArrayList<>(unique.values());
    }

    private static String chooseApplyUrl(JSearchJobJson job) {
        if (job.applyOptions != null) {
            String first = null;
            for (JSearchApplyOption option : job.applyOptions) {
                if (option == null) {
                    continue;
                }
                String link = blankToNull(option.applyLink);
                if (link == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(option.isDirect)) {
                    return link;
                }
                if (first == null) {
                    first = link;
                }
            }
            if (first != null) {
                return first;
            }
        }
        return blankToNull(job.jobApplyLink);
    }

    private static String location(JSearchJobJson job) {
        if (job.jobLocation != null && !job.jobLocation.isBlank()) {
            return job.jobLocation.strip();
        }
        StringBuilder location = new StringBuilder();
        appendPart(location, job.jobCity);
        appendPart(location, job.jobState);
        appendPart(location, job.jobCountry);
        return location.isEmpty() ? null : location.toString();
    }

    private static void appendPart(StringBuilder target, String part) {
        String value = blankToNull(part);
        if (value == null) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(value);
    }

    private static String canonicalApplyUrl(String sourceUrl) {
        String normalized = JobFingerprint.normalizeUrl(sourceUrl);
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        return normalized;
    }

    private static String composeQuery(JobSearchRequest request) {
        if (request.location() == null || request.location().isBlank()) {
            return request.keywords();
        }
        return request.keywords() + " in " + request.location();
    }

    static String queryHash(String query, JobSearchRequest request) {
        return queryHash(query, request, null);
    }

    static String queryHash(String query, JobSearchRequest request, String cursor) {
        String raw = SOURCE + "|" + JSearchClientConfig.COUNTRY + "|" + JobFingerprint.normalize(query)
                + "|" + (cursor == null || cursor.isBlank()
                        ? request.page()
                        : JobSearchCacheKeys.cursorHash(cursor))
                + "|" + JSearchDatePosted.queryValue(request.postedWithinDays())
                + "|" + (request.postedWithinDays() == null ? "" : request.postedWithinDays());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return "unavailable";
        }
    }

    private String secret(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private void logUpstream(int status) {
        log.warn("reasonCode={} upstreamStatus={} provider={}",
                JobSearchUnavailableException.REASON_UPSTREAM_STATUS, status, SOURCE);
    }

    private JSearchResponseJson execute(Supplier<JSearchResponseJson> call) {
        try {
            JSearchResponseJson body = call.get();
            return body == null ? new JSearchResponseJson() : body;
        } catch (InvalidJobSearchException
                 | JobSearchRateLimitedException
                 | JobSearchTimeoutException
                 | JobSearchUnavailableException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.warn("reasonCode=NETWORK_OR_TIMEOUT exceptionClass={} provider={}",
                    ex.getClass().getName(), SOURCE);
            throw new JobSearchTimeoutException("Job search timed out.", ex);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            logUpstream(status);
            if (status == 429) {
                throw new JobSearchRateLimitedException(
                        "Job search rate limit reached. Try again later.");
            }
            throw new JobSearchUnavailableException(
                    UNAVAILABLE_MESSAGE,
                    JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                    status,
                    ex);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class JSearchResponseJson {
        public JsonNode data;
        public String cursor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class JSearchJobJson {
        @JsonProperty("job_id")
        public String jobId;
        @JsonProperty("job_title")
        public String jobTitle;
        @JsonProperty("employer_name")
        public String employerName;
        @JsonProperty("job_city")
        public String jobCity;
        @JsonProperty("job_state")
        public String jobState;
        @JsonProperty("job_country")
        public String jobCountry;
        @JsonProperty("job_location")
        public String jobLocation;
        @JsonProperty("job_posted_at_datetime_utc")
        public String jobPostedAtDatetimeUtc;
        @JsonProperty("job_description")
        public String jobDescription;
        @JsonProperty("job_apply_link")
        public String jobApplyLink;
        @JsonProperty("job_publisher")
        public String jobPublisher;
        @JsonProperty("apply_options")
        public List<JSearchApplyOption> applyOptions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class JSearchApplyOption {
        @JsonProperty("apply_link")
        public String applyLink;
        @JsonProperty("is_direct")
        public Boolean isDirect;
        public String publisher;
    }
}
