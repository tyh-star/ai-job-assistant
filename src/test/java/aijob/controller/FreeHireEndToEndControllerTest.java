package aijob.controller;

import aijob.AiJobApplication;
import aijob.jobsearch.ExternalJobIds;
import aijob.mapper.JobMapper;
import aijob.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Search, detail and save through the REAL HTTP stack -- {@code MockMvc} hitting the
 * actual {@code @RestController}, the actual default-wired {@code JobCatalogService},
 * the actual {@code RestClient} bean -- landing on a local loopback fixture standing in
 * for freehire.me. Nothing here is unit-mocked; this is the same call chain a real
 * browser request makes, minus the browser and minus the real freehire.me network hop.
 *
 * <p>Ends by confirming the saved posting is a real row in {@code jobs} with a real
 * numeric id -- what Match and Tailor read -- closing the loop the task calls out.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:freehiree2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
                // No aijob.job-catalog.provider: this is the real default (freehire).
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class FreeHireEndToEndControllerTest {

    private static HttpServer fixture;
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static String lastPath;

    /**
     * The exact real FreeHire slug confirmed live on 2026-09-06 (title + company + a
     * random suffix, 77 characters -- over {@code jobs.external_job_id}'s
     * {@code VARCHAR(64)}). Used only as a local, offline fixture value below -- this
     * constant never causes a real network request.
     */
    /**
     * A short slug, exactly the shape a row saved BEFORE the {@code persistId} fix
     * would have stored directly (its own raw slug, unhashed) -- fits well inside
     * {@code VARCHAR(64)} on its own, so nothing about a row like this ever failed;
     * simulates the rolling-upgrade case where such a row already exists.
     */
    private static final String LEGACY_SLUG = "legacy-acme-swe-e2e";

    private static final String LONG_SLUG =
            "magento-2-frontend-developer-hyva-experience-required-unotech-global-bptpehnr";
    /** The first 64 characters of {@link #LONG_SLUG} exactly -- shared with {@link #LONG_SLUG_COLLISION_CANDIDATE}. */
    private static final String LONG_SLUG_SHARED_PREFIX =
            "magento-2-frontend-developer-hyva-experience-required-unotech-gl";
    /**
     * Identical to {@link #LONG_SLUG} in its first 64 characters, differs only after --
     * proves truncating to 64 chars (instead of hashing) would have collided these two
     * into the same identity.
     */
    private static final String LONG_SLUG_COLLISION_CANDIDATE = LONG_SLUG_SHARED_PREFIX + "obal-zzzzzzzz";

    @DynamicPropertySource
    static void pointAtTheLocalFixture(DynamicPropertyRegistry registry) throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/agent/jobs/search", ex -> {
            REQUEST_COUNT.incrementAndGet();
            lastPath = ex.getRequestURI().toString();
            // "cities=London" gets its own branch: two real postings, same city name,
            // different countries (gb/ca) -- see cityFilterMatchesByNameOnlyAcrossCountries
            // below. This is the local fixture standing in for the proof that FreeHire's
            // cities facet is a bare name match with no per-document country narrowing
            // (confirmed by reading internal/job/job/job.go and query_filter.go, not
            // guessed), so it must never be confused with the single-Acme-posting default
            // path other tests in this class rely on.
            if (lastPath.contains("cities=London")) {
                respond(ex, 200, """
                        {"data":[{"public_slug":"london-gb-e2e","source":"greenhouse","external_id":"gh-lon-gb",
                                  "url":"https://boards.greenhouse.io/acme/jobs/lon-gb","title":"Engineer (London)",
                                  "company":"Acme UK","company_slug":"acme-uk","location":"London, UK",
                                  "description":"<p>London, United Kingdom role.</p>","countries":["gb"],
                                  "regions":[],"work_mode":"onsite","cities":["London"],"skills":[],
                                  "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                                  "created_at":"2026-08-30T00:00:00Z","closed_at":null},
                                 {"public_slug":"london-ca-e2e","source":"greenhouse","external_id":"gh-lon-ca",
                                  "url":"https://boards.greenhouse.io/acme/jobs/lon-ca","title":"Engineer (London)",
                                  "company":"Acme Canada","company_slug":"acme-ca","location":"London, Canada",
                                  "description":"<p>London, Ontario, Canada role.</p>","countries":["ca"],
                                  "regions":[],"work_mode":"onsite","cities":["London"],"skills":[],
                                  "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                                  "created_at":"2026-08-30T00:00:00Z","closed_at":null}],
                         "meta":{"total":2,"limit":20,"offset":0}}
                        """);
                return;
            }
            // "cities=Toronto" (and not also cities=London): shaped after a REAL
            // freehire.me response captured 2026-09-06 (city=Toronto, no other filter,
            // via the real production buildSearchParams/FreeHireClient code path, real
            // network, no fixture) -- one clean Toronto/CA match, and one GENUINE
            // multi-location posting whose own `location`/`countries` read as a
            // different country (US) entirely even though its `cities` array also
            // contains "Toronto". Not an idealized mock: this second shape is exactly
            // what was found live (a remote US-eligible posting whose `cities` lists
            // several remote-eligible cities including Toronto), company/title names
            // changed since they are not this project's data to keep verbatim, but
            // every field shape (`location` text, the `cities`/`countries` mismatch
            // pattern, `work_mode`) matches the real evidence exactly.
            if (lastPath.contains("cities=Toronto") && !lastPath.contains("cities=London")) {
                respond(ex, 200, """
                        {"data":[{"public_slug":"toronto-clean-e2e","source":"greenhouse","external_id":"gh-tor-clean",
                                  "url":"https://boards.greenhouse.io/acme/jobs/tor-clean","title":"Data Engineer",
                                  "company":"Acme Canada","company_slug":"acme-ca","location":"Toronto, Canada",
                                  "description":"<p>Toronto, Canada role.</p>","countries":["ca"],"regions":[],
                                  "work_mode":"hybrid","cities":["Toronto"],"skills":[],"is_tech":"tech",
                                  "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                                  "closed_at":null},
                                 {"public_slug":"toronto-multi-us-e2e","source":"greenhouse","external_id":"gh-tor-multi-us",
                                  "url":"https://boards.greenhouse.io/acme/jobs/tor-multi-us","title":"Brand & Marketing Manager",
                                  "company":"RemoteCo","company_slug":"remoteco","location":"Remote (US)",
                                  "description":"<p>Remote, US-based role.</p>","countries":["us"],"regions":[],
                                  "work_mode":"remote","cities":["New York City","Toronto"],"skills":[],
                                  "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                                  "created_at":"2026-08-30T00:00:00Z","closed_at":null}],
                         "meta":{"total":15036,"limit":20,"offset":0}}
                        """);
                return;
            }
            // "q=longslugtest": a synthetic 77-character public_slug (title + company +
            // random suffix, exactly how a real FreeHire slug is built) -- confirmed
            // live against real freehire.me on 2026-09-06 that slugs this long genuinely
            // occur ("magento-2-frontend-developer-hyva-experience-required-unotech-global-bptpehnr"
            // is that exact real slug). Reused here as a local, offline fixture -- no
            // new real FreeHire request -- to regression-test the fix without ever
            // depending on that specific real posting still existing.
            if (lastPath.contains("q=longslugtest")) {
                respond(ex, 200, """
                        {"data":[{"public_slug":"%s","source":"whatjobs-in","external_id":"wj-long",
                                  "url":"https://en-in.whatjobs.com/pub_api__cpl__12345","title":"Long Slug Test Posting",
                                  "company":"Unotech Global","company_slug":"unotech-global","location":"Surat, India",
                                  "description":"<p>Exercises the exact real slug-length edge case.</p>",
                                  "countries":["in"],"regions":[],"work_mode":null,"cities":[],"skills":[],
                                  "is_tech":"tech","posted_at":"2026-09-06T00:00:00Z",
                                  "created_at":"2026-09-06T00:00:00Z","closed_at":null}],
                         "meta":{"total":1,"limit":20,"offset":0}}
                        """.formatted(LONG_SLUG));
                return;
            }
            // "q=legacyslugtest": the search-side counterpart of LEGACY_SLUG below --
            // a short slug, exactly the shape a row saved BEFORE the persistId fix
            // would have stored directly as its own external_job_id.
            if (lastPath.contains("q=legacyslugtest")) {
                respond(ex, 200, """
                        {"data":[{"public_slug":"%s","source":"greenhouse","external_id":"gh-legacy",
                                  "url":"https://boards.greenhouse.io/acme/jobs/legacy","title":"Legacy Slug Posting",
                                  "company":"Acme","company_slug":"acme","location":"Remote - US",
                                  "description":"<p>Pre-existing, saved under the old raw-slug identity.</p>",
                                  "countries":["us"],"regions":[],"work_mode":"remote","cities":[],"skills":[],
                                  "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                                  "created_at":"2026-08-30T00:00:00Z","closed_at":null}],
                         "meta":{"total":1,"limit":20,"offset":0}}
                        """.formatted(LEGACY_SLUG));
                return;
            }
            respond(ex, 200, """
                    {"data":[{"public_slug":"acme-swe-e2e","source":"greenhouse","external_id":"gh-e2e",
                              "url":"https://boards.greenhouse.io/acme/jobs/e2e","title":"Backend Engineer",
                              "company":"Acme","company_slug":"acme","location":"Remote - US",
                              "description":"<p>Ship the backend.</p>","countries":["us"],"regions":[],
                              "work_mode":"remote","cities":[],"skills":[],"is_tech":"tech",
                              "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                              "closed_at":null}],
                     "meta":{"total":1,"limit":20,"offset":0}}
                    """);
        });
        fixture.createContext("/jobs/" + LEGACY_SLUG, ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 200, """
                    {"data":{"public_slug":"%s","source":"greenhouse","external_id":"gh-legacy",
                             "url":"https://boards.greenhouse.io/acme/jobs/legacy","title":"Legacy Slug Posting",
                             "company":"Acme","company_slug":"acme","location":"Remote - US",
                             "description":"<p>Pre-existing, saved under the old raw-slug identity. Full JD.</p>",
                             "countries":["us"],"regions":[],"work_mode":"remote","cities":[],"skills":[],
                             "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                             "created_at":"2026-08-30T00:00:00Z","closed_at":null}}
                    """.formatted(LEGACY_SLUG));
        });
        fixture.createContext("/jobs/" + LONG_SLUG, ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 200, """
                    {"data":{"public_slug":"%s","source":"whatjobs-in","external_id":"wj-long",
                             "url":"https://en-in.whatjobs.com/pub_api__cpl__12345","title":"Long Slug Test Posting",
                             "company":"Unotech Global","company_slug":"unotech-global","location":"Surat, India",
                             "description":"<p>Exercises the exact real slug-length edge case. Full JD.</p>",
                             "countries":["in"],"regions":[],"work_mode":null,"cities":[],"skills":[],
                             "is_tech":"tech","posted_at":"2026-09-06T00:00:00Z",
                             "created_at":"2026-09-06T00:00:00Z","closed_at":null}}
                    """.formatted(LONG_SLUG));
        });
        // Shares LONG_SLUG's first 64 characters exactly, differs only after --
        // see twoLongSlugsSharingA64CharacterPrefixNeverCollide.
        fixture.createContext("/jobs/" + LONG_SLUG_COLLISION_CANDIDATE, ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 200, """
                    {"data":{"public_slug":"%s","source":"whatjobs-in","external_id":"wj-long-2",
                             "url":"https://en-in.whatjobs.com/pub_api__cpl__67890","title":"A Different Posting",
                             "company":"Some Other Company","company_slug":"some-other-company","location":"Pune, India",
                             "description":"<p>A genuinely different posting that happens to share a 64-char prefix.</p>",
                             "countries":["in"],"regions":[],"work_mode":null,"cities":[],"skills":[],
                             "is_tech":"tech","posted_at":"2026-09-06T00:00:00Z",
                             "created_at":"2026-09-06T00:00:00Z","closed_at":null}}
                    """.formatted(LONG_SLUG_COLLISION_CANDIDATE));
        });
        // The exact same slug, uppercased -- see
        // caseDifferentLongSlugsAreTreatedAsDistinctIdentitiesNotMerged.
        fixture.createContext("/jobs/" + LONG_SLUG.toUpperCase(java.util.Locale.ROOT), ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 200, """
                    {"data":{"public_slug":"%s","source":"whatjobs-in","external_id":"wj-long-upper",
                             "url":"https://en-in.whatjobs.com/pub_api__cpl__12345","title":"Long Slug Test Posting (upper)",
                             "company":"Unotech Global","company_slug":"unotech-global","location":"Surat, India",
                             "description":"<p>Same real posting's slug, uppercased.</p>",
                             "countries":["in"],"regions":[],"work_mode":null,"cities":[],"skills":[],
                             "is_tech":"tech","posted_at":"2026-09-06T00:00:00Z",
                             "created_at":"2026-09-06T00:00:00Z","closed_at":null}}
                    """.formatted(LONG_SLUG.toUpperCase(java.util.Locale.ROOT)));
        });
        fixture.createContext("/jobs/acme-swe-e2e", ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 200, """
                    {"data":{"public_slug":"acme-swe-e2e","source":"greenhouse","external_id":"gh-e2e",
                             "url":"https://boards.greenhouse.io/acme/jobs/e2e","title":"Backend Engineer",
                             "company":"Acme","company_slug":"acme","location":"Remote - US",
                             "description":"<p>Ship the backend. Full JD paragraph two.</p>",
                             "countries":["us"],"regions":[],"work_mode":"remote","cities":[],"skills":[],
                             "is_tech":"tech","posted_at":"2026-09-01T00:00:00Z",
                             "created_at":"2026-08-30T00:00:00Z","closed_at":null}}
                    """);
        });
        // One fixture path per upstream failure mode this round adds coverage for, so
        // each error-mapping test below can pick its own slug and get a stable,
        // independent response rather than fighting over shared mutable state.
        fixture.createContext("/jobs/err-401", ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 401, "{\"error\":\"unauthorized -- internal freehire.me detail, must never reach the browser\"}");
        });
        fixture.createContext("/jobs/err-403", ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 403, "{\"error\":\"forbidden\"}");
        });
        fixture.createContext("/jobs/err-404", ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 404, "{\"error\":\"not found\"}");
        });
        fixture.createContext("/jobs/err-429", ex -> {
            REQUEST_COUNT.incrementAndGet();
            byte[] bytes = "{\"error\":\"slow down\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Retry-After", "77");
            ex.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        fixture.createContext("/jobs/err-503", ex -> {
            REQUEST_COUNT.incrementAndGet();
            respond(ex, 503, "{\"error\":\"unavailable\"}");
        });
        // A single context branching on the query string: proves both the happy path
        // and the degrade-gracefully-on-failure path through the SAME real controller
        // route, without needing a second fixture path.
        fixture.createContext("/geo/cities", ex -> {
            REQUEST_COUNT.incrementAndGet();
            if (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("q=fail")) {
                respond(ex, 503, "{\"error\":\"unavailable\"}");
            } else {
                respond(ex, 200, "{\"data\":[{\"value\":\"Toronto\",\"country\":\"ca\"}]}");
            }
        });
        fixture.start();
        int port = fixture.getAddress().getPort();
        registry.add("FREEHIRE_API_BASE_URL", () -> "http://127.0.0.1:" + port);
    }

    @AfterAll
    static void stopFixture() {
        if (fixture != null) {
            fixture.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobMapper jobMapper;

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "E2E User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    @Test
    void searchDetailAndSaveEndToEndThroughRealHttp() throws Exception {
        String email = "e2e@example.com";
        String auth = bearerFor(email);
        // REQUEST_COUNT is a single static counter shared by the whole fixture server,
        // across every @Test in this class (JUnit does not guarantee method order) --
        // so every assertion below is a DELTA from this test's own starting point, never
        // an absolute total.
        int requestsAtStart = REQUEST_COUNT.get();

        // 1. Search: real HTTP request, real controller, real service, real RestClient,
        // landing on the local fixture -- and the id it hands back must carry the
        // freehire: namespace, never a bare number.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("freehire:acme-swe-e2e"))
                .andExpect(jsonPath("$.results[0].title").value("Backend Engineer"))
                .andExpect(jsonPath("$.results[0].canonicalUrl").value("https://boards.greenhouse.io/acme/jobs/e2e"))
                .andExpect(jsonPath("$.results[0].applyUrl").doesNotExist())
                .andExpect(jsonPath("$.results[0].descriptionSnippet").doesNotExist())
                .andExpect(jsonPath("$.results[0].descriptionText").doesNotExist())
                .andExpect(jsonPath("$.results[0].saved").value(false));

        assertTrue(lastPath.contains("q=engineer"));

        // 2. Detail: full JD reaches the browser through our own API, not FreeHire's.
        mockMvc.perform(get("/api/v1/job-catalog/freehire:acme-swe-e2e").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("freehire:acme-swe-e2e"))
                .andExpect(jsonPath("$.descriptionText").value(
                        org.hamcrest.Matchers.containsString("Full JD paragraph two")))
                .andExpect(jsonPath("$.applyUrl").value("https://boards.greenhouse.io/acme/jobs/e2e"))
                .andExpect(jsonPath("$.saved").value(false));

        // A bare local-style numeric id must not resolve to a FreeHire row.
        mockMvc.perform(get("/api/v1/job-catalog/999").header("Authorization", auth))
                .andExpect(status().isNotFound());

        // 3. Save: the ONE posting the user chose, not the whole search page, becomes a
        // real personal jobs row.
        String saveJson = mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/save")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catalogJobId").value("freehire:acme-swe-e2e"))
                .andExpect(jsonPath("$.alreadySaved").value(false))
                .andExpect(jsonPath("$.job.source").value("FREEHIRE"))
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(saveJson, java.util.Map.class).get("jobId")).longValue();

        // This is the id Match/Tailor read -- a real row in `jobs`, not a synthetic value.
        aijob.entity.Job personal = jobMapper.findById(jobId);
        assertEquals("FREEHIRE", personal.getSource());
        // The PERSISTED id (a SHA-256 hex digest), never the raw slug -- see
        // FreeHireJobCatalogService#persistId's javadoc: a real FreeHire slug can
        // exceed jobs.external_job_id's VARCHAR(64), so this column never stores the
        // raw slug directly, exactly like JSearch's own long raw ids already didn't.
        assertEquals(ExternalJobIds.persistId("FREEHIRE", "acme-swe-e2e"), personal.getExternalJobId());
        assertEquals("Backend Engineer", personal.getTitle());
        assertTrue(personal.getDescription().contains("Ship the backend"));

        // 4. Repeat save is idempotent and does not create a second personal job, and
        // does not need a second FreeHire fetch (findByUserIdAndSourceAndExternalJobId
        // short-circuits it).
        int requestsBeforeRepeat = REQUEST_COUNT.get();
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/save").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySaved").value(true))
                .andExpect(jsonPath("$.jobId").value(jobId));
        assertEquals(requestsBeforeRepeat, REQUEST_COUNT.get(),
                "a repeat save must not call FreeHire again");

        // 5. Search again: the saved posting is now marked saved with the real personal id.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", auth))
                .andExpect(jsonPath("$.results[0].saved").value(true))
                .andExpect(jsonPath("$.results[0].personalJobId").value(jobId));

        // Never more than search + detail + save + (no-op repeat) hit FreeHire: no
        // per-user request multiplication, no background mirroring of the catalog.
        int requestsThisTest = REQUEST_COUNT.get() - requestsAtStart;
        assertTrue(requestsThisTest <= 4, "unexpectedly many upstream requests: " + requestsThisTest);
    }

    // ---------------------------------------------------------------------------
    // A real 77-character FreeHire slug (over jobs.external_job_id's VARCHAR(64)) --
    // confirmed live on 2026-09-06 that FreeHire genuinely serves slugs this long
    // (title + company + a random suffix). Reused here as an offline fixture value
    // (LONG_SLUG) -- these tests make ZERO real FreeHire requests.
    // ---------------------------------------------------------------------------

    @Test
    void aRealLengthLongSlugSearchesDetailsSavesAndSurvivesARefreshCorrectly() throws Exception {
        String auth = bearerFor("longslug@example.com");

        // 1. Search shows it, with the full raw slug in the catalog id -- never a hash.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "longslugtest").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("freehire:" + LONG_SLUG))
                .andExpect(jsonPath("$.results[0].saved").value(false));

        // 2. Save returns 201, using the FULL raw slug against FreeHire's own detail
        // endpoint -- this only succeeds at all because the fixture is registered at
        // "/jobs/" + LONG_SLUG (the raw slug); a hash there would 404.
        String saveJson = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catalogJobId").value("freehire:" + LONG_SLUG))
                .andExpect(jsonPath("$.alreadySaved").value(false))
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(saveJson, java.util.Map.class).get("jobId")).longValue();

        // 3/4/5. The database's external_job_id is the PERSISTED id -- exactly 64
        // characters, matches ExternalJobIds.persistId's contract, and is NOT the raw
        // slug and NOT a truncated prefix of it (a truncation would equal
        // LONG_SLUG.substring(0, 64), which this explicitly checks against too).
        aijob.entity.Job personal = jobMapper.findById(jobId);
        String persistedId = personal.getExternalJobId();
        assertEquals(64, persistedId.length());
        assertEquals(aijob.jobsearch.ExternalJobIds.persistId("FREEHIRE", LONG_SLUG), persistedId);
        assertNotEquals(LONG_SLUG, persistedId, "must never store the raw slug verbatim");
        assertNotEquals(LONG_SLUG.substring(0, 64), persistedId,
                "must never store a truncated prefix of the raw slug -- that would collide two different long slugs");
        assertEquals("Long Slug Test Posting", personal.getTitle());

        // 6. Repeat save is idempotent: alreadySaved=true, same jobId, still one row.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySaved").value(true))
                .andExpect(jsonPath("$.jobId").value(jobId));
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, jobId);
        assertEquals(1, rowCount);

        // 7. "Refresh" (a fresh search call, same as a real page reload would trigger):
        // the card shows saved=true with the real personal id.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "longslugtest").header("Authorization", auth))
                .andExpect(jsonPath("$.results[0].saved").value(true))
                .andExpect(jsonPath("$.results[0].personalJobId").value(jobId));

        // 8. Mark applied / revoke both work normally against this same long-slug row.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true));
        mockMvc.perform(delete("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(false));
    }

    @Test
    void twoLongSlugsSharingA64CharacterPrefixNeverCollide() throws Exception {
        // LONG_SLUG and LONG_SLUG_COLLISION_CANDIDATE are identical in their first 64
        // characters -- exactly what truncating (instead of hashing) to fit
        // VARCHAR(64) would have merged into the same identity. They must save as TWO
        // separate rows for the same user.
        String auth = bearerFor("longslugcollision@example.com");

        String firstJson = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(firstJson, java.util.Map.class).get("jobId")).longValue();

        String secondJson = mockMvc.perform(post("/api/v1/job-catalog/freehire:" + LONG_SLUG_COLLISION_CANDIDATE
                        + "/save").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadySaved").value(false))
                .andReturn().getResponse().getContentAsString();
        long secondId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(secondJson, java.util.Map.class).get("jobId")).longValue();

        assertNotEquals(firstId, secondId, "two different long slugs must never collide into the same personal job");
        aijob.entity.Job first = jobMapper.findById(firstId);
        aijob.entity.Job second = jobMapper.findById(secondId);
        assertNotEquals(first.getExternalJobId(), second.getExternalJobId());
        assertEquals("Long Slug Test Posting", first.getTitle());
        assertEquals("A Different Posting", second.getTitle());
    }

    @Test
    void caseDifferentLongSlugsAreTreatedAsDistinctIdentitiesNotMerged() throws Exception {
        // The existing exact-identity rule (see ExternalJobIds#persistId -- no case
        // normalization) applies here exactly as it always has: LONG_SLUG and its
        // uppercased form are two DIFFERENT raw slugs, so two different personal jobs,
        // never merged into one.
        String auth = bearerFor("longslugcase@example.com");
        String upper = LONG_SLUG.toUpperCase(java.util.Locale.ROOT);

        String lowerJson = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long lowerId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(lowerJson, java.util.Map.class).get("jobId")).longValue();

        String upperJson = mockMvc.perform(post("/api/v1/job-catalog/freehire:" + upper + "/save")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadySaved").value(false))
                .andReturn().getResponse().getContentAsString();
        long upperId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(upperJson, java.util.Map.class).get("jobId")).longValue();

        assertNotEquals(lowerId, upperId, "a case-different slug must never be treated as the same identity");
    }

    // ---------------------------------------------------------------------------
    // Rolling-upgrade compatibility: a row saved BEFORE the persistId fix stored the
    // raw slug directly. Simulated here with a real INSERT (not through the app), then
    // every read/write path is exercised against that pre-existing row through the
    // REAL controller/service/mapper -- proving the old row is recognized, never
    // duplicated, and never rewritten.
    // ---------------------------------------------------------------------------

    @Test
    void aPreExistingLegacyRawSlugRowIsRecognizedNeverDuplicatedAndStillWorksNormally() throws Exception {
        String email = "legacy-compat@example.com";
        String auth = bearerFor(email);
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        // Simulates data that already existed before this fix -- a real INSERT, not
        // through the app, with external_job_id = the RAW slug (unhashed). This is
        // exactly what a row saved by the old code looked like.
        jdbcTemplate.update("""
                INSERT INTO jobs (user_id, company, title, location, job_url, description, source, status,
                                   external_job_id, applied, applied_at)
                VALUES (?, 'Acme', 'Legacy Slug Posting', 'Remote - US', 'https://boards.greenhouse.io/acme/jobs/legacy',
                        'Pre-existing row.', 'FREEHIRE', 'SAVED', ?, 0, NULL)
                """, userId, LEGACY_SLUG);
        Long legacyJobId = jdbcTemplate.queryForObject(
                "SELECT id FROM jobs WHERE user_id = ? AND external_job_id = ?", Long.class, userId, LEGACY_SLUG);

        // 1. Search still shows it as saved, pointing at the real pre-existing row.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "legacyslugtest").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].saved").value(true))
                .andExpect(jsonPath("$.results[0].personalJobId").value(legacyJobId));

        // 2. Saving again finds the OLD row by its raw slug -- alreadySaved=true, same
        // id, and critically: still only ONE row for this slug, never a second
        // (hashed) row inserted alongside it.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:" + LEGACY_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySaved").value(true))
                .andExpect(jsonPath("$.jobId").value(legacyJobId));
        Integer rowCountForThisSlug = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ? AND external_job_id = ?",
                Integer.class, userId, LEGACY_SLUG);
        assertEquals(1, rowCountForThisSlug);

        // 3. Mark applied / revoke both operate on the SAME old row.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:" + LEGACY_SLUG + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(legacyJobId))
                .andExpect(jsonPath("$.job.applied").value(true));
        mockMvc.perform(delete("/api/v1/job-catalog/freehire:" + LEGACY_SLUG + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(false));

        // 4. This backend never rewrites the old row's identity -- still the raw slug,
        // untouched, exactly as point 2 (schema/data) of this round requires.
        String stillRaw = jdbcTemplate.queryForObject(
                "SELECT external_job_id FROM jobs WHERE id = ?", String.class, legacyJobId);
        assertEquals(LEGACY_SLUG, stillRaw);

        // 5. A DIFFERENT, NEW save (the long slug from the other tests in this class)
        // for this SAME user gets the new 64-character persisted id -- a fresh save
        // never reverts to the old, unhashed behavior just because an old row exists
        // elsewhere in this user's own jobs.
        String longSlugSaveJson = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:" + LONG_SLUG + "/save").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long longSlugJobId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(longSlugSaveJson, java.util.Map.class).get("jobId")).longValue();
        String longSlugPersistedId = jdbcTemplate.queryForObject(
                "SELECT external_job_id FROM jobs WHERE id = ?", String.class, longSlugJobId);
        assertEquals(64, longSlugPersistedId.length());
        assertEquals(aijob.jobsearch.ExternalJobIds.persistId("FREEHIRE", LONG_SLUG), longSlugPersistedId);

        Integer totalRowsForUser = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, totalRowsForUser, "the legacy row plus exactly one new row -- never merged, never duplicated");
    }

    // ---------------------------------------------------------------------------
    // Upstream error semantics through the REAL controller + GlobalExceptionHandler --
    // not just the FreeHireClient unit tests -- proving what actually reaches the
    // browser: the right HTTP status, no upstream body/URL leak, and (critically) that
    // a FreeHire 401/403 never comes back as OUR OWN 401/403, which is what the
    // frontend's axios interceptor treats as "log this user out".
    // ---------------------------------------------------------------------------

    @Test
    void upstream401NeverSurfacesAsOurOwn401() throws Exception {
        String auth = bearerFor("e2e-401@example.com");
        String body = mockMvc.perform(get("/api/v1/job-catalog/freehire:err-401").header("Authorization", auth))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("temporarily unavailable"));
        assertFalse(body.contains("internal freehire.me detail"),
                "FreeHire's raw response body must never reach the browser");
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("freehire.me"),
                "no internal upstream URL must leak to the client");
    }

    @Test
    void upstream403NeverSurfacesAsOurOwn403() throws Exception {
        String auth = bearerFor("e2e-403@example.com");
        mockMvc.perform(get("/api/v1/job-catalog/freehire:err-403").header("Authorization", auth))
                .andExpect(status().isBadGateway());
    }

    @Test
    void upstreamDetail404IsAJobNotFoundNotAServiceFailure() throws Exception {
        // The specific, matchable code (see JobDetailUnavailableException), never the
        // generic "Job not found" this site's OWN 404s use -- the frontend's
        // apiErrorKey routes on this exact string to show "该职位的详情已经失效..."
        // instead of a generic error, and must never confuse the two.
        String auth = bearerFor("e2e-404@example.com");
        mockMvc.perform(get("/api/v1/job-catalog/freehire:err-404").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("JOB_DETAIL_NO_LONGER_AVAILABLE"));
    }

    @Test
    void savingAPostingWhoseUpstreamDetailHas404dGetsTheSpecificCodeNotAGenericFailure() throws Exception {
        // The real scenario this guards: a posting still shows up in search results,
        // but by the time the user clicks "保存职位" its own detail lookup 404s (source
        // pulled it). Confirmed live against real freehire.me -- see the task report.
        // Must never look like "保存职位失败，请重试" (a generic, retry-suggesting
        // failure) -- retrying a save on a genuinely gone posting can never succeed.
        String auth = bearerFor("e2e-save-404@example.com");
        mockMvc.perform(post("/api/v1/job-catalog/freehire:err-404/save").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("JOB_DETAIL_NO_LONGER_AVAILABLE"));
    }

    @Test
    void confirmingAppliedOnAPostingWhoseUpstreamDetailHas404dGetsTheSpecificCodeToo() throws Exception {
        // markApplied's implicit save (see FreeHireJobCatalogService#markApplied) hits
        // the exact same getBySlug call -- same specific code, not a generic failure.
        String auth = bearerFor("e2e-applied-404@example.com");
        mockMvc.perform(post("/api/v1/job-catalog/freehire:err-404/applied").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("JOB_DETAIL_NO_LONGER_AVAILABLE"));
    }

    @Test
    void upstream429PropagatesRetryAfterAsOurOwn429() throws Exception {
        String auth = bearerFor("e2e-429@example.com");
        mockMvc.perform(get("/api/v1/job-catalog/freehire:err-429").header("Authorization", auth))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Retry-After", "77"));
    }

    @Test
    void upstream503IsASearchServiceUnavailableStatus() throws Exception {
        String auth = bearerFor("e2e-503@example.com");
        mockMvc.perform(get("/api/v1/job-catalog/freehire:err-503").header("Authorization", auth))
                .andExpect(status().isBadGateway());
    }

    @Test
    void searchAndDetailNowSucceedWithoutAJwtButSaveStillNeedsOne() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].saved").value(false))
                .andExpect(jsonPath("$.results[0].applied").value(false));

        mockMvc.perform(get("/api/v1/job-catalog/freehire:acme-swe-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.applied").value(false));

        int status = mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/save"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "save must still require a JWT: status=" + status);
    }

    // ---------------------------------------------------------------------------
    // city facet: the real fix for the free-text location box that always 400ed
    // ---------------------------------------------------------------------------

    @Test
    void searchByCityEndToEndNeverSendsCountryAlongsideIt() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/search").param("city", "Toronto"))
                .andExpect(status().isOk());
        assertTrue(lastPath.contains("cities=Toronto"), "expected cities=Toronto in: " + lastPath);
        assertFalse(lastPath.contains("countries="),
                "city and country share one OR-group upstream -- sending both would widen, not narrow: "
                        + lastPath);
    }

    @Test
    void cityFilterMatchesByNameOnlyAcrossCountries() throws Exception {
        // Proves the claim in FreeHireJobCatalogService.buildSearchParams' javadoc with a
        // real (local, no real freehire.me) fixture: two postings named "London", one in
        // GB and one in CA, both come back for a single cities=London request. Nothing
        // about the response narrows to one country -- there is no way, from this facet
        // alone, to ask for "London, specifically Canada".
        mockMvc.perform(get("/api/v1/job-catalog/search").param("city", "London"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].countryCode").value("GB"))
                .andExpect(jsonPath("$.results[1].countryCode").value("CA"));
        assertTrue(lastPath.contains("cities=London"), "expected cities=London in: " + lastPath);
        assertFalse(lastPath.contains("countries="), "cities alone must never gain a countries param: " + lastPath);
    }

    @Test
    void cityMatchHonestlyLabelsAGenuineMultiLocationMismatchInsteadOfHidingIt() throws Exception {
        // Real bug, reproduced with a fixture shaped after the real evidence (see the
        // "cities=Toronto" fixture branch above): a Toronto search can legitimately
        // return a posting whose OWN location/countries are a different country (US)
        // entirely, because that posting's cities array lists Toronto among several
        // remote-eligible cities. cityMatch must surface this honestly rather than
        // letting the US location alone look like the filter silently stopped working.
        mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("city", "Toronto")
                        .param("cityCountry", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                // Clean match: cities=["Toronto"], countries=["ca"] -- confirmed.
                .andExpect(jsonPath("$.results[0].cityMatch.city").value("Toronto"))
                .andExpect(jsonPath("$.results[0].cityMatch.countryCode").value("CA"))
                .andExpect(jsonPath("$.results[0].cityMatch.countryConfirmed").value(true))
                .andExpect(jsonPath("$.results[0].locationText").value("Toronto, Canada"))
                // Genuine mismatch: cities=["New York City","Toronto"], countries=["us"]
                // -- cityMatch must still be present (cities DOES contain "Toronto") but
                // NOT claim the country is confirmed, and locationText must be preserved
                // verbatim, never hidden or overwritten.
                .andExpect(jsonPath("$.results[1].cityMatch.city").value("Toronto"))
                .andExpect(jsonPath("$.results[1].cityMatch.countryCode").value("CA"))
                .andExpect(jsonPath("$.results[1].cityMatch.countryConfirmed").value(false))
                .andExpect(jsonPath("$.results[1].locationText").value("Remote (US)"))
                .andExpect(jsonPath("$.results[1].countryCode").value("US"));
        assertTrue(lastPath.contains("cities=Toronto"), "expected cities=Toronto in: " + lastPath);
        assertFalse(lastPath.contains("countries="), "cityCountry must never reach FreeHire as countries: " + lastPath);
        assertFalse(lastPath.contains("cityCountry"), "cityCountry is site-local metadata, never a FreeHire param: "
                + lastPath);
    }

    @Test
    void cityAndCountryTogetherIsRejectedBeforeAnyFreeHireCall() throws Exception {
        int before = REQUEST_COUNT.get();
        mockMvc.perform(get("/api/v1/job-catalog/search").param("city", "Toronto").param("country", "US"))
                .andExpect(status().isBadRequest());
        assertEquals(before, REQUEST_COUNT.get(), "a rejected combination must never reach FreeHire");
    }

    @Test
    void citySuggestionsEndToEndReturnRealFreeHireValues() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/cities").param("q", "Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("Toronto"))
                .andExpect(jsonPath("$[0].countryCode").value("ca"));
    }

    @Test
    void citySuggestionsDegradeGracefullyOnUpstreamFailureWithoutBreakingSearch() throws Exception {
        // The city lookup failing must be its own, separately-visible failure -- and
        // must never affect the keyword+country search, which is a completely separate
        // request/response cycle.
        mockMvc.perform(get("/api/v1/job-catalog/cities").param("q", "fail-this-lookup"))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").param("country", "us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("Backend Engineer"));
    }

    @Test
    void confirmingAppliedWithoutAJwtIsRejectedBeforeAnyFreeHireCallHappens() throws Exception {
        int before = REQUEST_COUNT.get();
        int status = mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status=" + status);
        assertEquals(before, REQUEST_COUNT.get(), "an unauthenticated request must never reach FreeHire");
    }

    @Test
    void confirmAppliedThenRevokeEndToEnd() throws Exception {
        String auth = bearerFor("applied-e2e@example.com");

        // Confirming applied on a posting never saved before implicitly saves it first --
        // the same real personal jobs row Match/Tailor read from.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true))
                .andExpect(jsonPath("$.job.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.job.source").value("FREEHIRE"));

        // The search result for the same posting now reflects it, for this same user.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", auth))
                .andExpect(jsonPath("$.results[0].applied").value(true));

        // Confirming again is idempotent -- no error, no duplicate row, no extra
        // FreeHire fetch (the posting is already saved).
        int requestsBeforeRepeat = REQUEST_COUNT.get();
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true));
        assertEquals(requestsBeforeRepeat, REQUEST_COUNT.get(), "a repeat confirmation must not call FreeHire again");

        // Revoking a mistaken confirmation clears it.
        mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(false))
                .andExpect(jsonPath("$.job.appliedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", auth))
                .andExpect(jsonPath("$.results[0].applied").value(false));
    }

    @Test
    void confirmAppliedFromTheSearchCardNeverResetsAppliedAtOnRepeatAndGetsAFreshOneAfterARevoke() throws Exception {
        // Same idempotency guarantee JobControllerAppliedSecurityTest proves for
        // /api/v1/jobs/{id}/applied -- checked here for the OTHER entry point
        // (/api/v1/job-catalog/{catalogJobId}/applied, the search-card flow), against
        // REAL H2 persistence (not a mock), because both now share the exact same
        // JobMapper#markApplied UPDATE (its own COALESCE guarantees this, not a
        // check-then-write in either service).
        String auth = bearerFor("applied-repeat-e2e@example.com");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        String firstBody = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) mapper.readTree(firstBody).get("jobId").numberValue()).longValue();
        String firstAppliedAt = mapper.readTree(firstBody).get("job").get("appliedAt").asText();

        Thread.sleep(5);

        String secondBody = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondAppliedAt = mapper.readTree(secondBody).get("job").get("appliedAt").asText();

        assertEquals(firstAppliedAt, secondAppliedAt, "a repeat confirmation must never reset appliedAt");
        Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, jobId);
        assertEquals(1, rowCount, "a repeat confirmation must never create a second row");

        mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.appliedAt").doesNotExist());

        Thread.sleep(5);

        String thirdBody = mockMvc.perform(
                        post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String thirdAppliedAt = mapper.readTree(thirdBody).get("job").get("appliedAt").asText();

        assertNotEquals(firstAppliedAt, thirdAppliedAt,
                "re-confirming after a revoke must get a genuinely new appliedAt");
    }

    @Test
    void revokingAppliedOnANeverSavedPostingIsNotFound() throws Exception {
        String auth = bearerFor("applied-revoke-e2e@example.com");
        mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoDifferentUsersAppliedStatusIsIsolated() throws Exception {
        String aliceAuth = bearerFor("applied-alice@example.com");
        String bobAuth = bearerFor("applied-bob@example.com");

        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true));

        // Bob never confirmed anything -- his own view of the same posting must not show
        // Alice's applied state, and his own confirmation must not touch her row.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", bobAuth))
                .andExpect(jsonPath("$.results[0].applied").value(false));

        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", bobAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true));

        mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-e2e/applied").header("Authorization", bobAuth))
                .andExpect(status().isOk());

        // Bob revoking his own confirmation must never touch Alice's.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", aliceAuth))
                .andExpect(jsonPath("$.results[0].applied").value(true));
    }
}
