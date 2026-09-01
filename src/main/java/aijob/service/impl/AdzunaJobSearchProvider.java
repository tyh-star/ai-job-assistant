package aijob.service.impl;

import aijob.config.ExternalJobSearchEnabledCondition;
import aijob.dto.JobSearchRequest;
import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import aijob.jobsearch.JobSearchCapability;
import aijob.jobsearch.ProviderJobHit;
import aijob.jobsearch.ProviderSearchPage;
import aijob.service.JobSearchProvider;
import aijob.util.HtmlTextExtractor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@Conditional(ExternalJobSearchEnabledCondition.class)
public class AdzunaJobSearchProvider implements JobSearchProvider {

    public static final String SOURCE = "ADZUNA";
    public static final String DEFAULT_COUNTRY_CODE = "ca";
    static final String APP_ID_PROPERTY = "ADZUNA_APP_ID";
    static final String APP_KEY_PROPERTY = "ADZUNA_APP_KEY";
    static final String COUNTRY_PROPERTY = "ADZUNA_COUNTRY_CODE";
    static final String UNAVAILABLE_MESSAGE = "Job search is temporarily unavailable.";
    static final String REASON_NETWORK_OR_TIMEOUT = "NETWORK_OR_TIMEOUT";

    private static final Logger log = LoggerFactory.getLogger(AdzunaJobSearchProvider.class);

    private final RestClient adzunaRestClient;
    private final Environment environment;
    private final AtomicBoolean configurationLogged = new AtomicBoolean(false);

    public AdzunaJobSearchProvider(
            @Qualifier("adzunaRestClient") RestClient adzunaRestClient,
            Environment environment) {
        this.adzunaRestClient = adzunaRestClient;
        this.environment = environment;
    }

    @Override
    public String providerName() {
        return SOURCE;
    }

    @Override
    public JobSearchCapability capability() {
        return JobSearchCapability.PREVIEW_ONLY;
    }

    @PostConstruct
    void logStartupConfiguration() {
        logConfigurationOnce();
    }

    @Override
    public ProviderSearchPage search(JobSearchRequest request) {
        if (request == null || request.keywords() == null || request.keywords().isBlank()) {
            throw new InvalidJobSearchException("Search keywords are required");
        }
        if (request.postedWithinDays() != null
                && !JobSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(request.postedWithinDays())) {
            throw new InvalidJobSearchException("postedWithinDays must be 1, 3, 7, 14, or 30");
        }
        logConfigurationOnce();
        String appId = secret(APP_ID_PROPERTY);
        String appKey = secret(APP_KEY_PROPERTY);
        if (appId == null || appKey == null) {
            throwConfigMissing(appId, appKey);
        }
        String country = countryCode();
        AdzunaSearchJson body = execute(country, () -> adzunaRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/api/jobs/{country}/search/{page}");
                    uriBuilder.queryParam("app_id", appId);
                    uriBuilder.queryParam("app_key", appKey);
                    uriBuilder.queryParam("what", request.keywords());
                    if (request.location() != null && !request.location().isBlank()) {
                        uriBuilder.queryParam("where", request.location());
                    }
                    uriBuilder.queryParam("results_per_page", request.pageSize());
                    if (request.postedWithinDays() != null) {
                        uriBuilder.queryParam("max_days_old", request.postedWithinDays());
                    }
                    return uriBuilder.build(country, request.page());
                })
                .retrieve()
                .onStatus(status -> status.value() == 429, (req, response) -> {
                    logUpstreamStatus(response.getStatusCode().value(), country);
                    throw new JobSearchRateLimitedException(
                            "Job search rate limit reached. Try again later.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, response) -> {
                    int status = response.getStatusCode().value();
                    logUpstreamStatus(status, country);
                    throw new JobSearchUnavailableException(
                            UNAVAILABLE_MESSAGE,
                            JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                            status);
                })
                .onStatus(HttpStatusCode::isError, (req, response) -> {
                    int status = response.getStatusCode().value();
                    logUpstreamStatus(status, country);
                    throw new JobSearchUnavailableException(
                            UNAVAILABLE_MESSAGE,
                            JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                            status);
                })
                .body(AdzunaSearchJson.class));
        return toResponse(request, body);
    }

    String countryCode() {
        String value = environment.getProperty(COUNTRY_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_COUNTRY_CODE;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z]{2}")) {
            return DEFAULT_COUNTRY_CODE;
        }
        return normalized;
    }

    private String secret(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private void logConfigurationOnce() {
        if (!configurationLogged.compareAndSet(false, true)) {
            return;
        }
        String rawId = environment.getProperty(APP_ID_PROPERTY);
        String rawKey = environment.getProperty(APP_KEY_PROPERTY);
        boolean appIdPresent = rawId != null && !rawId.isBlank();
        boolean appKeyPresent = rawKey != null && !rawKey.isBlank();
        int appIdLength = appIdPresent ? rawId.strip().length() : 0;
        int appKeyLength = appKeyPresent ? rawKey.strip().length() : 0;
        log.info(
                "Adzuna configuration: appIdPresent={} appIdLength={} appKeyPresent={} appKeyLength={} country={}",
                appIdPresent,
                appIdLength,
                appKeyPresent,
                appKeyLength,
                countryCode());
    }

    private void throwConfigMissing(String appId, String appKey) {
        List<String> missingFields = new ArrayList<>();
        if (appId == null) {
            missingFields.add(APP_ID_PROPERTY);
        }
        if (appKey == null) {
            missingFields.add(APP_KEY_PROPERTY);
        }
        log.warn("reasonCode={} missingFields={}",
                JobSearchUnavailableException.REASON_CONFIG_MISSING,
                missingFields);
        throw new JobSearchUnavailableException(
                UNAVAILABLE_MESSAGE,
                JobSearchUnavailableException.REASON_CONFIG_MISSING,
                null);
    }

    private void logUpstreamStatus(int status, String country) {
        log.warn(
                "reasonCode={} upstreamStatus={} provider=ADZUNA country={}",
                JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                status,
                country);
    }

    private AdzunaSearchJson execute(String country, Supplier<AdzunaSearchJson> call) {
        try {
            AdzunaSearchJson body = call.get();
            return body == null ? new AdzunaSearchJson() : body;
        } catch (InvalidJobSearchException
                 | JobSearchRateLimitedException
                 | JobSearchTimeoutException
                 | JobSearchUnavailableException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.warn("reasonCode={} exceptionClass={}",
                    REASON_NETWORK_OR_TIMEOUT,
                    ex.getClass().getName());
            throw new JobSearchTimeoutException("Job search timed out.", ex);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            logUpstreamStatus(status, country);
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

    private static ProviderSearchPage toResponse(JobSearchRequest request, AdzunaSearchJson body) {
        List<ProviderJobHit> results = new ArrayList<>();
        if (body.results != null) {
            for (AdzunaJobJson job : body.results) {
                ProviderJobHit mapped = map(job);
                if (mapped != null) {
                    results.add(mapped);
                }
            }
        }
        boolean hasMore = false;
        Long total = body.count;
        if (total != null) {
            hasMore = (long) request.page() * request.pageSize() < total;
        } else {
            hasMore = results.size() >= request.pageSize();
        }
        return new ProviderSearchPage(results, hasMore, total);
    }

    private static ProviderJobHit map(AdzunaJobJson job) {
        if (job == null) {
            return null;
        }
        String title = blankToNull(job.title);
        String company = job.company == null ? null : blankToNull(job.company.displayName);
        String sourceUrl = blankToNull(job.redirectUrl);
        if (title == null || company == null || sourceUrl == null) {
            return null;
        }
        return new ProviderJobHit(
                job.id == null ? null : String.valueOf(job.id),
                title,
                company,
                job.location == null ? null : blankToNull(job.location.displayName),
                blankToNull(job.created),
                HtmlTextExtractor.toPlainText(job.description),
                sourceUrl,
                SOURCE,
                SOURCE
        );
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AdzunaSearchJson {
        public Long count;
        public List<AdzunaJobJson> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AdzunaJobJson {
        public Object id;
        public String title;
        public String description;
        public String created;
        @JsonProperty("redirect_url")
        public String redirectUrl;
        public AdzunaNamedJson location;
        public AdzunaNamedJson company;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AdzunaNamedJson {
        @JsonProperty("display_name")
        public String displayName;
    }
}
