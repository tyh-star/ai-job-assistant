package aijob.freehire;

import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobDetailUnavailableException;
import aijob.exception.JobNotFoundException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The raw transport to FreeHire's hosted API. Two calls: the agent search, and one
 * job's detail by slug. Nothing here interprets a result — it decodes the response body
 * and maps a failure onto the shared job-search exceptions
 * ({@link JobSearchRateLimitedException}, {@link JobSearchTimeoutException},
 * {@link JobSearchUnavailableException}), the same three {@code JobSearchProvider}
 * already surfaces for JSearch and Adzuna, so the controller layer's error handling
 * needs no FreeHire-specific branch.
 *
 * <p><b>Concurrency budget.</b> {@code maxConcurrentRequests} bounds how many requests
 * this class has in flight AT ONCE, backend-wide — not per caller and not per user. It
 * is a fairness/politeness cap on our own egress, independent of FreeHire's own
 * IP-keyed 300/min limit on {@code /agent/jobs/search} (confirmed live via the
 * {@code X-RateLimit-*} response headers): a shared backend process behind one egress
 * address already spends that budget as ONE consumer by construction, so nothing here
 * multiplies it per signed-in user.
 *
 * <p><b>Per-minute rate budget.</b> {@code searchRateLimiter} and {@code
 * readRateLimiter} ({@link FreeHireRateLimiter}) are a SEPARATE mechanism from the
 * concurrency semaphore above — see that class's javadoc for why a concurrency cap
 * alone is not a rate limit. Checked before the concurrency gate is even touched: an
 * exhausted per-minute budget fails fast without waiting on, or consuming, a concurrency
 * slot. Shared by every caller of this process, not per user — the whole point being a
 * true backend-wide ceiling on call volume, not a per-user allotment.
 *
 * <p><b>429 handling.</b> On a 429 this class does not retry. It reads {@code
 * Retry-After} when FreeHire sent one and attaches it to
 * {@link JobSearchRateLimitedException}, so the decision of when to try again belongs to
 * whoever calls this — not to a retry loop here that would burn the same limit faster.
 * The same exception type also carries OUR OWN local-budget rejections (see {@link
 * #checkLocalBudget}), distinguished only by message and log line — both are, from a
 * caller's perspective, "try again in N seconds", so callers do not need two branches.
 */
@Component
public class FreeHireClient {

    static final String UNAVAILABLE_MESSAGE = "Job search is temporarily unavailable.";
    static final String RATE_LIMITED_MESSAGE = "Job search rate limit reached. Try again later.";
    static final String LOCAL_BUDGET_MESSAGE = "Search is temporarily busy. Please try again shortly.";
    static final String TIMEOUT_MESSAGE = "Job search timed out.";

    private static final Logger log = LoggerFactory.getLogger(FreeHireClient.class);

    private final RestClient freeHireRestClient;
    private final Semaphore concurrencyGate;
    private final FreeHireRateLimiter searchRateLimiter;
    private final FreeHireRateLimiter readRateLimiter;

    public FreeHireClient(
            @Qualifier("freeHireRestClient") RestClient freeHireRestClient,
            @org.springframework.beans.factory.annotation.Value(
                    "${aijob.freehire.max-concurrent-requests:5}") int maxConcurrentRequests,
            @Qualifier("freeHireSearchRateLimiter") FreeHireRateLimiter searchRateLimiter,
            @Qualifier("freeHireReadRateLimiter") FreeHireRateLimiter readRateLimiter) {
        this.freeHireRestClient = freeHireRestClient;
        this.concurrencyGate = new Semaphore(Math.max(1, maxConcurrentRequests));
        this.searchRateLimiter = searchRateLimiter;
        this.readRateLimiter = readRateLimiter;
    }

    /**
     * {@code GET /agent/jobs/search} — the full-description variant, always used here
     * (never the truncated {@code /jobs/search}), so one request carries the complete
     * JD and a second detail fetch is not needed to render a search result.
     */
    public FreeHireSearchResponseJson search(MultiValueMap<String, String> queryParams) {
        checkLocalBudget(searchRateLimiter, "search");
        return withGate(() -> execute(() -> freeHireRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/agent/jobs/search")
                        .queryParams(queryParams)
                        .build())
                .retrieve()
                .onStatus(status -> status.value() == 429, this::handleRateLimited)
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(FreeHireSearchResponseJson.class)));
    }

    /**
     * {@code GET /jobs/{slug}}. {@code slug} is FreeHire's opaque {@code public_slug} —
     * passed through verbatim as a path segment; {@code RestClient}'s URI builder
     * percent-encodes it, so a slug is never interpreted as extra path structure.
     *
     * <p>A 404 here means the UPSTREAM posting's own detail lookup failed -- confirmed
     * live to genuinely happen for a posting that was still listed in a search result
     * moments earlier (the source pulled it in between). That is a different fact from
     * this site's own "not found" (an unowned job, a malformed id, ...), so it is a
     * distinct exception type -- see {@link JobDetailUnavailableException}'s javadoc for
     * why callers (and the frontend) must never treat the two the same way.
     *
     * @throws JobDetailUnavailableException when FreeHire's own detail lookup 404s or
     *         answers with no body
     */
    public FreeHireJobJson getBySlug(String slug) {
        checkLocalBudget(readRateLimiter, "read");
        FreeHireJobDetailResponseJson body = withGate(() -> execute(() -> freeHireRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/jobs/{slug}").build(slug))
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new JobDetailUnavailableException(JobDetailUnavailableException.CODE);
                })
                .onStatus(status -> status.value() == 429, this::handleRateLimited)
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(FreeHireJobDetailResponseJson.class)));
        if (body == null || body.data() == null) {
            throw new JobDetailUnavailableException(JobDetailUnavailableException.CODE);
        }
        return body.data();
    }

    /**
     * {@code GET /geo/cities?q=&country=} — a population-ranked city-name prefix
     * search, confirmed live 2026-09-05 ({@code {"data":[{"value":"Toronto",
     * "country":"ca"}]}} for {@code ?q=Toronto&country=ca}) and matching the pinned
     * upstream source's {@code internal/api/handler/geo.go} exactly: public,
     * unauthenticated, a blank {@code q} yields an empty (not null) list. Billed against
     * the same read budget as {@link #getBySlug} — a lightweight reference lookup, not
     * the heavier search endpoint.
     */
    public java.util.List<FreeHireGeoCityJson> searchCities(String q, String country) {
        checkLocalBudget(readRateLimiter, "read");
        FreeHireGeoCitiesResponseJson body = withGate(() -> execute(() -> freeHireRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/geo/cities")
                        .queryParamIfPresent("q", java.util.Optional.ofNullable(q))
                        .queryParamIfPresent("country", java.util.Optional.ofNullable(country))
                        .build())
                .retrieve()
                .onStatus(status -> status.value() == 429, this::handleRateLimited)
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(FreeHireGeoCitiesResponseJson.class)));
        return body == null ? java.util.List.of() : body.dataOrEmpty();
    }

    /**
     * Fails fast — no waiting, no queueing — when this operation's own per-minute
     * budget is already spent this window. This is OUR policy protecting FreeHire's
     * limit with a safety margin, not FreeHire itself refusing the call; the resulting
     * {@link JobSearchRateLimitedException#getRetryAfterSeconds()} is therefore an
     * estimate of our own window, never a value FreeHire sent.
     */
    private void checkLocalBudget(FreeHireRateLimiter limiter, String operation) {
        FreeHireRateLimiter.Outcome outcome = limiter.tryAcquire();
        if (!outcome.allowed()) {
            log.warn("reasonCode=LOCAL_RATE_BUDGET_EXCEEDED operation={} retryAfterSeconds={}",
                    operation, outcome.retryAfterSeconds());
            throw new JobSearchRateLimitedException(LOCAL_BUDGET_MESSAGE, outcome.retryAfterSeconds());
        }
    }

    private <T> T withGate(Supplier<T> call) {
        boolean acquired;
        try {
            // A bounded wait, not an unbounded block: a caller stuck waiting for a gate
            // slot forever is the same failure mode as an upstream that never answers,
            // and both must surface the same way rather than hanging the request thread.
            acquired = concurrencyGate.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JobSearchUnavailableException("Interrupted while waiting for search capacity", ex);
        }
        if (!acquired) {
            throw new JobSearchUnavailableException(
                    "Too many concurrent searches; try again shortly",
                    JobSearchUnavailableException.REASON_UPSTREAM_STATUS,
                    null);
        }
        try {
            return call.get();
        } finally {
            concurrencyGate.release();
        }
    }

    private void handleRateLimited(org.springframework.http.HttpRequest req,
                                   org.springframework.http.client.ClientHttpResponse response) {
        Integer retryAfter = parseRetryAfter(response.getHeaders());
        logUpstreamAndClassify(429);
        throw new JobSearchRateLimitedException(RATE_LIMITED_MESSAGE, retryAfter);
    }

    private void handleError(org.springframework.http.HttpRequest req,
                             org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        // 401/403 are the UPSTREAM refusing an unauthenticated read it normally allows
        // (or an outage behind Cloudflare) -- never our own session. This class never
        // throws anything auth-related, so a caller cannot mistake one for the other:
        // there is nothing here to mistake it for.
        throw new JobSearchUnavailableException(
                UNAVAILABLE_MESSAGE,
                logUpstreamAndClassify(status),
                status);
    }

    /**
     * RFC 9110 delta-seconds only. FreeHire's own limiter (see
     * {@code internal/api/ratelimit/throttler.go} in the upstream source) always sends
     * whole seconds, never an HTTP-date, so that is the only form read here; anything
     * else is treated as "no usable hint" rather than guessed at.
     */
    private static Integer parseRetryAfter(HttpHeaders headers) {
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(raw.strip());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (InvalidJobSearchException
                 | JobNotFoundException
                 | JobSearchRateLimitedException
                 | JobSearchTimeoutException
                 | JobSearchUnavailableException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            // A connect/read timeout, or the connection itself failing (refused,
            // reset, DNS) -- thrown by the request factory before any status line was
            // even read. Confirmed by test: this is NOT what the size cap throws (see
            // the RestClientException branch below) -- only true "could not reach or
            // read from the socket" lands here.
            log.warn("reasonCode=NETWORK_OR_TIMEOUT exceptionClass={}", ex.getClass().getName());
            throw new JobSearchTimeoutException(TIMEOUT_MESSAGE, ex);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 429) {
                logUpstreamAndClassify(status);
                throw new JobSearchRateLimitedException(
                        RATE_LIMITED_MESSAGE, parseRetryAfter(ex.getResponseHeaders() == null
                                ? new HttpHeaders() : ex.getResponseHeaders()));
            }
            if (status == 404) {
                logUpstreamAndClassify(status);
                throw new JobNotFoundException("Job not found");
            }
            throw new JobSearchUnavailableException(
                    UNAVAILABLE_MESSAGE,
                    logUpstreamAndClassify(status),
                    status,
                    ex);
        } catch (RestClientException ex) {
            // Everything else RestClient can throw once a 2xx status line came back:
            // Jackson failing to parse the body (malformed JSON), or the size-cap
            // interceptor's IOException surfacing while the message converter reads the
            // stream (confirmed by test -- NOT ResourceAccessException, because the
            // request factory already succeeded; the failure is in converting the body
            // it handed back). Both are "the response could not be trusted", which is an
            // availability problem, not a timeout and not a 4xx from FreeHire itself.
            log.warn("reasonCode=UNREADABLE_RESPONSE exceptionClass={}", ex.getClass().getName());
            throw new JobSearchUnavailableException(
                    UNAVAILABLE_MESSAGE, JobSearchUnavailableException.REASON_UPSTREAM_STATUS, null, ex);
        }
    }

    /**
     * Logs the upstream failure and returns the reason code to attach to it. A 400 is
     * FreeHire telling US our own request was malformed -- a bug in this backend's param
     * translation, never an upstream outage and never the calling user's fault -- so it
     * is logged at ERROR with its own distinct reason code, rather than folded into the
     * same WARN-level "upstream had trouble" bucket 401/403/5xx use. Both still surface
     * to the eventual caller as the same honest "search is temporarily unavailable";
     * only the log line (and therefore what an operator finds when triaging) differs.
     */
    private static String logUpstreamAndClassify(int status) {
        if (status == 400) {
            log.error("reasonCode={} upstreamStatus={} provider=FREEHIRE "
                            + "-- FreeHire rejected OUR request as malformed; this is our own "
                            + "param-translation bug, not an upstream outage or a user error",
                    JobSearchUnavailableException.REASON_UPSTREAM_REJECTED_OUR_REQUEST, status);
            return JobSearchUnavailableException.REASON_UPSTREAM_REJECTED_OUR_REQUEST;
        }
        log.warn("reasonCode={} upstreamStatus={} provider=FREEHIRE",
                JobSearchUnavailableException.REASON_UPSTREAM_STATUS, status);
        return JobSearchUnavailableException.REASON_UPSTREAM_STATUS;
    }
}
