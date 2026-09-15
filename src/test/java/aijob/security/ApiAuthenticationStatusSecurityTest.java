package aijob.security;

import aijob.AiJobApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401 = no usable credentials (missing, malformed, wrong key, expired, revoked);
 * 403 = authenticated but not allowed. Through the real filter chain on H2.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:apiauthstatus;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=" + ApiAuthenticationStatusSecurityTest.SECRET,
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/site-feedback-h2.sql")
class ApiAuthenticationStatusSecurityTest {

    static final String SECRET = "test-only-jwt-secret-key-must-be-32b!!";
    private static final String PROTECTED = "/api/v1/users/me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser(String email) {
        jdbcTemplate.update("INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "Auth Status User");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(long userId, String email, SecretKey key, Date expiration) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("role", "USER")
                .claim("authVersion", 0)
                .issuedAt(new Date(expiration.getTime() - 3_600_000))
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    @Test
    void noToken_onProtectedRoute_is401WithJsonBody() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(PROTECTED));
    }

    @Test
    void expiredToken_is401NotForbidden() throws Exception {
        long id = insertUser("expired@example.com");
        String expired = token(id, "expired@example.com", key(SECRET), new Date(System.currentTimeMillis() - 60_000));
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void malformedToken_is401() throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKey_is401() throws Exception {
        long id = insertUser("forged@example.com");
        String forged = token(id, "forged@example.com", key("another-secret-key-that-is-at-least-32b!!"),
                new Date(System.currentTimeMillis() + 3_600_000));
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedToken_is401() throws Exception {
        long id = insertUser("revoked@example.com");
        String bearer = "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(id, "revoked@example.com", "USER", 0));
        jdbcTemplate.update("UPDATE users SET auth_version = 1 WHERE id = ?", id);
        mockMvc.perform(get(PROTECTED).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("revoked")));
    }

    @Test
    void unknownRoute_withoutToken_is401NotForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/definitely-not-a-route"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validToken_reachesTheController() throws Exception {
        long id = insertUser("valid@example.com");
        String bearer = "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(id, "valid@example.com", "USER", 0));
        mockMvc.perform(get(PROTECTED).header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void expiredToken_onPublicRoute_stillServesTheVisitor() throws Exception {
        long id = insertUser("public@example.com");
        String expired = token(id, "public@example.com", key(SECRET), new Date(System.currentTimeMillis() - 60_000));
        mockMvc.perform(get("/api/v1/feedback/summary").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());
    }

    @Test
    void accessDenied_forAnAuthenticatedUser_is403WithJsonBody() throws Exception {
        RestSecurityErrorHandler handler = new RestSecurityErrorHandler(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin-only");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":403"));
        assertTrue(response.getContentAsString().contains("/api/v1/admin-only"));
    }
}
