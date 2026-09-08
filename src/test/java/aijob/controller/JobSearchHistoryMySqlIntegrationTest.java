package aijob.controller;

import aijob.AiJobApplication;
import aijob.dto.RecordJobSearchHistoryRequest;
import aijob.security.JwtUtil;
import aijob.service.JobSearchHistoryService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Job search history against real MySQL 8.4: unique-key upsert under concurrency, and
 * the migration gate refusing an incomplete table that {@code CREATE TABLE IF NOT EXISTS}
 * would otherwise silently accept.
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
                "aijob.storage.root=target/aijob-search-history-mysql-test-uploads",
                "spring.datasource.hikari.data-source-properties.connectionTimeZone=UTC",
                "spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession=true"
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "set AIJOB_MYSQL_TEST_URL/USERNAME/PASSWORD (see infra/mysql-test) "
                + "to run against an isolated MySQL 8.4")
class JobSearchHistoryMySqlIntegrationTest {

    private static final String URL_VARIABLE = "AIJOB_MYSQL_TEST_URL";
    private static final String REQUIRED_SERVER_VERSION_PREFIX = "8.4";
    private static final String REQUIRED_SCHEMA = "aijob_import_test";
    private static final Set<String> OWNED_TABLES = Set.of(
            "users", "resumes", "jobs", "tailor_proposals", "resume_evidence_items",
            "job_requirement_profiles", "schema_migrations", "job_sources", "job_catalog",
            "job_source_records", "job_ingest_runs", "user_saved_catalog_jobs", "site_feedback",
            "resume_builder_drafts", "resume_builder_generation_requests", "job_search_history");

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JobSearchHistoryService jobSearchHistoryService;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv(URL_VARIABLE);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "set " + URL_VARIABLE + " to an ISOLATED MySQL 8.4 test database to run this");
        assertTrue(url.startsWith("jdbc:mysql:"), "not a MySQL connection string");
        verifyServerIsSafeToUse();
        applyProductionSchema();
    }

    @Test
    void concurrentDuplicateSearchesDoNotCreateTwoRows() throws Exception {
        String email = "history-concurrent-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123!"), "Concurrent History User");
        RecordJobSearchHistoryRequest request = new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, "REMOTE", null, null, null);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await(15, TimeUnit.SECONDS);
                    jobSearchHistoryService.record(email, request);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(0, failures.get());

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_search_history WHERE user_id = ?", Integer.class, userId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT search_count FROM job_search_history WHERE user_id = ?", Integer.class, userId);
        assertEquals(1, rows);
        assertEquals(threads, count);

        String auth = "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].searchCount").value(threads))
                .andExpect(jsonPath("$[0].city").value("toronto"));
    }

    @Test
    void incompleteTableMakesTheMigrationAndFinalVerifierFail() throws SQLException {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbcTemplate.execute("DROP TABLE IF EXISTS job_search_history");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbcTemplate.execute("""
                CREATE TABLE job_search_history (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    keywords VARCHAR(500) NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        try {
            try (Connection connection = dataSource.getConnection()) {
                assertThrows(ScriptException.class, () -> ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource("schema/job_search_history.sql")),
                        "CREATE TABLE IF NOT EXISTS must not hide a missing criteria_hash/unique/FK");
            }
            try (Connection connection = dataSource.getConnection()) {
                assertThrows(ScriptException.class, () -> ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource("schema/job_catalog_phase1_verify.sql")));
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbcTemplate.execute("DROP TABLE IF EXISTS job_search_history");
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
            applyScript("schema/job_search_history.sql");
            applyScript("schema/job_catalog_phase1_verify.sql");
        }
    }

    @Test
    void twentyFirstSearchTrimsOnlyTheCurrentUser() throws Exception {
        String aliceEmail = "history-alice-" + UUID.randomUUID() + "@example.com";
        String bobEmail = "history-bob-" + UUID.randomUUID() + "@example.com";
        String alice = bearerFor(aliceEmail);
        String bob = bearerFor(bobEmail);
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/job-search-history")
                            .header("Authorization", bob)
                            .contentType("application/json")
                            .content("{\"keywords\":\"bob-" + i + "\"}"))
                    .andExpect(status().isNoContent());
        }
        for (int i = 0; i < 21; i++) {
            mockMvc.perform(post("/api/v1/job-search-history")
                            .header("Authorization", alice)
                            .contentType("application/json")
                            .content("{\"keywords\":\"alice-" + i + "\"}"))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(20));
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andExpect(jsonPath("$.length()").value(4));
    }

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123!"), "MySQL History User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    private void verifyServerIsSafeToUse() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith(REQUIRED_SERVER_VERSION_PREFIX), version);
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals(REQUIRED_SCHEMA, schema);
        List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
        assertTrue(grants.stream().anyMatch(g -> g.contains("`" + REQUIRED_SCHEMA + "`.*")), grants.toString());
        List<String> global = grants.stream()
                .filter(g -> g.contains(" ON *.*"))
                .filter(g -> !g.startsWith("GRANT USAGE ON *.*"))
                .toList();
        assertTrue(global.isEmpty(), global.toString());
        List<String> unexpected = jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.TABLES WHERE table_schema = DATABASE()",
                        String.class)
                .stream()
                .filter(t -> !OWNED_TABLES.contains(t.toLowerCase(Locale.ROOT)))
                .toList();
        assertTrue(unexpected.isEmpty(), unexpected.toString());
    }

    private void applyProductionSchema() throws SQLException {
        applyScript("infra/aws/mysql-init/01-schema.sql");
        applyScript("schema/job_catalog_phase1.sql");
        applyScript("schema/job_catalog_external_id_exact_identity.sql");
        applyScript("schema/job_catalog_external_id_no_pad.sql");
        applyScript("schema/jobs_applied_tracking.sql");
        applyScript("schema/users_auth_version.sql");
        applyScript("schema/site_feedback.sql");
        applyScript("schema/resume_builder.sql");
        applyScript("schema/job_search_history.sql");
        applyScript("schema/job_catalog_phase1_verify.sql");
    }

    private void applyScript(String path) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }
}
