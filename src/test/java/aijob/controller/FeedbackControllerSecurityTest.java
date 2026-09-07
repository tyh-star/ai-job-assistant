package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "网站评价" through the REAL Spring Security filter chain, real H2, real
 * {@link JwtUtil} -- anonymous rejected on the account routes, the public summary
 * route works for anyone, a caller only ever sees/edits their own feedback, and the
 * summary never leaks a comment or identity.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:sitefeedback;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/site-feedback-h2.sql")
class FeedbackControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123"), "Feedback Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    private static void assertRejected(int status) {
        assertNotEquals(200, status);
        assertTrue(status == 401 || status == 403, "status=" + status);
    }

    // ---------------------------------------------------------------------------
    // anonymous: rejected on the account routes, allowed on the public summary
    // ---------------------------------------------------------------------------

    @Test
    void anonymousGetMeIsRejected() throws Exception {
        int status = mockMvc.perform(get("/api/v1/feedback/me")).andReturn().getResponse().getStatus();
        assertRejected(status);
    }

    @Test
    void anonymousPutMeIsRejectedBeforeTouchingTheDatabase() throws Exception {
        int status = mockMvc.perform(put("/api/v1/feedback/me")
                        .contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\"great\"}"))
                .andReturn().getResponse().getStatus();
        assertRejected(status);

        long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM site_feedback", Long.class);
        assertTrue(count == 0, "an anonymous PUT must never write a row");
    }

    @Test
    void anonymousDeleteMeIsRejected() throws Exception {
        int status = mockMvc.perform(delete("/api/v1/feedback/me")).andReturn().getResponse().getStatus();
        assertRejected(status);
    }

    @Test
    void anonymousCanReadThePublicSummary() throws Exception {
        mockMvc.perform(get("/api/v1/feedback/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").isNumber())
                .andExpect(jsonPath("$.reviewerCount").isNumber());
    }

    // ---------------------------------------------------------------------------
    // rating validation: 1 and 5 accepted, 0 and 6 rejected, comment length limit
    // ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void oneStarAndFiveStarAreBothAccepted(int rating) throws Exception {
        String auth = bearerFor("rating-" + rating + "@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":" + rating + ",\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(rating));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 100})
    void ratingsOutsideOneToFiveAreRejected(int illegalRating) throws Exception {
        String auth = bearerFor("illegal-rating-" + illegalRating + "@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":" + illegalRating + ",\"comment\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCommentOverOneThousandCharactersIsRejected() throws Exception {
        String auth = bearerFor("overlong-comment@example.com");
        String tooLong = "a".repeat(1001);

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":4,\"comment\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCommentOfExactlyOneThousandCharactersIsAccepted() throws Exception {
        String auth = bearerFor("max-length-comment@example.com");
        String exactlyMax = "a".repeat(1000);

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":4,\"comment\":\"" + exactlyMax + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingRatingIsRejected() throws Exception {
        String auth = bearerFor("missing-rating@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"comment\":\"no rating given\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCommentIsTrimmedAndBlankAfterTrimmingIsStoredAsNoComment() throws Exception {
        String auth = bearerFor("trim-comment@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":4,\"comment\":\"  nice job!  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("nice job!"));

        String auth2 = bearerFor("blank-comment@example.com");
        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth2)
                        .contentType("application/json")
                        .content("{\"rating\":4,\"comment\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").doesNotExist());
    }

    @Test
    void theCommentIsOptional() throws Exception {
        String auth = bearerFor("no-comment@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(3));
    }

    // ---------------------------------------------------------------------------
    // GET/PUT/DELETE lifecycle: create, edit, delete
    // ---------------------------------------------------------------------------

    @Test
    void getMeReturns404BeforeAnyFeedbackHasEverBeenSubmitted() throws Exception {
        String auth = bearerFor("never-submitted@example.com");

        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void puttingTwiceEditsTheSameRowRatherThanCreatingASecondOne() throws Exception {
        String email = "edit-flow@example.com";
        String auth = bearerFor(email);

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":2,\"comment\":\"meh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(2))
                .andExpect(jsonPath("$.comment").value("meh"));

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\"actually great after the update\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("actually great after the update"));

        // GET reflects the EDITED values, and there is still only one row for this user.
        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_feedback WHERE user_id = ?", Long.class, userId);
        assertTrue(rowCount == 1, "editing must never create a second row, found " + rowCount);
    }

    @Test
    void deletingRemovesItAndASubsequentGetIs404() throws Exception {
        String auth = bearerFor("delete-flow@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":4,\"comment\":\"fine\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/feedback/me").header("Authorization", auth))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingWhenNothingExistsIs404NotASilentSuccess() throws Exception {
        String auth = bearerFor("delete-nothing@example.com");

        mockMvc.perform(delete("/api/v1/feedback/me").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------
    // isolation: acting as Alice must never touch or reveal Bob's feedback
    // ---------------------------------------------------------------------------

    @Test
    void eachUserOnlySeesTheirOwnFeedback() throws Exception {
        String aliceAuth = bearerFor("alice-feedback@example.com");
        String bobAuth = bearerFor("bob-feedback@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", aliceAuth)
                        .contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\"alice loves it\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", bobAuth)
                        .contentType("application/json")
                        .content("{\"rating\":1,\"comment\":\"bob hates it\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("alice loves it"));

        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", bobAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(1))
                .andExpect(jsonPath("$.comment").value("bob hates it"));

        // Bob deleting his own feedback must never touch Alice's.
        mockMvc.perform(delete("/api/v1/feedback/me").header("Authorization", bobAuth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/feedback/me").header("Authorization", aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));
    }

    // ---------------------------------------------------------------------------
    // summary: correct aggregate, never a comment or identity
    // ---------------------------------------------------------------------------

    @Test
    void theSummaryReflectsSubmittedRatingsButNeverExposesCommentsOrIdentity() throws Exception {
        String auth1 = bearerFor("summary-a@example.com");
        String auth2 = bearerFor("summary-b@example.com");

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth1)
                        .contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\"top secret opinion A\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth2)
                        .contentType("application/json")
                        .content("{\"rating\":3,\"comment\":\"top secret opinion B\"}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/feedback/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.reviewerCount").value(2))
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains("top secret"), "the summary must never contain comment text");
        assertTrue(!body.contains("summary-a@example.com") && !body.contains("summary-b@example.com"),
                "the summary must never contain an email address");
        assertTrue(!body.contains("userId") && !body.contains("user_id"),
                "the summary must never contain a user id");
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of("averageRating", "reviewerCount"), names);
    }

    @Test
    void updatingDoesNotChangeCreatedAt() throws Exception {
        String email = "created-at-stable@example.com";
        String auth = bearerFor(email);

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":2,\"comment\":\"first\"}"))
                .andExpect(status().isOk());

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        java.sql.Timestamp createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM site_feedback WHERE user_id = ?",
                java.sql.Timestamp.class,
                userId);

        mockMvc.perform(put("/api/v1/feedback/me")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"rating\":5,\"comment\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));

        java.sql.Timestamp createdAtAfter = jdbcTemplate.queryForObject(
                "SELECT created_at FROM site_feedback WHERE user_id = ?",
                java.sql.Timestamp.class,
                userId);
        org.junit.jupiter.api.Assertions.assertEquals(createdAt, createdAtAfter);
    }

    @Test
    void concurrentFirstSubmitsLeaveExactlyOneRow() throws Exception {
        String email = "concurrent-first@example.com";
        String auth = bearerFor(email);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final int rating = i + 4;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    int status = mockMvc.perform(put("/api/v1/feedback/me")
                                    .header("Authorization", auth)
                                    .contentType("application/json")
                                    .content("{\"rating\":" + rating + ",\"comment\":\"race\"}"))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // counted as a non-200 below
                } finally {
                    done.countDown();
                }
            });
        }

        org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        org.junit.jupiter.api.Assertions.assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_feedback WHERE user_id = ?", Long.class, userId);
        org.junit.jupiter.api.Assertions.assertEquals(1L, rowCount);
        org.junit.jupiter.api.Assertions.assertEquals(2, ok.get());
    }
}
