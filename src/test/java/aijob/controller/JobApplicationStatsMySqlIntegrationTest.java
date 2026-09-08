package aijob.controller;

import aijob.AiJobApplication;
import aijob.entity.Job;
import aijob.mapper.JobMapper;
import aijob.security.JwtUtil;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Application-stats against real MySQL 8.4 TIMESTAMP storage. Ground truth is
 * {@code UNIX_TIMESTAMP(applied_at)}, not {@code LocalDateTime.now(UTC)} inferred in Java.
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
                "aijob.storage.root=target/aijob-app-stats-mysql-test-uploads",
                "spring.datasource.hikari.data-source-properties.connectionTimeZone=UTC",
                "spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession=true"
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "set AIJOB_MYSQL_TEST_URL/USERNAME/PASSWORD (see infra/mysql-test) "
                + "to run against an isolated MySQL 8.4")
class JobApplicationStatsMySqlIntegrationTest {

    private static final String URL_VARIABLE = "AIJOB_MYSQL_TEST_URL";
    private static final String REQUIRED_SERVER_VERSION_PREFIX = "8.4";
    private static final String REQUIRED_SCHEMA = "aijob_import_test";
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
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
    private JobMapper jobMapper;

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv(URL_VARIABLE);
        Assumptions.assumeTrue(url != null && !url.isBlank());
        assertTrue(url.startsWith("jdbc:mysql:"));
        verifyServerIsSafeToUse();
        applyProductionSchema();
    }

    @Test
    void jdbcRoundTripKeepsTheRealUtcInstantAndTorontoBuckets() throws Exception {
        String email = "stats-mysql-" + UUID.randomUUID() + "@example.com";
        String auth = bearerFor(email);
        long userId = userId(email);
        Instant known = Instant.parse("2026-03-09T00:30:00Z");
        long epoch = known.getEpochSecond();

        jdbcTemplate.execute("SET time_zone = '+00:00'");
        jdbcTemplate.update("""
                INSERT INTO jobs (user_id, company, title, description, source, status, applied, applied_at)
                VALUES (?, 'Example Corp', 'Backend Engineer', 'Fictional posting.', 'MANUAL', 'SAVED', TRUE, FROM_UNIXTIME(?))
                """, userId, epoch);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM jobs WHERE user_id = ?", Long.class, userId);

        Long unix = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(applied_at) FROM jobs WHERE id = ?", Long.class, jobId);
        assertEquals(epoch, unix, "MySQL must store the UTC instant we wrote");

        List<Job> applied = jobMapper.findCurrentlyAppliedByUserId(userId);
        assertEquals(1, applied.size());
        assertNotNull(applied.get(0).getAppliedAt());
        long fromMapper = applied.get(0).getAppliedAt().toInstant(ZoneOffset.UTC).getEpochSecond();
        assertEquals(epoch, fromMapper,
                "JDBC LocalDateTime must still be the same UTC instant as UNIX_TIMESTAMP");

        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", auth)
                        .param("days", "90")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1))
                .andExpect(jsonPath("$.daily.length()").value(90));
        assertEquals(LocalDate.of(2026, 3, 8), known.atZone(TORONTO).toLocalDate());
    }

    @Test
    void markAppliedWritesARealUtcInstantAndRepeatConfirmDoesNotChangeIt() throws Exception {
        String email = "stats-mark-" + UUID.randomUUID() + "@example.com";
        String auth = bearerFor(email);
        long userId = userId(email);
        jdbcTemplate.update("""
                INSERT INTO jobs (user_id, company, title, description, source, status, applied)
                VALUES (?, 'Example Corp', 'Backend Engineer', 'Fictional posting.', 'MANUAL', 'SAVED', FALSE)
                """, userId);
        long jobId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM jobs WHERE user_id = ?", Long.class, userId);

        long before = Instant.now().getEpochSecond();
        mockMvc.perform(post("/api/v1/jobs/" + jobId + "/applied").header("Authorization", auth))
                .andExpect(status().isOk());
        long after = Instant.now().getEpochSecond();

        jdbcTemplate.execute("SET time_zone = '+00:00'");
        Long unix = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(applied_at) FROM jobs WHERE id = ?", Long.class, jobId);
        assertNotNull(unix);
        assertTrue(unix >= before - 2 && unix <= after + 2, "unix=" + unix);

        Job mapped = jobMapper.findCurrentlyAppliedByUserId(userId).get(0);
        assertEquals(unix, mapped.getAppliedAt().toInstant(ZoneOffset.UTC).getEpochSecond());

        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", auth)
                        .param("days", "7")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1))
                .andExpect(jsonPath("$.todayCount").value(1))
                .andExpect(jsonPath("$.daily.length()").value(7));

        mockMvc.perform(post("/api/v1/jobs/" + jobId + "/applied").header("Authorization", auth))
                .andExpect(status().isOk());
        Long unixAgain = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(applied_at) FROM jobs WHERE id = ?", Long.class, jobId);
        assertEquals(unix, unixAgain);
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", auth)
                        .param("days", "7")
                        .param("timeZone", "America/Toronto"))
                .andExpect(jsonPath("$.totalApplied").value(1));

        mockMvc.perform(delete("/api/v1/jobs/" + jobId + "/applied").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", auth)
                        .param("days", "7")
                        .param("timeZone", "America/Toronto"))
                .andExpect(jsonPath("$.totalApplied").value(0))
                .andExpect(jsonPath("$.todayCount").value(0));
    }

    @Test
    void savedNotAppliedIsNotCountedAndUsersAreIsolated() throws Exception {
        String aliceEmail = "stats-alice-" + UUID.randomUUID() + "@example.com";
        String bobEmail = "stats-bob-" + UUID.randomUUID() + "@example.com";
        String alice = bearerFor(aliceEmail);
        String bob = bearerFor(bobEmail);
        jdbcTemplate.update("""
                INSERT INTO jobs (user_id, company, title, description, source, status, applied)
                VALUES (?, 'Example Corp', 'Saved Only', 'Fictional posting.', 'MANUAL', 'SAVED', FALSE)
                """, userId(aliceEmail));
        jdbcTemplate.update("""
                INSERT INTO jobs (user_id, company, title, description, source, status, applied, applied_at)
                VALUES (?, 'Example Corp', 'Bob Applied', 'Fictional posting.', 'MANUAL', 'SAVED', TRUE, UTC_TIMESTAMP())
                """, userId(bobEmail));

        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "30")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(0));
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", bob)
                        .param("days", "30")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1));
    }

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123!"), "MySQL Stats User");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(id, email, "USER", 0));
    }

    private long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void verifyServerIsSafeToUse() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith(REQUIRED_SERVER_VERSION_PREFIX), version);
        assertEquals(REQUIRED_SCHEMA, jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
        assertTrue(grants.stream().anyMatch(g -> g.contains("`" + REQUIRED_SCHEMA + "`.*")));
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
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("infra/aws/mysql-init/01-schema.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/job_catalog_phase1.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/job_catalog_external_id_exact_identity.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/job_catalog_external_id_no_pad.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/jobs_applied_tracking.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/users_auth_version.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/site_feedback.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/resume_builder.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/job_search_history.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("schema/job_catalog_phase1_verify.sql"));
        }
    }
}
