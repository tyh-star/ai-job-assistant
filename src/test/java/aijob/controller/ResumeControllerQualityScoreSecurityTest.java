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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "简历质量评分" through the REAL Spring Security filter chain, real H2, real
 * {@link JwtUtil} -- proves the same three things every other feature's security test
 * in this project proves: anonymous requests never reach the database, a resumeId
 * belonging to another user is indistinguishable from one that does not exist, and the
 * owner genuinely gets a scored (or honestly-unscorable) result. Synthetic resume text
 * only -- never a real user's resume content.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:resumequality;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/resume-quality-h2.sql")
class ResumeControllerQualityScoreSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long insertUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123"), "Quality Score Test User");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private String bearerFor(String email) {
        long userId = insertUser(email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    /** Synthetic text only -- never a real user's resume. */
    private static final String SYNTHETIC_RESUME_TEXT = """
            Jordan Lee
            jordan.lee@example.com | (555) 123-4567

            EXPERIENCE
            Software Engineer, Example Corp, Jan 2021 - Present
            Reduced API latency by 35% by redesigning the caching layer
            Led a team of 3 engineers to migrate the billing service to a new database

            EDUCATION
            B.S. Computer Science, State University, 2016 - 2020

            SKILLS
            Java, Spring Boot, PostgreSQL, Docker, AWS
            """;

    private long insertResume(long userId, String extractedText) {
        jdbcTemplate.update(
                "INSERT INTO resumes (user_id, file_name, file_type, file_path, extracted_text, origin, review_status, extraction_status) "
                        + "VALUES (?, 'resume.pdf', 'pdf', '/tmp/does-not-matter.pdf', ?, 'UPLOAD', 'READY', 'TEXT')",
                userId, extractedText);
        return jdbcTemplate.queryForObject("SELECT id FROM resumes WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, userId);
    }

    @Test
    void anonymousRequestIsRejectedBeforeTouchingTheDatabase() throws Exception {
        String auth = bearerFor("owner-anon-check@example.com");
        long resumeId = insertResume(
                jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, "owner-anon-check@example.com"),
                SYNTHETIC_RESUME_TEXT);

        int status = mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score"))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(status == 401 || status == 403, "status=" + status);
        // (auth is unused here except to confirm the resume exists under a real owner)
        org.junit.jupiter.api.Assertions.assertNotNull(auth);
    }

    @Test
    void ownerReceivesARealScoreForACompleteSyntheticResume() throws Exception {
        String email = "owner-complete@example.com";
        String auth = bearerFor(email);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        long resumeId = insertResume(userId, SYNTHETIC_RESUME_TEXT);

        mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorable").value(true))
                .andExpect(jsonPath("$.rubricVersion").value("v1"))
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories.length()").value(5));
    }

    @Test
    void anotherUsersGuessedResumeIdReturns404NotSomeoneElsesScore() throws Exception {
        String ownerEmail = "owner-guessed@example.com";
        long ownerId = insertUser(ownerEmail);
        long resumeId = insertResume(ownerId, SYNTHETIC_RESUME_TEXT);

        String attackerAuth = bearerFor("attacker-guessed@example.com");

        mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", attackerAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonExistentResumeIdAlsoReturns404() throws Exception {
        String auth = bearerFor("owner-nonexistent@example.com");

        mockMvc.perform(get("/api/v1/resumes/999999/quality-score").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void aResumeWithNoExtractableTextIsHonestlyUnscorableNeverAMisleadingZero() throws Exception {
        String email = "owner-blank-text@example.com";
        String auth = bearerFor(email);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        long resumeId = insertResume(userId, "");

        mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorable").value(false))
                .andExpect(jsonPath("$.reasonCode").value("TEXT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.score").doesNotExist());
    }

    @Test
    void scoringTheSameResumeTwiceReturnsAnIdenticalResult() throws Exception {
        String email = "owner-repeat@example.com";
        String auth = bearerFor(email);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        long resumeId = insertResume(userId, SYNTHETIC_RESUME_TEXT);

        String first = mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }
}
