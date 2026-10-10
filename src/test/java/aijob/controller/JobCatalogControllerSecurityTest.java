package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog's public/protected split: {@code GET search} and {@code GET
 * /{catalogJobId}} need no account (public job browsing), but everything that writes
 * user state -- {@code save}, {@code applied} (confirm and revoke) -- still needs a
 * JWT, same as the rest of {@code /api/v1} outside {@code /api/v1/auth/**}.
 *
 * <p>A local loopback fixture stands in for freehire.me (the default provider) so the
 * public-route assertions are real 200s with real bodies, not just "not a 401" --
 * without this test ever reaching the real network.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobcatalogsecurity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
                // No aijob.job-catalog.provider: the real default (freehire).
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobCatalogControllerSecurityTest {

    private static HttpServer fixture;

    @DynamicPropertySource
    static void pointAtTheLocalFixture(DynamicPropertyRegistry registry) throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/agent/jobs/search", ex -> respond(ex, 200, """
                {"data":[{"public_slug":"acme-swe-sec","source":"greenhouse","external_id":"gh-sec",
                          "url":"https://boards.greenhouse.io/acme/jobs/sec","title":"Backend Engineer",
                          "company":"Acme","company_slug":"acme","location":"Remote - US",
                          "description":"<p>Ship the backend.</p>","countries":["us"],"regions":[],
                          "work_mode":"remote","cities":[],"skills":[],"is_tech":"tech",
                          "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                          "closed_at":null}],
                 "meta":{"total":1,"limit":20,"offset":0}}
                """));
        fixture.createContext("/jobs/acme-swe-sec", ex -> respond(ex, 200, """
                {"data":{"public_slug":"acme-swe-sec","source":"greenhouse","external_id":"gh-sec",
                         "url":"https://boards.greenhouse.io/acme/jobs/sec","title":"Backend Engineer",
                         "company":"Acme","company_slug":"acme","location":"Remote - US",
                         "description":"<p>Ship the backend.</p>","countries":["us"],"regions":[],
                         "work_mode":"remote","cities":[],"skills":[],"is_tech":"tech",
                         "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                         "closed_at":null}}
                """));
        fixture.createContext("/geo/cities", ex -> respond(ex, 200, """
                {"data":[{"value":"Toronto","country":"ca"}]}
                """));
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

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "Security Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    @Test
    void searchWithoutAJwtSucceedsAndComesBackAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("freehire:acme-swe-sec"))
                .andExpect(jsonPath("$.results[0].saved").value(false))
                .andExpect(jsonPath("$.results[0].applied").value(false))
                .andExpect(jsonPath("$.results[0].personalJobId").doesNotExist());
    }

    @Test
    void detailWithoutAJwtSucceedsAndComesBackAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/freehire:acme-swe-sec"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.applied").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/job-catalog/search",
            "/api/v1/job-catalog/search?q=java&country=CA",
            "/api/v1/job-catalog/freehire:acme-swe-sec",
            "/api/v1/job-catalog/cities?q=Toronto",
    })
    void publicCatalogRoutesAreNeverRejectedForMissingAuth(String path) throws Exception {
        int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
        assertNotEquals(401, status, "status=" + status);
        assertNotEquals(403, status, "status=" + status);
    }

    @Test
    void citiesWithoutAJwtSucceedsAndReturnsRealSuggestions() throws Exception {
        mockMvc.perform(get("/api/v1/job-catalog/cities").param("q", "Toronto").param("country", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("Toronto"))
                .andExpect(jsonPath("$[0].countryCode").value("ca"));
    }

    @Test
    void citiesRouteIsNeverCapturedAsADetailLookupForAPostingLiterallyNamedCities() throws Exception {
        // The regression this guards: /api/v1/job-catalog/{catalogJobId} and
        // /api/v1/job-catalog/cities are both single-segment paths under /job-catalog/.
        // Spring must resolve the literal "cities" route, never treat it as
        // catalogJobId="cities" and try (and fail) to look up a posting by that id.
        String body = mockMvc.perform(get("/api/v1/job-catalog/cities").param("q", "Toronto"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.startsWith("["), "expected a JSON array of suggestions, got: " + body);
    }

    @Test
    void savingFromTheCatalogWithoutAJwtIsRejected() throws Exception {
        assertRejected(mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-sec/save"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void confirmingAppliedWithoutAJwtIsRejected() throws Exception {
        assertRejected(mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-sec/applied"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void revokingAppliedWithoutAJwtIsRejected() throws Exception {
        assertRejected(mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-sec/applied"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void anInvalidBearerTokenIsRejectedOnAProtectedRoute() throws Exception {
        assertRejected(mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-sec/save")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void anInvalidBearerTokenOnAPublicRouteIsSimplyIgnoredNotRejected() throws Exception {
        // A garbage token must not turn a public route into a 401 -- it is simply not a
        // valid credential, so the request proceeds exactly as an anonymous one would.
        int status = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andReturn().getResponse().getStatus();
        assertEquals(200, status);
    }

    @Test
    void savingStillWorksNormallyWithARealJwt() throws Exception {
        String auth = bearerFor("catalog-security@example.com");
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-sec/save").header("Authorization", auth))
                .andExpect(status().isCreated());
    }

    private static void assertRejected(int status) {
        assertNotEquals(200, status);
        assertEquals(401, status, "missing or unusable credentials must be 401");
    }
}
