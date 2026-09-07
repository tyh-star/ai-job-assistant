package aijob.controller;

import aijob.security.JwtUtil;
import aijob.AiJobApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST}/{@code DELETE /api/v1/jobs/{id}/applied} through the REAL Spring
 * Security filter chain (not the standalone-mocked {@link JobControllerAppliedTest}) --
 * H2 in-memory, real {@link JwtUtil}, real database rows. Answers exactly the four
 * questions asked of this endpoint: only the owning JWT can write; a guessed numeric id
 * belonging to someone else is a 404 that reveals nothing; an anonymous request is
 * rejected before it ever reaches the database; and the confirm/revoke cycle's
 * idempotency (no duplicate rows, {@code appliedAt} only changes on a genuine
 * transition) holds against REAL persistence, not a mock that cannot lie about it.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobappliedsecurity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobControllerAppliedSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "Applied Security Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    /** Creates a real job row through the real create-job endpoint, owned by {@code auth}'s user. */
    private long createJob(String auth) throws Exception {
        String body = mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("""
                                {"company":"Acme","title":"Engineer","location":"Remote",
                                 "jobUrl":"https://example.com/job","description":"Ship things."}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
        return ((Number) parsed.get("id")).longValue();
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT applied, applied_at FROM jobs WHERE id = ?", id);
    }

    // ---------------------------------------------------------------------------
    // anonymous: rejected before touching the database
    // ---------------------------------------------------------------------------

    @Test
    void anonymousMarkAppliedIsRejectedBeforeTouchingTheDatabase() throws Exception {
        String auth = bearerFor("owner-anon-mark@example.com");
        long id = createJob(auth);

        int status = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status=" + status);

        Map<String, Object> unchanged = row(id);
        assertEquals(Boolean.FALSE, toBoolean(unchanged.get("APPLIED")),
                "an anonymous request must never write applied=true");
        assertNull(unchanged.get("APPLIED_AT"));
    }

    @Test
    void anonymousClearAppliedIsRejectedBeforeTouchingTheDatabase() throws Exception {
        String auth = bearerFor("owner-anon-clear@example.com");
        long id = createJob(auth);
        mockMvc.perform(post("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk());

        int status = mockMvc.perform(delete("/api/v1/jobs/" + id + "/applied"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status=" + status);

        // Still applied -- the anonymous revoke attempt must never have reached the row.
        Map<String, Object> unchanged = row(id);
        assertEquals(Boolean.TRUE, toBoolean(unchanged.get("APPLIED")));
    }

    @Test
    void anInvalidBearerTokenIsRejectedBeforeTouchingTheDatabase() throws Exception {
        String auth = bearerFor("owner-badtoken@example.com");
        long id = createJob(auth);

        int status = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status=" + status);
        assertEquals(Boolean.FALSE, toBoolean(row(id).get("APPLIED")));
    }

    // ---------------------------------------------------------------------------
    // cross-user: guessing another user's numeric id
    // ---------------------------------------------------------------------------

    @Test
    void guessingAnotherUsersJobIdReturns404AndNeverWritesAnything() throws Exception {
        String ownerAuth = bearerFor("owner-guess@example.com");
        long ownedByAlice = createJob(ownerAuth);
        String attackerAuth = bearerFor("attacker-guess@example.com");

        mockMvc.perform(post("/api/v1/jobs/" + ownedByAlice + "/applied").header("Authorization", attackerAuth))
                .andExpect(status().isNotFound());

        assertEquals(Boolean.FALSE, toBoolean(row(ownedByAlice).get("APPLIED")),
                "guessing someone else's id must never mark THEIR job applied");
    }

    @Test
    void revokingAnotherUsersJobReturns404AndNeverClearsAnything() throws Exception {
        String ownerAuth = bearerFor("owner-guess-revoke@example.com");
        long ownedByAlice = createJob(ownerAuth);
        mockMvc.perform(post("/api/v1/jobs/" + ownedByAlice + "/applied").header("Authorization", ownerAuth))
                .andExpect(status().isOk());
        String attackerAuth = bearerFor("attacker-guess-revoke@example.com");

        mockMvc.perform(delete("/api/v1/jobs/" + ownedByAlice + "/applied").header("Authorization", attackerAuth))
                .andExpect(status().isNotFound());

        assertEquals(Boolean.TRUE, toBoolean(row(ownedByAlice).get("APPLIED")),
                "an attacker's 404 must never clear the real owner's applied flag");
    }

    @Test
    void aNonExistentIdAndAnotherUsersRealIdBothReturn404_neverLeakingWhichIsWhich() throws Exception {
        String ownerAuth = bearerFor("owner-existence@example.com");
        long realId = createJob(ownerAuth);
        String attackerAuth = bearerFor("attacker-existence@example.com");

        int statusForSomeoneElsesRealJob = mockMvc.perform(
                        post("/api/v1/jobs/" + realId + "/applied").header("Authorization", attackerAuth))
                .andReturn().getResponse().getStatus();
        int statusForANumberThatDoesNotExistAtAll = mockMvc.perform(
                        post("/api/v1/jobs/999999999/applied").header("Authorization", attackerAuth))
                .andReturn().getResponse().getStatus();

        assertEquals(404, statusForSomeoneElsesRealJob);
        assertEquals(404, statusForANumberThatDoesNotExistAtAll);
    }

    // ---------------------------------------------------------------------------
    // idempotency against REAL persistence (not a mock)
    // ---------------------------------------------------------------------------

    @Test
    void repeatConfirmationNeverCreatesADuplicateRowAndNeverResetsAppliedAt() throws Exception {
        String auth = bearerFor("idempotent-repeat@example.com");
        long id = createJob(auth);

        String firstBody = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        String firstAppliedAt = (String) objectMapper.readValue(firstBody, Map.class).get("appliedAt");

        Thread.sleep(5); // enough real wall-clock gap that a fresh timestamp would differ

        String secondBody = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        String secondAppliedAt = (String) objectMapper.readValue(secondBody, Map.class).get("appliedAt");

        assertEquals(firstAppliedAt, secondAppliedAt, "a repeat confirmation must never reset appliedAt");

        Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, id);
        assertEquals(1, rowCount, "a repeat confirmation must never create a second row");
    }

    @Test
    void revokeThenReconfirmSetsAGenuinelyNewAppliedAt() throws Exception {
        String auth = bearerFor("idempotent-cycle@example.com");
        long id = createJob(auth);

        String firstBody = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstAppliedAt = (String) objectMapper.readValue(firstBody, Map.class).get("appliedAt");

        mockMvc.perform(delete("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.appliedAt").doesNotExist());

        Map<String, Object> revokedRow = row(id);
        assertEquals(Boolean.FALSE, toBoolean(revokedRow.get("APPLIED")));
        assertNull(revokedRow.get("APPLIED_AT"));

        Thread.sleep(5);

        String secondBody = mockMvc.perform(post("/api/v1/jobs/" + id + "/applied").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andReturn().getResponse().getContentAsString();
        String secondAppliedAt = (String) objectMapper.readValue(secondBody, Map.class).get("appliedAt");

        assertNotEquals(firstAppliedAt, secondAppliedAt,
                "re-applying after a revoke must get a genuinely new appliedAt");
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.FALSE;
    }
}
