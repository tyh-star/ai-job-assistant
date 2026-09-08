package aijob.controller;

import aijob.AiJobApplication;
import aijob.exception.WeakPasswordException;
import aijob.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration and change-password share {@link aijob.security.PasswordStrength}.
 * Existing hashes that would fail the gate still log in. Fictional users only.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:passwordstrength;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-password-strength-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class PasswordStrengthSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerRejectsWeakPasswordsWithStableCode() throws Exception {
        expectWeakRegister("tooshort@example.com", "Pass1!");
        expectWeakRegister("noupper@example.com", "password123!");
        expectWeakRegister("nolower@example.com", "PASSWORD123!");
        expectWeakRegister("nodigit@example.com", "Password!!!!");
        expectWeakRegister("nospecial@example.com", "Password1234");
        expectWeakRegister("spacey@example.com", "Pass word123!");
        expectWeakRegister("toolong@example.com", "Aa1!" + "x".repeat(69));

        Integer leftover = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email IN (?,?,?,?,?,?,?)",
                Integer.class,
                "tooshort@example.com", "noupper@example.com", "nolower@example.com",
                "nodigit@example.com", "nospecial@example.com", "spacey@example.com",
                "toolong@example.com");
        org.junit.jupiter.api.Assertions.assertEquals(0, leftover,
                "a rejected weak registration must not insert a users row");
    }

    @Test
    void registerAcceptsAStrongPasswordAndExistingWeakHashStillLogsIn() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"name\":\"Alex Morgan\",\"email\":\"strong-reg@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"strong-reg@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                "legacy-login@example.com",
                passwordEncoder.encode("Password123"),
                "Legacy Login User");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"legacy-login@example.com\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordRejectsWeakNewPasswordWithoutWriting() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                "change-weak@example.com",
                passwordEncoder.encode("Password123"),
                "Change Weak User");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "change-weak@example.com");
        Integer versionBefore = jdbcTemplate.queryForObject(
                "SELECT auth_version FROM users WHERE email = ?", Integer.class, "change-weak@example.com");
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE email = ?", String.class, "change-weak@example.com");
        String auth = "Bearer " + jwtUtil.generateToken(
                new JwtUtil.UserTokenInfo(userId, "change-weak@example.com", "USER", 0));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"Password1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(WeakPasswordException.CODE));

        org.junit.jupiter.api.Assertions.assertEquals(storedHash, jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE email = ?", String.class, "change-weak@example.com"));
        org.junit.jupiter.api.Assertions.assertEquals(versionBefore, jdbcTemplate.queryForObject(
                "SELECT auth_version FROM users WHERE email = ?", Integer.class, "change-weak@example.com"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"change-weak@example.com\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changingToAStrongPasswordInvalidatesTheOldJwt() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                "change-strong@example.com",
                passwordEncoder.encode("Password123"),
                "Change Strong User");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "change-strong@example.com");
        String oldAuth = "Bearer " + jwtUtil.generateToken(
                new JwtUtil.UserTokenInfo(userId, "change-strong@example.com", "USER", 0));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", oldAuth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"Password123!\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                        .header("Authorization", oldAuth))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"change-strong@example.com\",\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"change-strong@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk());
    }

    private void expectWeakRegister(String email, String password) throws Exception {
        String json = "{\"name\":\"Alex Morgan\",\"email\":\"" + email + "\",\"password\":\""
                + password.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(WeakPasswordException.CODE));
    }
}
