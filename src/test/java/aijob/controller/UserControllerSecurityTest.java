package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "个人中心" (profile: name, email, password) through the REAL Spring Security filter
 * chain, real H2, real {@link JwtUtil} -- not a mocked service. Proves the three things
 * this batch is actually about: (1) email changes verify the current password and hand
 * back a token that actually works, while the OLD token genuinely stops working, (2)
 * every operation is scoped to the caller's own JWT with no id to guess, and (3)
 * anonymous requests never reach the database.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:usersecurity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String bearerFor(String email, String rawPassword) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode(rawPassword), "Security Test User");
        return secondBearerFor(email);
    }

    /**
     * Mints ANOTHER token for an email that already has a row -- e.g. a second
     * browser/device that logged in before the account's auth_version changed. Reads
     * the row's CURRENT auth_version rather than assuming 0, so this stays correct even
     * when called after a password change.
     */
    private String secondBearerFor(String email) {
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        int authVersion = jdbcTemplate.queryForObject(
                "SELECT auth_version FROM users WHERE email = ?", Integer.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", authVersion));
    }

    private static void assertRejected(int status) {
        assertNotEquals(200, status);
        assertTrue(status == 401 || status == 403, "status=" + status);
    }

    // ---------------------------------------------------------------------------
    // anonymous: rejected before touching the database
    // ---------------------------------------------------------------------------

    @Test
    void anonymousGetMeIsRejected() throws Exception {
        int status = mockMvc.perform(get("/api/v1/users/me")).andReturn().getResponse().getStatus();
        assertRejected(status);
    }

    @Test
    void anonymousUpdateNameIsRejectedBeforeTouchingTheDatabase() throws Exception {
        String email = "anon-name@example.com";
        bearerFor(email, "Password123");

        int status = mockMvc.perform(put("/api/v1/users/me/name")
                        .contentType("application/json")
                        .content("{\"name\":\"Hacked\"}"))
                .andReturn().getResponse().getStatus();
        assertRejected(status);

        String stillOriginal = jdbcTemplate.queryForObject(
                "SELECT name FROM users WHERE email = ?", String.class, email);
        assertEquals("Security Test User", stillOriginal);
    }

    @Test
    void anonymousUpdateEmailIsRejectedBeforeTouchingTheDatabase() throws Exception {
        int status = mockMvc.perform(put("/api/v1/users/me/email")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"x\",\"newEmail\":\"new@example.com\"}"))
                .andReturn().getResponse().getStatus();
        assertRejected(status);
    }

    @Test
    void anonymousChangePasswordIsRejected() throws Exception {
        int status = mockMvc.perform(put("/api/v1/users/me/password")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"NewPassword456\"}"))
                .andReturn().getResponse().getStatus();
        assertRejected(status);
    }

    // ---------------------------------------------------------------------------
    // name
    // ---------------------------------------------------------------------------

    @Test
    void updateNameWorksNormallyAndReturnsTheUpdatedProfile() throws Exception {
        String auth = bearerFor("name-update@example.com", "Password123");

        mockMvc.perform(put("/api/v1/users/me/name")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"name\":\"  Real Name  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Real Name"));
    }

    @Test
    void aBlankNameIsRejected() throws Exception {
        String auth = bearerFor("name-blank@example.com", "Password123");

        mockMvc.perform(put("/api/v1/users/me/name")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------
    // email: the real point of this whole batch
    // ---------------------------------------------------------------------------

    @Test
    void updateEmailWithTheWrongCurrentPasswordIsRejectedAndNothingChanges() throws Exception {
        String email = "email-wrongpw@example.com";
        String auth = bearerFor(email, "Password123");

        mockMvc.perform(put("/api/v1/users/me/email")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"wrong\",\"newEmail\":\"new-address@example.com\"}"))
                .andExpect(status().isBadRequest());

        String stillOriginal = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE email = ?", String.class, email);
        assertEquals(email, stillOriginal);
    }

    @Test
    void updateEmailToAnAlreadyRegisteredAddressIsRejected() throws Exception {
        bearerFor("already-taken@example.com", "Password123");
        String auth = bearerFor("email-conflict@example.com", "Password123");

        mockMvc.perform(put("/api/v1/users/me/email")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newEmail\":\"already-taken@example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateEmailSucceedsReturnsAWorkingFreshTokenAndTheOldTokenStopsWorking() throws Exception {
        String oldEmail = "before-change@example.com";
        String oldAuth = bearerFor(oldEmail, "Password123");

        String body = mockMvc.perform(put("/api/v1/users/me/email")
                        .header("Authorization", oldAuth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newEmail\":\"after-change@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("after-change@example.com"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newToken = (String) objectMapper.readValue(body, Map.class).get("token");

        // The OLD token is still a validly-signed, unexpired JWT, but its subject
        // ("before-change@example.com") no longer names any account -- JwtAuthenticationFilter's
        // auth_version lookup for that email finds no row at all, which it treats
        // exactly like a revoked token: an immediate 401 before the controller ever
        // runs, never a fresh grant of access to the renamed account.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", oldAuth))
                .andExpect(status().isUnauthorized());

        // The NEW token actually works, and correctly names the new email.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("after-change@example.com"));
    }

    // ---------------------------------------------------------------------------
    // password
    // ---------------------------------------------------------------------------

    @Test
    void changePasswordWithTheWrongCurrentPasswordIsRejected() throws Exception {
        String auth = bearerFor("pw-wrong@example.com", "Password123");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewPassword456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordSucceedsAndTheNewPasswordActuallyWorksAtLogin() throws Exception {
        String email = "pw-change@example.com";
        String auth = bearerFor(email, "Password123");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"NewPassword456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The persistent-revocation guarantee this whole mechanism exists for: a password
     * change must invalidate EVERY previously-issued token for that account, on every
     * device -- not just clear one browser's own localStorage. Covers exactly the
     * scenario list this feature was specified against: two tokens issued before the
     * change, both rejected after it; old password stops working; new password works;
     * a token from that new login works; another user's token is untouched.
     */
    @Test
    void changingPasswordInvalidatesEveryPreviouslyIssuedTokenOnEveryDeviceButNeverAnotherUsers() throws Exception {
        String email = "auth-version@example.com";
        String deviceA = bearerFor(email, "Password123");
        String deviceB = secondBearerFor(email); // a second device/browser, logged in earlier

        String bobEmail = "auth-version-bob@example.com";
        String bobToken = bearerFor(bobEmail, "Password123");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", deviceA)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}"))
                .andExpect(status().isNoContent());

        // Both tokens issued before the change -- including the one that DIDN'T make
        // the change -- are now rejected with a deterministic 401, before ever reaching
        // the controller.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", deviceA))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", deviceB))
                .andExpect(status().isUnauthorized());

        // The old password no longer logs in; the new one does.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
        String loginBody = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"NewPassword456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A token from that fresh login -- carrying the NEW auth_version -- works fine.
        String newToken = (String) objectMapper.readValue(loginBody, Map.class).get("token");
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // Bob's own token, from an entirely different account, was never touched.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(bobEmail));
    }

    /**
     * A stray/manual Authorization header on /auth/login must never lock a user out of
     * logging back in -- see JwtAuthenticationFilter's AUTH_PATH_PREFIX exemption.
     */
    @Test
    void loggingInWithARevokedTokenAttachedStillSucceeds() throws Exception {
        String email = "auth-version-relogin@example.com";
        String staleToken = bearerFor(email, "Password123");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", staleToken)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .header("Authorization", staleToken) // now-revoked, attached anyway
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"NewPassword456\"}"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------
    // isolation: acting as Alice must never touch Bob's row
    // ---------------------------------------------------------------------------

    @Test
    void updateNameNeverAffectsAnotherUsersRow() throws Exception {
        String bobEmail = "bob-isolated@example.com";
        bearerFor(bobEmail, "Password123");
        String aliceAuth = bearerFor("alice-isolated@example.com", "Password123");

        mockMvc.perform(put("/api/v1/users/me/name")
                        .header("Authorization", aliceAuth)
                        .contentType("application/json")
                        .content("{\"name\":\"Alice Renamed\"}"))
                .andExpect(status().isOk());

        String bobsNameUnchanged = jdbcTemplate.queryForObject(
                "SELECT name FROM users WHERE email = ?", String.class, bobEmail);
        assertEquals("Security Test User", bobsNameUnchanged);
    }
}
