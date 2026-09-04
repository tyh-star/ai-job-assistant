package aijob.controller;

import aijob.AiJobApplication;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @DynamicPropertySource
    static void pointAtTheLocalFixture(DynamicPropertyRegistry registry) throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/agent/jobs/search", ex -> {
            REQUEST_COUNT.incrementAndGet();
            lastPath = ex.getRequestURI().toString();
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
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER"));
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
                .andExpect(jsonPath("$.results[0].applyUrl").value("https://boards.greenhouse.io/acme/jobs/e2e"))
                .andExpect(jsonPath("$.results[0].saved").value(false));

        assertTrue(lastPath.contains("q=engineer"));

        // 2. Detail: full JD reaches the browser through our own API, not FreeHire's.
        mockMvc.perform(get("/api/v1/job-catalog/freehire:acme-swe-e2e").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("freehire:acme-swe-e2e"))
                .andExpect(jsonPath("$.descriptionText").value(
                        org.hamcrest.Matchers.containsString("Full JD paragraph two")))
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
        assertEquals("acme-swe-e2e", personal.getExternalJobId());
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
        String auth = bearerFor("e2e-404@example.com");
        mockMvc.perform(get("/api/v1/job-catalog/freehire:err-404").header("Authorization", auth))
                .andExpect(status().isNotFound());
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
    void noJwtIsRejectedBeforeAnyFreeHireCallHappens() throws Exception {
        int before = REQUEST_COUNT.get();
        int status = mockMvc.perform(get("/api/v1/job-catalog/search"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status=" + status);
        assertEquals(before, REQUEST_COUNT.get(), "an unauthenticated request must never reach FreeHire");
    }
}
