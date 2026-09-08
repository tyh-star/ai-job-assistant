package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobappstats;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-app-stats-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobApplicationStatsSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void statsAreIsolatedIdempotentOnRepeatConfirmAndDropAfterRevoke() throws Exception {
        String alice = bearerFor("alice-stats@example.com");
        String bob = bearerFor("bob-stats@example.com");
        long aliceId = userId("alice-stats@example.com");
        long bobId = userId("bob-stats@example.com");

        long savedOnly = insertJob(aliceId, false, null);
        long applied = insertJob(aliceId, true, "2026-09-01 15:00:00");
        insertJob(bobId, true, "2026-09-01 15:00:00");

        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "30")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1))
                .andExpect(jsonPath("$.daily.length()").value(30));

        mockMvc.perform(post("/api/v1/jobs/" + applied + "/applied").header("Authorization", alice))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "7")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1))
                .andExpect(jsonPath("$.daily.length()").value(7));

        mockMvc.perform(delete("/api/v1/jobs/" + applied + "/applied").header("Authorization", alice))
                .andExpect(status().isOk());
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

        mockMvc.perform(post("/api/v1/jobs/" + savedOnly + "/applied").header("Authorization", alice))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "30")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplied").value(1));
    }

    @Test
    void rejectsInvalidDaysAndTimezone() throws Exception {
        String alice = bearerFor("stats-invalid@example.com");
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "14")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "30")
                        .param("timeZone", "Not/AZone"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "30")
                        .param("timeZone", "+01:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "90")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(90));
    }

    @Test
    void emptyAppliedListZeroFillsEveryDay() throws Exception {
        String alice = bearerFor("stats-empty@example.com");
        mockMvc.perform(get("/api/v1/jobs/application-stats")
                        .header("Authorization", alice)
                        .param("days", "7")
                        .param("timeZone", "America/Toronto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayCount").value(0))
                .andExpect(jsonPath("$.last7DaysCount").value(0))
                .andExpect(jsonPath("$.last30DaysCount").value(0))
                .andExpect(jsonPath("$.totalApplied").value(0))
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[0].count").value(0))
                .andExpect(jsonPath("$.daily[6].count").value(0));
    }

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123"), "Stats Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    private long userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long insertJob(long userId, boolean applied, String appliedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO jobs (user_id, company, title, description, source, status, applied, applied_at)
                VALUES (?, 'Example Corp', 'Backend Engineer', 'Fictional posting.', 'MANUAL', 'SAVED', ?, ?)
                """,
                userId, applied, appliedAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM jobs WHERE user_id = ?", Long.class, userId);
    }
}
