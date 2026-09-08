package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Reproduces a real user-reported 500 on {@code /api/v1/job-catalog/search} against the
 * REAL stack: real Spring context, real {@code JobCatalogController} ->
 * {@code FreeHireJobCatalogService} -> {@code JobMapper}, and a REAL MySQL 8.4 (the same
 * isolated {@code aijob_import_test} the sibling MySQL suites use -- never
 * {@code aijob_catalog_pilot}, never port 3306 directly, never H2). Only the freehire.me
 * network hop itself is replaced, with a local loopback {@code HttpServer} fixture --
 * everything on this side of that hop is the genuine production code path.
 *
 * <p>Exists because a prior round's manual acceptance testing hit a real HTTP 500 that
 * the H2-backed suite never caught (H2 in MySQL mode does not exercise everything a real
 * MySQL 8.4 connection does -- see {@link JobCatalogAppliedMySqlIntegrationTest}'s own
 * javadoc for the same point about the {@code applied}/{@code applied_at} columns). Each
 * test below asserts the response is never a 500 and, if one occurs, fails with the real
 * resolved exception's full stack trace attached -- {@code GlobalExceptionHandler} must
 * never be the reason a real cause becomes undiagnosable.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${AIJOB_MYSQL_TEST_URL}",
                "spring.datasource.username=${AIJOB_MYSQL_TEST_USERNAME}",
                "spring.datasource.password=${AIJOB_MYSQL_TEST_PASSWORD}",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
                // No aijob.job-catalog.provider: the real default (freehire), same as
                // production, pointed at a local loopback fixture below.
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "set AIJOB_MYSQL_TEST_URL/USERNAME/PASSWORD (see infra/mysql-test) "
                + "to run against an isolated MySQL 8.4")
class JobCatalogSearchMySqlRealStackTest {

    private static final String URL_VARIABLE = "AIJOB_MYSQL_TEST_URL";
    private static final String REQUIRED_SERVER_VERSION_PREFIX = "8.4";
    private static final String REQUIRED_SCHEMA = "aijob_import_test";
    private static final Set<String> OWNED_TABLES = Set.of(
            "users", "resumes", "jobs", "tailor_proposals", "resume_evidence_items",
            "job_requirement_profiles", "schema_migrations", "job_sources", "job_catalog",
            "job_source_records", "job_ingest_runs", "user_saved_catalog_jobs", "site_feedback",
            "resume_builder_drafts", "resume_builder_generation_requests");

    private static HttpServer fixture;
    private static String lastPath;

    @DynamicPropertySource
    static void pointAtTheLocalFixture(DynamicPropertyRegistry registry) throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/agent/jobs/search", ex -> {
            lastPath = ex.getRequestURI().toString();
            respond(ex, 200, """
                    {"data":[{"public_slug":"acme-swe-realstack","source":"greenhouse","external_id":"gh-realstack",
                              "url":"https://boards.greenhouse.io/acme/jobs/realstack","title":"Backend Engineer",
                              "company":"Acme","company_slug":"acme","location":"Toronto, Canada",
                              "description":"<p>Ship the backend.</p>","countries":["ca"],"regions":[],
                              "work_mode":"onsite","cities":["Toronto"],"skills":[],"is_tech":"tech",
                              "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                              "closed_at":null}],
                     "meta":{"total":1,"limit":20,"offset":0}}
                    """);
        });
        fixture.start();
        int port = fixture.getAddress().getPort();
        registry.add("FREEHIRE_API_BASE_URL", () -> "http://127.0.0.1:" + port);
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
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv(URL_VARIABLE);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "set " + URL_VARIABLE + " to an ISOLATED MySQL 8.4 test database to run this");
        assertTrue(url.startsWith("jdbc:mysql:"), "not a MySQL connection string");

        verifyServerIsSafeToUse();
        applyProductionSchema();
        truncateAll();
    }

    /** Same four checks as the sibling MySQL suites -- see their javadoc for why each one matters. */
    private void verifyServerIsSafeToUse() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith(REQUIRED_SERVER_VERSION_PREFIX),
                "expected MySQL " + REQUIRED_SERVER_VERSION_PREFIX + ", server reports " + version);

        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals(REQUIRED_SCHEMA, schema,
                "refusing to run: connected to schema '" + schema + "', expected exactly '"
                        + REQUIRED_SCHEMA + "'");

        List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
        boolean onTestSchema = grants.stream().anyMatch(g -> g.contains("`" + REQUIRED_SCHEMA + "`.*"));
        assertTrue(onTestSchema, "the account has no grant on `" + REQUIRED_SCHEMA + "`.*; its grants are " + grants);
        List<String> global = grants.stream()
                .filter(g -> g.contains(" ON *.*"))
                .filter(g -> !g.startsWith("GRANT USAGE ON *.*"))
                .toList();
        assertTrue(global.isEmpty(),
                "refusing to run: the account holds server-wide privileges " + global);

        List<String> unexpected = jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.TABLES WHERE table_schema = DATABASE()",
                        String.class)
                .stream()
                .filter(t -> !OWNED_TABLES.contains(t.toLowerCase(Locale.ROOT)))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "refusing to run: '" + schema + "' holds tables this project does not own " + unexpected);
    }

    /** Built from the same files infra/aws/release.sh applies, in the same order. */
    private void applyProductionSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("infra/aws/mysql-init/01-schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/job_catalog_phase1.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/job_catalog_external_id_exact_identity.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/job_catalog_external_id_no_pad.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/jobs_applied_tracking.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/job_catalog_phase1_verify.sql"));
        }
    }

    private void truncateAll() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : List.of("user_saved_catalog_jobs", "job_ingest_runs", "job_source_records",
                "job_catalog", "job_sources", "jobs", "users")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "MySQL Real Stack Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    /** Confirms this JVM's only live JDBC connection is aijob_import_test, never 3306/pilot directly. */
    private void assertNotConnectedToPilotSchema() {
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals(REQUIRED_SCHEMA, schema, "must never be connected to anything but " + REQUIRED_SCHEMA);
        assertTrue(!schema.equals("aijob_catalog_pilot"), "must never be connected to the pilot schema");
    }

    private void assertNeverA500(MvcResult result, String label) {
        int status = result.getResponse().getStatus();
        if (status == 500) {
            Throwable resolved = result.getResolvedException();
            StringWriter sw = new StringWriter();
            if (resolved != null) {
                resolved.printStackTrace(new PrintWriter(sw));
            }
            String body;
            try {
                body = result.getResponse().getContentAsString();
            } catch (Exception e) {
                body = "<unreadable: " + e + ">";
            }
            fail(label + " returned HTTP 500.\n"
                    + "  response body: " + body + "\n"
                    + "  resolved exception:\n" + sw);
        }
    }

    // ---------------------------------------------------------------------------
    // The five scenarios from manual acceptance testing.
    // ---------------------------------------------------------------------------

    @Test
    void anonymousInitialPageLoadBlankKeywordPageAndSize() throws Exception {
        // The exact first request JobCatalogSearch.tsx fires on mount (see its
        // `useEffect(() => { void runSearch(1) }, [])`): blank q, no city/country, but
        // page and size ARE explicitly sent (page=1, size=PAGE_SIZE=10) -- unlike the
        // other scenarios below, which all pass q non-blank.
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("page", "1")
                        .param("size", "10"))
                .andReturn();
        assertNeverA500(result, "anonymous initial page load (blank q, page=1, size=10)");
        System.out.println("[anonymous initial page load] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void anonymousKeywordOnly() throws Exception {
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer"))
                .andReturn();
        assertNeverA500(result, "anonymous+keyword");
        System.out.println("[anonymous+keyword] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void authenticatedKeywordOnly() throws Exception {
        assertNotConnectedToPilotSchema();
        String auth = bearerFor("realstack-auth-keyword@example.com");
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .header("Authorization", auth))
                .andReturn();
        assertNeverA500(result, "authenticated+keyword");
        System.out.println("[authenticated+keyword] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void anonymousKeywordAndCountry() throws Exception {
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .param("country", "CA"))
                .andReturn();
        assertNeverA500(result, "anonymous+keyword+country=CA");
        System.out.println("[anonymous+keyword+country=CA] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void anonymousKeywordAndCity() throws Exception {
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .param("city", "Toronto"))
                .andReturn();
        assertNeverA500(result, "anonymous+keyword+city=Toronto");
        System.out.println("[anonymous+keyword+city=Toronto] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void anonymousKeywordAndCityWithASpace() throws Exception {
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .param("city", "New York"))
                .andReturn();
        assertNeverA500(result, "anonymous+keyword+city='New York' (space)");
        System.out.println("[anonymous+keyword+city='New York'] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void anonymousKeywordAndCityWithNonAscii() throws Exception {
        assertNotConnectedToPilotSchema();
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .param("city", "Montréal"))
                .andReturn();
        assertNeverA500(result, "anonymous+keyword+city='Montréal' (non-ASCII)");
        System.out.println("[anonymous+keyword+city='Montréal'] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }

    @Test
    void authenticatedKeywordAndCity() throws Exception {
        assertNotConnectedToPilotSchema();
        String auth = bearerFor("realstack-auth-city@example.com");
        MvcResult result = mockMvc.perform(get("/api/v1/job-catalog/search")
                        .param("q", "engineer")
                        .param("city", "Toronto")
                        .header("Authorization", auth))
                .andReturn();
        assertNeverA500(result, "authenticated+keyword+city=Toronto");
        System.out.println("[authenticated+keyword+city=Toronto] status=" + result.getResponse().getStatus()
                + " upstream_query=" + lastPath);
    }
}
