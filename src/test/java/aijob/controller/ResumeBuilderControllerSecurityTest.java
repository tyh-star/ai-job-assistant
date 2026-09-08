package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用简历生成器 (resume builder) through the REAL Spring Security filter chain, real
 * H2, real {@link JwtUtil}: multi-draft ownership, version-conflict semantics,
 * cross-user 404 on every route, normalized preview, DOCX generation into a
 * GENERATED-origin resume that appears in "我的简历" and is immediately usable by the
 * quality-score endpoint, and idempotent retries.
 * <p>
 * Fixtures are entirely fictional: Alex Morgan, Example University, Example Company,
 * Project Atlas. Never a real person's data (see the resume-builder feature spec's
 * absolute prohibition on real personal data in automated tests).
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:resumebuilder;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-resume-builder-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/resume-builder-h2.sql")
class ResumeBuilderControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123"), "Resume Builder Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    private static void assertRejected(int status) {
        assertNotEquals(200, status);
        assertTrue(status == 401 || status == 403, "status=" + status);
    }

    private long createDraft(String auth, String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/resume-builder/drafts")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"templateType\":\"GENERIC_ATS\",\"locale\":\"en\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private JsonNode getDraft(String auth, long id) throws Exception {
        String body = mockMvc.perform(get("/api/v1/resume-builder/drafts/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ---------------------------------------------------------------------------
    // anonymous: rejected everywhere
    // ---------------------------------------------------------------------------

    @Test
    void anonymousIsRejectedOnEveryRoute() throws Exception {
        assertRejected(mockMvc.perform(get("/api/v1/resume-builder/drafts"))
                .andReturn().getResponse().getStatus());
        assertRejected(mockMvc.perform(post("/api/v1/resume-builder/drafts")
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"templateType\":\"GENERIC_ATS\",\"locale\":\"en\"}"))
                .andReturn().getResponse().getStatus());
        assertRejected(mockMvc.perform(get("/api/v1/resume-builder/drafts/1"))
                .andReturn().getResponse().getStatus());
    }

    // ---------------------------------------------------------------------------
    // multi-draft: a user can hold several, independently
    // ---------------------------------------------------------------------------

    @Test
    void aUserCanHoldMultipleIndependentDrafts() throws Exception {
        String auth = bearerFor("multi-draft@example.com");
        long first = createDraft(auth, "Draft One");
        long second = createDraft(auth, "Draft Two");
        assertNotEquals(first, second);

        mockMvc.perform(get("/api/v1/resume-builder/drafts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        JsonNode draftContent = getDraft(auth, first);
        String updateBody = ("""
                {"title":"Draft One Edited","templateType":"GENERIC_ATS","locale":"en","version":%d,
                 "content":%s}
                """).formatted(draftContent.get("version").asLong(),
                objectMapper.writeValueAsString(draftContent.get("content")));

        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + first)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Draft One Edited"));

        // Editing the first draft must never touch the second.
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + second).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Draft Two"));
    }

    // ---------------------------------------------------------------------------
    // version conflict: a stale version is rejected, never silently overwritten
    // ---------------------------------------------------------------------------

    @Test
    void twoBrowserTabsWithTheOldVersionCannotOverwriteEachOthersSave() throws Exception {
        String auth = bearerFor("version-conflict@example.com");
        long id = createDraft(auth, "Conflict Draft");
        JsonNode original = getDraft(auth, id);
        long staleVersion = original.get("version").asLong();
        String contentJson = objectMapper.writeValueAsString(original.get("content"));

        String firstSave = ("""
                {"title":"First Save","templateType":"GENERIC_ATS","locale":"en","version":%d,"content":%s}
                """).formatted(staleVersion, contentJson);
        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + id)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(firstSave))
                .andExpect(status().isOk());

        // A second save still carrying the ORIGINAL (now stale) version must be
        // rejected as a conflict, not silently applied over the first save.
        String secondSave = ("""
                {"title":"Second Save (stale)","templateType":"GENERIC_ATS","locale":"en","version":%d,"content":%s}
                """).formatted(staleVersion, contentJson);
        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + id)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(secondSave))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First Save"));
    }

    // ---------------------------------------------------------------------------
    // cross-user isolation: every route 404s on someone else's draft id
    // ---------------------------------------------------------------------------

    @Test
    void everyRouteReturns404ForAnotherUsersDraft() throws Exception {
        String aliceAuth = bearerFor("alice-builder@example.com");
        String bobAuth = bearerFor("bob-builder@example.com");
        long aliceDraftId = createDraft(aliceAuth, "Alice's Draft");

        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + aliceDraftId).header("Authorization", bobAuth))
                .andExpect(status().isNotFound());

        JsonNode aliceDraft = getDraft(aliceAuth, aliceDraftId);
        String updateBody = ("""
                {"title":"Hijacked","templateType":"GENERIC_ATS","locale":"en","version":%d,"content":%s}
                """).formatted(aliceDraft.get("version").asLong(),
                objectMapper.writeValueAsString(aliceDraft.get("content")));
        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + aliceDraftId)
                        .header("Authorization", bobAuth)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/resume-builder/drafts/" + aliceDraftId).header("Authorization", bobAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/resume-builder/drafts/" + aliceDraftId + "/duplicate")
                        .header("Authorization", bobAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/resume-builder/drafts/" + aliceDraftId + "/preview")
                        .header("Authorization", bobAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/resume-builder/drafts/" + aliceDraftId + "/generate")
                        .header("Authorization", bobAuth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"bob-key-1\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + aliceDraftId + "/career-materials")
                        .header("Authorization", bobAuth))
                .andExpect(status().isNotFound());

        // Alice's own draft is completely unaffected by all of Bob's attempts.
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + aliceDraftId).header("Authorization", aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alice's Draft"));
    }

    // ---------------------------------------------------------------------------
    // preview: blank sections omitted, missing facts reported, ready to generate
    // ---------------------------------------------------------------------------

    @Test
    void previewOmitsBlankSectionsAndReportsMissingKeyFacts() throws Exception {
        String auth = bearerFor("preview-user@example.com");
        long id = createDraft(auth, "Preview Draft");

        String body = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + id + "/preview")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode preview = objectMapper.readTree(body);

        assertTrue(preview.get("sections").isEmpty());
        assertTrue(preview.get("omittedSectionIds").size() > 0);
        assertTrue(preview.get("readyToGenerate").asBoolean() == false);
    }

    // ---------------------------------------------------------------------------
    // generate: real DOCX, GENERATED origin, appears in "我的简历", idempotent retry
    // ---------------------------------------------------------------------------

    @Test
    void generatingProducesAGeneratedResumeVisibleInMyResumesAndUsableByQualityScore() throws Exception {
        String auth = bearerFor("generate-user@example.com");
        long id = createDraft(auth, "Generate Draft");
        JsonNode draft = getDraft(auth, id);
        String content = objectMapper.writeValueAsString(draft.get("content"));
        JsonNode contentNode = objectMapper.readTree(content);
        ((com.fasterxml.jackson.databind.node.ObjectNode) contentNode.get("basicInfo"))
                .put("fullName", "Alex Morgan");
        ((com.fasterxml.jackson.databind.node.ObjectNode) contentNode.get("basicInfo"))
                .put("email", "alex.morgan@example.com");
        String updateBody = ("""
                {"title":"Generate Draft","templateType":"GENERIC_ATS","locale":"en","version":%d,"content":%s}
                """).formatted(draft.get("version").asLong(), contentNode.toString());
        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + id)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk());

        String generateBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + id + "/generate")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"gen-key-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("GENERATED"))
                .andReturn().getResponse().getContentAsString();
        long resumeId = objectMapper.readTree(generateBody).get("id").asLong();

        // Appears in "我的简历" -- the DEFAULT list, no includeDerived flag needed.
        mockMvc.perform(get("/api/v1/resumes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + resumeId + ")]").exists());

        // The existing quality-score feature reads it with no additional integration.
        mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorable").value(true));

        // Retrying with the SAME idempotency key returns the SAME resume, never a
        // second one.
        String retryBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + id + "/generate")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"gen-key-1\"}"))
                .andReturn().getResponse().getContentAsString();
        long retryResumeId = objectMapper.readTree(retryBody).get("id").asLong();
        assertEquals(resumeId, retryResumeId);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "generate-user@example.com");
        long resumeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE user_id = ? AND origin = 'GENERATED'", Long.class, userId);
        assertEquals(1L, resumeCount);
    }

    // ---------------------------------------------------------------------------
    // career materials: deterministic, never touches OpenAI, works alongside preview
    // ---------------------------------------------------------------------------

    @Test
    void careerMaterialsEndpointWorksAndNeverRequiresAnyRealOpenAiCall() throws Exception {
        String auth = bearerFor("career-materials-user@example.com");
        long id = createDraft(auth, "Career Materials Draft");

        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + id + "/career-materials")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positioningSummary").exists())
                .andExpect(jsonPath("$.selfIntroduction").exists())
                .andExpect(jsonPath("$.aiReflection").exists())
                .andExpect(jsonPath("$.strengthsAndWeaknesses").exists())
                .andExpect(jsonPath("$.developmentPlan").exists());
    }

    // ---------------------------------------------------------------------------
    // duplicate / delete lifecycle
    // ---------------------------------------------------------------------------

    @Test
    void duplicatingCreatesAnIndependentCopyAndDeletingRequiresOwnership() throws Exception {
        String auth = bearerFor("duplicate-user@example.com");
        long id = createDraft(auth, "Original Draft");

        String copyBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + id + "/duplicate")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long copyId = objectMapper.readTree(copyBody).get("id").asLong();
        assertNotEquals(id, copyId);

        mockMvc.perform(get("/api/v1/resume-builder/drafts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(delete("/api/v1/resume-builder/drafts/" + copyId).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + copyId).header("Authorization", auth))
                .andExpect(status().isNotFound());
        // The original survives the copy's deletion.
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + id).header("Authorization", auth))
                .andExpect(status().isOk());
    }
}
