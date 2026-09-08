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

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "applied" confirm/revoke feature against a REAL MySQL 8.4, on the same isolated
 * database {@link aijob.collector.jsonl.JobExportImportMySqlIntegrationTest} uses --
 * schema built from the same files a real release applies (including {@code
 * schema/jobs_applied_tracking.sql}, chained into {@code infra/aws/release.sh}), not a
 * hand-written approximation and not H2. H2 in MySQL mode does not exercise the actual
 * {@code TINYINT(1)}/{@code TIMESTAMP} storage this migration adds, so this is the only
 * place that claim is actually checked.
 *
 * <p>Same safety gate as the sibling MySQL suite: server really is 8.4, connected schema
 * really is {@code aijob_import_test}, the account's grants reach nothing else, and the
 * schema holds no table this project does not own -- before a single write.
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
class JobCatalogAppliedMySqlIntegrationTest {

    private static final String URL_VARIABLE = "AIJOB_MYSQL_TEST_URL";
    private static final String REQUIRED_SERVER_VERSION_PREFIX = "8.4";
    private static final String REQUIRED_SCHEMA = "aijob_import_test";
    private static final Set<String> OWNED_TABLES = Set.of(
            "users", "resumes", "jobs", "tailor_proposals", "resume_evidence_items",
            "job_requirement_profiles", "schema_migrations", "job_sources", "job_catalog",
            "job_source_records", "job_ingest_runs", "user_saved_catalog_jobs", "site_feedback",
            "resume_builder_drafts", "resume_builder_generation_requests", "job_search_history");

    private static HttpServer fixture;

    @DynamicPropertySource
    static void pointAtTheLocalFixture(DynamicPropertyRegistry registry) throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/agent/jobs/search", ex -> respond(ex, 200, """
                {"data":[{"public_slug":"acme-swe-mysql","source":"greenhouse","external_id":"gh-mysql",
                          "url":"https://boards.greenhouse.io/acme/jobs/mysql","title":"Backend Engineer",
                          "company":"Acme","company_slug":"acme","location":"Remote - US",
                          "description":"<p>Ship the backend.</p>","countries":["us"],"regions":[],
                          "work_mode":"remote","cities":[],"skills":[],"is_tech":"tech",
                          "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                          "closed_at":null}],
                 "meta":{"total":1,"limit":20,"offset":0}}
                """));
        fixture.createContext("/jobs/acme-swe-mysql", ex -> respond(ex, 200, """
                {"data":{"public_slug":"acme-swe-mysql","source":"greenhouse","external_id":"gh-mysql",
                         "url":"https://boards.greenhouse.io/acme/jobs/mysql","title":"Backend Engineer",
                         "company":"Acme","company_slug":"acme","location":"Remote - US",
                         "description":"<p>Ship the backend.</p>","countries":["us"],"regions":[],
                         "work_mode":"remote","cities":[],"skills":[],"is_tech":"tech",
                         "posted_at":"2026-09-01T00:00:00Z","created_at":"2026-08-30T00:00:00Z",
                         "closed_at":null}}
                """));
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

    /** Same four checks as the sibling MySQL suite -- see its javadoc for why each one matters. */
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
            // The migration under test in this file. Running it here against a database
            // whose `jobs` table already exists (from a prior run, or from 01-schema.sql
            // already carrying the columns) is itself the "apply on top of an existing
            // table" / re-run case this migration's own gate has to survive.
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/jobs_applied_tracking.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/users_auth_version.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/site_feedback.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/resume_builder.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("schema/job_search_history.sql"));
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
                email, "not-a-real-hash", "MySQL Applied Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    // ---------------------------------------------------------------------------

    @Test
    void migratedColumnsHaveTheExactTypeNullabilityAndDefaultMySqlItselfReports() {
        var row = jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable, column_default
                  FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied'
                """);
        assertEquals("tinyint", row.get("data_type"));
        assertEquals("NO", row.get("is_nullable"));
        assertEquals("0", String.valueOf(row.get("column_default")));

        var rowAt = jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable
                  FROM information_schema.COLUMNS
                 WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied_at'
                """);
        assertEquals("timestamp", rowAt.get("data_type"));
        assertEquals("YES", rowAt.get("is_nullable"));
    }

    @Test
    void confirmRepeatRevokeConfirmAgainAllPersistInRealMySql() throws Exception {
        String auth = bearerFor("mysql-applied@example.com");

        String firstJson = mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-mysql/applied")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(firstJson, java.util.Map.class).get("jobId")).longValue();

        // Re-query with a FRESH statement (not anything cached by the request above) --
        // proving the confirmation actually committed to MySQL, not just to an in-memory
        // response object.
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT applied FROM jobs WHERE id = ?", Boolean.class, jobId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT applied_at FROM jobs WHERE id = ?", java.sql.Timestamp.class, jobId));

        // Repeat confirmation: idempotent, still exactly one row for this user+posting.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-mysql/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId));
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = (SELECT id FROM users WHERE email = ?)",
                Integer.class, "mysql-applied@example.com");
        assertEquals(1, rowCount, "a repeat confirmation must never create a second row");

        // Revoke: cleared in real MySQL, re-queryable as false/null.
        mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-mysql/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(false));
        assertFalse(jdbcTemplate.queryForObject("SELECT applied FROM jobs WHERE id = ?", Boolean.class, jobId));

        // Confirm again after revoking: must be settable a second time, not stuck cleared.
        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-mysql/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.applied").value(true));
        assertTrue(jdbcTemplate.queryForObject("SELECT applied FROM jobs WHERE id = ?", Boolean.class, jobId));
    }

    @Test
    void twoUsersAppliedRowsAreIsolatedInRealMySql() throws Exception {
        String aliceAuth = bearerFor("mysql-alice@example.com");
        String bobAuth = bearerFor("mysql-bob@example.com");

        mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-mysql/applied").header("Authorization", aliceAuth))
                .andExpect(status().isOk());

        // Bob's own search must not show Alice's applied state for the same posting.
        mockMvc.perform(get("/api/v1/job-catalog/search").param("q", "engineer").header("Authorization", bobAuth))
                .andExpect(jsonPath("$.results[0].applied").value(false));

        Long aliceUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "mysql-alice@example.com");
        Long bobUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "mysql-bob@example.com");
        Integer aliceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ? AND applied = TRUE", Integer.class, aliceUserId);
        Integer bobRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ? AND applied = TRUE", Integer.class, bobUserId);
        assertEquals(1, aliceRows);
        assertEquals(0, bobRows);
    }

    @Test
    void anonymousConfirmAndRevokeAreRejectedBeforeTouchingMySql() throws Exception {
        int status1 = mockMvc.perform(post("/api/v1/job-catalog/freehire:acme-swe-mysql/applied"))
                .andReturn().getResponse().getStatus();
        assertTrue(status1 == 401 || status1 == 403, "status=" + status1);

        int status2 = mockMvc.perform(delete("/api/v1/job-catalog/freehire:acme-swe-mysql/applied"))
                .andReturn().getResponse().getStatus();
        assertTrue(status2 == 401 || status2 == 403, "status=" + status2);

        Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class);
        assertEquals(0, rowCount, "an anonymous request must never write a row");
    }
}
