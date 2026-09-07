package aijob.freehire;

import aijob.exception.InvalidJobSearchException;
import aijob.exception.JobDetailUnavailableException;
import aijob.exception.JobSearchRateLimitedException;
import aijob.exception.JobSearchTimeoutException;
import aijob.exception.JobSearchUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FreeHireClient} against a real loopback HTTP server -- not a mocked
 * {@code RestClient} -- so the assertions are about actual requests and actual
 * responses: what path and headers went out, what a 429/timeout/oversized-body/
 * malformed-JSON response does to the caller, and that a rate limit is never worked
 * around by retrying.
 */
class FreeHireClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A budget generous enough that no test below (all far under 1000 requests) can ever hit it by accident. */
    private static final int GENEROUS_BUDGET = 1_000_000;

    private FreeHireClient clientFor(HttpServer server) {
        return clientFor(server, 5_000_000, Duration.ofSeconds(2));
    }

    private FreeHireClient clientFor(HttpServer server, long maxResponseBytes, Duration readTimeout) {
        return clientFor(server, maxResponseBytes, readTimeout,
                new FreeHireRateLimiter(GENEROUS_BUDGET, Clock.systemUTC()),
                new FreeHireRateLimiter(GENEROUS_BUDGET, Clock.systemUTC()));
    }

    private FreeHireClient clientFor(HttpServer server, long maxResponseBytes, Duration readTimeout,
                                      FreeHireRateLimiter searchRateLimiter, FreeHireRateLimiter readRateLimiter) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-job-assistant-test/1.0")
                .requestInterceptor(aijob.config.FreeHireApiConfig.freeHireSizeCapInterceptor(maxResponseBytes))
                .build();
        return new FreeHireClient(restClient, 5, searchRateLimiter, readRateLimiter);
    }

    private static HttpServer startServer(HttpExchangeHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", handler::handle);
        s.start();
        return s;
    }

    @FunctionalInterface
    private interface HttpExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static void respond(HttpExchange exchange, int status, String body, String... headerPairs) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            exchange.getResponseHeaders().add(headerPairs[i], headerPairs[i + 1]);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------------------
    // search: happy path, URL/header correctness
    // ---------------------------------------------------------------------------

    @Test
    void searchSendsTheExpectedPathHeadersAndParamsAndParsesTheEnvelope() throws Exception {
        StringBuilder capturedPath = new StringBuilder();
        // com.sun.net.httpserver.Headers is case-insensitive on lookup but not on the
        // map keys it exposes to forEach, so this reads through the exchange's own
        // getFirst (case-insensitive by contract) rather than through a plain HashMap
        // keyed by a guessed exact case.
        com.sun.net.httpserver.Headers[] capturedHeaders = new com.sun.net.httpserver.Headers[1];
        server = startServer(exchange -> {
            capturedPath.append(exchange.getRequestURI().toString());
            capturedHeaders[0] = exchange.getRequestHeaders();
            respond(exchange, 200, """
                    {"data":[{"public_slug":"acme-swe-abc123","source":"greenhouse",
                              "external_id":"gh-1","url":"https://boards.greenhouse.io/acme/jobs/1",
                              "title":"Software Engineer","company":"Acme","company_slug":"acme",
                              "location":"Remote - US","description":"<p>Build things.</p>",
                              "countries":["us"],"regions":["north_america"],"work_mode":"remote",
                              "cities":["San Francisco"],"skills":["java"],"is_tech":"tech",
                              "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                              "closed_at":null}],
                     "meta":{"total":1,"limit":20,"offset":0}}
                    """);
        });
        FreeHireClient client = clientFor(server);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "java engineer");
        params.add("countries", "us");
        params.add("work_mode", "remote");
        params.add("limit", "20");
        params.add("offset", "0");

        FreeHireSearchResponseJson result = client.search(params);

        assertTrue(capturedPath.toString().startsWith("/agent/jobs/search?"), capturedPath.toString());
        assertTrue(capturedPath.toString().contains("q=java+engineer") || capturedPath.toString().contains("q=java%20engineer"),
                "keyword must be encoded, not mangled: " + capturedPath);
        assertTrue(capturedPath.toString().contains("countries=us"));
        assertTrue(capturedPath.toString().contains("work_mode=remote"));
        assertEquals("ai-job-assistant-test/1.0", capturedHeaders[0].getFirst("User-Agent"));
        // Never anything that looks like our own site's auth or session state.
        assertNull(capturedHeaders[0].getFirst("Authorization"));
        assertNull(capturedHeaders[0].getFirst("Cookie"));

        assertEquals(1, result.metaOrEmpty().total());
        FreeHireJobJson hit = result.dataOrEmpty().get(0);
        assertEquals("acme-swe-abc123", hit.publicSlug());
        assertEquals("https://boards.greenhouse.io/acme/jobs/1", hit.url());
        assertEquals(List.of("us"), hit.countries());
        assertEquals("remote", hit.workMode());
        assertTrue(hit.description().contains("Build things"));
    }

    @Test
    void searchReportsIgnoredParamsWithoutFailingTheCall() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, """
                {"data":[],"meta":{"total":0,"limit":20,"offset":0,
                 "ignored_params":[{"param":"country","did_you_mean":"countries"}]}}
                """));
        FreeHireClient client = clientFor(server);

        FreeHireSearchResponseJson result = client.search(new LinkedMultiValueMap<>());

        assertEquals(1, result.metaOrEmpty().ignoredParamsOrEmpty().size());
        assertEquals("country", result.metaOrEmpty().ignoredParamsOrEmpty().get(0).param());
        assertEquals("countries", result.metaOrEmpty().ignoredParamsOrEmpty().get(0).didYouMean());
    }

    @Test
    void emptyResultsDoNotThrow() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, """
                {"data":[],"meta":{"total":0,"limit":20,"offset":0}}
                """));
        FreeHireClient client = clientFor(server);

        FreeHireSearchResponseJson result = client.search(new LinkedMultiValueMap<>());

        assertEquals(0, result.dataOrEmpty().size());
        assertEquals(0, result.metaOrEmpty().total());
    }

    // ---------------------------------------------------------------------------
    // detail
    // ---------------------------------------------------------------------------

    @Test
    void getBySlugPercentEncodesTheSlugAndParsesTheDataEnvelope() throws Exception {
        StringBuilder capturedPath = new StringBuilder();
        server = startServer(exchange -> {
            capturedPath.append(exchange.getRequestURI().toString());
            respond(exchange, 200, """
                    {"data":{"public_slug":"acme swe / abc","source":"greenhouse","external_id":"gh-1",
                             "url":"https://boards.greenhouse.io/acme/jobs/1","title":"Software Engineer",
                             "company":"Acme","company_slug":"acme","location":"Remote",
                             "description":"<p>Full JD here.</p>","countries":["us"],"regions":[],
                             "cities":[],"skills":[],"is_tech":"tech",
                             "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                             "closed_at":null}}
                    """);
        });
        FreeHireClient client = clientFor(server);

        FreeHireJobJson job = client.getBySlug("acme swe / abc");

        // The raw slug reached the server URL-encoded, not as literal path separators
        // that would be interpreted as extra path structure.
        assertTrue(capturedPath.toString().contains("%20") || capturedPath.toString().contains("+"),
                capturedPath.toString());
        assertTrue(capturedPath.toString().contains("%2F"), "a slash inside a slug must be escaped: " + capturedPath);
        assertEquals("Software Engineer", job.title());
        assertTrue(job.description().contains("Full JD here"));
    }

    @Test
    void notFoundSlugThrowsJobDetailUnavailableWithTheStableCode() throws Exception {
        // A distinct type from JobNotFoundException (this site's OWN 404s) -- see
        // JobDetailUnavailableException's javadoc for why the two must never be
        // confused. The message is a stable, matchable code the frontend's
        // apiErrorKey routes on, not free text.
        server = startServer(exchange -> respond(exchange, 404, """
                {"error":"not found"}
                """));
        FreeHireClient client = clientFor(server);

        JobDetailUnavailableException ex = assertThrows(
                JobDetailUnavailableException.class,
                () -> client.getBySlug("does-not-exist"));
        assertEquals("JOB_DETAIL_NO_LONGER_AVAILABLE", ex.getMessage());
    }

    // ---------------------------------------------------------------------------
    // geo/cities -- the real city-search endpoint, confirmed live 2026-09-05
    // ---------------------------------------------------------------------------

    @Test
    void searchCitiesSendsQAndCountryAndParsesTheDataEnvelope() throws Exception {
        StringBuilder capturedPath = new StringBuilder();
        server = startServer(exchange -> {
            capturedPath.append(exchange.getRequestURI().toString());
            respond(exchange, 200, """
                    {"data":[{"value":"Toronto","country":"ca"}]}
                    """);
        });
        FreeHireClient client = clientFor(server);

        java.util.List<FreeHireGeoCityJson> result = client.searchCities("Toronto", "ca");

        assertTrue(capturedPath.toString().startsWith("/geo/cities?"), capturedPath.toString());
        assertTrue(capturedPath.toString().contains("q=Toronto"));
        assertTrue(capturedPath.toString().contains("country=ca"));
        assertEquals(1, result.size());
        assertEquals("Toronto", result.get(0).value());
        assertEquals("ca", result.get(0).country());
    }

    @Test
    void searchCitiesWithNoCountryOmitsTheParamEntirely() throws Exception {
        StringBuilder capturedPath = new StringBuilder();
        server = startServer(exchange -> {
            capturedPath.append(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[]}");
        });
        FreeHireClient client = clientFor(server);

        client.searchCities("Tor", null);

        assertFalse(capturedPath.toString().contains("country="),
                "no country selected must not send a bogus empty country param: " + capturedPath);
    }

    @Test
    void searchCitiesWithNoMatchesReturnsAnEmptyListNotNull() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{\"data\":[]}"));
        FreeHireClient client = clientFor(server);

        assertEquals(List.of(), client.searchCities("zzzznotarealcity", null));
    }

    // ---------------------------------------------------------------------------
    // errors: 429, 401/403, 5xx, timeout, oversized body, malformed JSON
    // ---------------------------------------------------------------------------

    @Test
    void rateLimitedWithRetryAfterIsReportedAndNeverRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429, """
                    {"error":"too many requests, please try again later"}
                    """, "Retry-After", "42");
        });
        FreeHireClient client = clientFor(server);

        JobSearchRateLimitedException ex = assertThrows(JobSearchRateLimitedException.class,
                () -> client.search(new LinkedMultiValueMap<>()));

        assertEquals(42, ex.getRetryAfterSeconds());
        assertEquals(1, requests.get(), "a 429 must not be retried by this client -- looping "
                + "or shortening the wait is exactly what Retry-After forbids");
    }

    @Test
    void rateLimitedWithoutRetryAfterReportsNoGuessedNumber() throws Exception {
        server = startServer(exchange -> respond(exchange, 429, """
                {"error":"too many requests"}
                """));
        FreeHireClient client = clientFor(server);

        JobSearchRateLimitedException ex = assertThrows(JobSearchRateLimitedException.class,
                () -> client.search(new LinkedMultiValueMap<>()));

        assertNull(ex.getRetryAfterSeconds(), "no Retry-After header means no number should be invented");
    }

    @Test
    void upstream401IsUnavailableNeverOurOwnAuthFailure() throws Exception {
        server = startServer(exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}"));
        FreeHireClient client = clientFor(server);

        JobSearchUnavailableException ex = assertThrows(JobSearchUnavailableException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(401, ex.getUpstreamStatus());
        assertEquals(JobSearchUnavailableException.REASON_UPSTREAM_STATUS, ex.getReasonCode());
    }

    @Test
    void upstream403IsUnavailable() throws Exception {
        server = startServer(exchange -> respond(exchange, 403, "{\"error\":\"forbidden\"}"));
        FreeHireClient client = clientFor(server);

        JobSearchUnavailableException ex = assertThrows(JobSearchUnavailableException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(403, ex.getUpstreamStatus());
    }

    @Test
    void upstream400IsUnavailableNotSilentlyEmptyResults() throws Exception {
        server = startServer(exchange -> respond(exchange, 400, "{\"error\":\"bad request\"}"));
        FreeHireClient client = clientFor(server);

        JobSearchUnavailableException ex = assertThrows(JobSearchUnavailableException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(400, ex.getUpstreamStatus());
        // A 400 means WE sent a malformed request -- classified distinctly from a
        // genuine upstream outage (401/403/5xx) so it is diagnosed as our own
        // param-translation bug rather than lost in generic "upstream is down" alerts.
        assertEquals(JobSearchUnavailableException.REASON_UPSTREAM_REJECTED_OUR_REQUEST, ex.getReasonCode());
    }

    @Test
    void upstream503IsUnavailable() throws Exception {
        server = startServer(exchange -> respond(exchange, 503, "{\"error\":\"unavailable\"}"));
        FreeHireClient client = clientFor(server);

        JobSearchUnavailableException ex = assertThrows(JobSearchUnavailableException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(503, ex.getUpstreamStatus());
    }

    @Test
    void upstream500IsUnavailable() throws Exception {
        server = startServer(exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        FreeHireClient client = clientFor(server);

        JobSearchUnavailableException ex = assertThrows(JobSearchUnavailableException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(500, ex.getUpstreamStatus());
    }

    @Test
    void aSlowResponseTimesOutRatherThanHanging() throws Exception {
        server = startServer(exchange -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"data\":[],\"meta\":{\"total\":0,\"limit\":20,\"offset\":0}}");
        });
        FreeHireClient client = clientFor(server, 5_000_000, Duration.ofMillis(500));

        assertThrows(JobSearchTimeoutException.class, () -> client.search(new LinkedMultiValueMap<>()));
    }

    @Test
    void aResponseOverTheSizeCapFailsRatherThanBeingBufferedInFull() throws Exception {
        String hugeBody = "{\"data\":[" + "x".repeat(200_000) + "],\"meta\":{}}";
        server = startServer(exchange -> respond(exchange, 200, hugeBody));
        // Cap far below the real body: the client must abort, not silently truncate.
        FreeHireClient client = clientFor(server, 1_000, Duration.ofSeconds(2));

        // Confirmed by running this test: the size-cap interceptor's IOException
        // surfaces while Jackson reads the (already-2xx) response body, which
        // RestClient reports as RestClientException, not ResourceAccessException --
        // so this is the same bucket as a malformed body, not a timeout.
        assertThrows(JobSearchUnavailableException.class, () -> client.search(new LinkedMultiValueMap<>()),
                "a response over the configured cap must fail the call");
    }

    @Test
    void malformedJsonDoesNotCrashUncaught() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{this is not json"));
        FreeHireClient client = clientFor(server);

        // Whatever the exact exception, it must be one of this client's own mapped
        // types (never an unmapped RuntimeException reaching the controller layer raw).
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertTrue(ex instanceof JobSearchUnavailableException
                        || ex instanceof JobSearchTimeoutException
                        || ex instanceof InvalidJobSearchException,
                "unexpected exception type: " + ex.getClass());
    }

    @Test
    void connectionRefusedIsUnavailableNotAHang() {
        // No server started: nothing is listening on this port.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestFactory(factory)
                .build();
        FreeHireClient client = new FreeHireClient(restClient, 5,
                new FreeHireRateLimiter(GENEROUS_BUDGET, Clock.systemUTC()),
                new FreeHireRateLimiter(GENEROUS_BUDGET, Clock.systemUTC()));

        assertThrows(JobSearchTimeoutException.class, () -> client.search(new LinkedMultiValueMap<>()));
    }

    // ---------------------------------------------------------------------------
    // per-minute rate BUDGET (distinct from the concurrency semaphore): a local
    // FreeHireRateLimiter wired in with a tiny budget and a controllable clock proves
    // the client fails fast, without any wait, once the window's budget is spent, and
    // that the search and read budgets are independent of each other.
    // ---------------------------------------------------------------------------

    @Test
    void searchBudgetExhaustionFailsFastWithoutCallingTheServer() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"data\":[],\"meta\":{\"total\":0,\"limit\":20,\"offset\":0}}");
        });
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        FreeHireClient client = clientFor(server, 5_000_000, Duration.ofSeconds(2),
                new FreeHireRateLimiter(2, clock), new FreeHireRateLimiter(GENEROUS_BUDGET, clock));

        // Two requests spend the whole per-minute search budget -- both reach the server.
        client.search(new LinkedMultiValueMap<>());
        client.search(new LinkedMultiValueMap<>());
        assertEquals(2, requests.get());

        // A third, still inside the same minute, must fail LOCALLY and fast: no wait, no
        // request reaching the server at all.
        JobSearchRateLimitedException ex = assertThrows(JobSearchRateLimitedException.class,
                () -> client.search(new LinkedMultiValueMap<>()));
        assertEquals(2, requests.get(), "a budget-exhausted call must never reach FreeHire");
        assertTrue(ex.getRetryAfterSeconds() != null && ex.getRetryAfterSeconds() > 0);
        assertEquals(FreeHireClient.LOCAL_BUDGET_MESSAGE, ex.getMessage());
    }

    @Test
    void searchAndReadBudgetsAreIndependent() throws Exception {
        server = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/agent/jobs/search")) {
                respond(exchange, 200, "{\"data\":[],\"meta\":{\"total\":0,\"limit\":20,\"offset\":0}}");
            } else {
                respond(exchange, 200, """
                        {"data":{"public_slug":"x","source":"greenhouse","external_id":"1",
                                 "url":"https://boards.greenhouse.io/x/jobs/1","title":"T","company":"C",
                                 "location":"L","description":"<p>d</p>","countries":[],"regions":[],
                                 "cities":[],"skills":[],"is_tech":"tech","posted_at":null,
                                 "created_at":null,"closed_at":null}}
                        """);
            }
        });
        Clock clock = Clock.systemUTC();
        FreeHireClient client = clientFor(server, 5_000_000, Duration.ofSeconds(2),
                new FreeHireRateLimiter(1, clock), new FreeHireRateLimiter(GENEROUS_BUDGET, clock));

        client.search(new LinkedMultiValueMap<>());
        assertThrows(JobSearchRateLimitedException.class, () -> client.search(new LinkedMultiValueMap<>()),
                "search budget of 1 must be spent");
        // The read (detail) budget is a SEPARATE limiter -- exhausting search must not
        // touch it.
        assertNotNull(client.getBySlug("x"), "the read budget must be unaffected by the search budget");
    }

    @Test
    void aBurstOfFastRequestsCannotExceedTheSharedMinuteBudget() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"data\":[],\"meta\":{\"total\":0,\"limit\":20,\"offset\":0}}");
        });
        int budget = 20;
        FreeHireClient client = clientFor(server, 5_000_000, Duration.ofSeconds(2),
                new FreeHireRateLimiter(budget, Clock.systemUTC()), new FreeHireRateLimiter(GENEROUS_BUDGET, Clock.systemUTC()));

        int threadCount = 50;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    client.search(new LinkedMultiValueMap<>());
                    allowed.incrementAndGet();
                } catch (JobSearchRateLimitedException ex) {
                    rejected.incrementAndGet();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Many threads, all completing fast, hammering the SAME shared limiter (no
        // per-caller state) -- the total that ever reached the server can never exceed
        // the configured budget, no matter how much concurrency piled on at once.
        assertEquals(budget, allowed.get());
        assertEquals(threadCount - budget, rejected.get());
        assertEquals(budget, requests.get());
    }

    /** A {@link Clock} whose instant can be advanced by a test, without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
