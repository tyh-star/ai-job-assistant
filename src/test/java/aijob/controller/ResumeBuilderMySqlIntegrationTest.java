package aijob.controller;

import aijob.AiJobApplication;
import aijob.dto.JobMatchResponse;
import aijob.security.JwtUtil;
import aijob.service.AiResumeService;
import aijob.service.ResumeStructureLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用简历生成器 against a REAL MySQL 8.4, on the same isolated {@code aijob_import_test}
 * database the sibling MySQL suites use -- schema built from the exact files a real
 * release applies (including {@code schema/resume_builder.sql}, chained into {@code
 * infra/aws/release.sh}), not H2 and not a hand-written approximation.
 * <p>
 * Unlike the sibling MySQL suites, this class NEVER truncates -- every test creates its
 * own uniquely-emailed user and cleans up only the rows that user's own actions
 * created, via targeted DELETEs in {@link #cleanUp()}, so the shared database is safe
 * to run this against repeatedly without disturbing anything else in it.
 * <p>
 * {@link AiResumeService} and {@link ResumeStructureLlmService} are {@code @MockBean}
 * here specifically so "never calls OpenAI" is an executable, verified claim
 * ({@code verifyNoInteractions}) against the REAL Spring context and REAL database --
 * not merely inferred from ResumeBuilderServiceImpl's constructor not listing them (see
 * that class and CareerMaterialsGeneratorTest for the structural argument; this is the
 * end-to-end confirmation of it). The one legitimate call to {@code AiResumeService} in
 * this file is the existing, unrelated Match feature, which is deliberately exercised
 * separately AFTER that verification to prove the distinction.
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
                "aijob.storage.root=target/aijob-resume-builder-mysql-test-uploads"
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AIJOB_MYSQL_TEST_URL", matches = ".+",
        disabledReason = "set AIJOB_MYSQL_TEST_URL/USERNAME/PASSWORD (see infra/mysql-test) "
                + "to run against an isolated MySQL 8.4")
class ResumeBuilderMySqlIntegrationTest {

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

    @MockBean
    private AiResumeService aiResumeService;
    @MockBean
    private ResumeStructureLlmService resumeStructureLlmService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        String url = System.getenv(URL_VARIABLE);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "set " + URL_VARIABLE + " to an ISOLATED MySQL 8.4 test database to run this");
        assertTrue(url.startsWith("jdbc:mysql:"), "not a MySQL connection string");

        verifyServerIsSafeToUse();
        applyProductionSchema();
    }

    @AfterEach
    void cleanUp() {
        // Targeted DELETEs scoped to users THIS test run created -- never TRUNCATE, and
        // everything else already in the shared database is left completely alone.
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM resume_builder_generation_requests WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM resume_builder_drafts WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM resumes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM jobs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    /** Same four checks as the sibling MySQL suites -- see their javadoc for why each
     * one matters: real 8.4, really the isolated schema, no server-wide privileges, and
     * no table here this project does not own. */
    private void verifyServerIsSafeToUse() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith(REQUIRED_SERVER_VERSION_PREFIX),
                "expected MySQL " + REQUIRED_SERVER_VERSION_PREFIX + ", server reports " + version);

        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals(REQUIRED_SCHEMA, schema,
                "refusing to run: connected to schema '" + schema + "', expected exactly '" + REQUIRED_SCHEMA + "'");

        List<String> grants = jdbcTemplate.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class);
        boolean onTestSchema = grants.stream().anyMatch(g -> g.contains("`" + REQUIRED_SCHEMA + "`.*"));
        assertTrue(onTestSchema, "the account has no grant on `" + REQUIRED_SCHEMA + "`.*; its grants are " + grants);
        List<String> global = grants.stream()
                .filter(g -> g.contains(" ON *.*"))
                .filter(g -> !g.startsWith("GRANT USAGE ON *.*"))
                .toList();
        assertTrue(global.isEmpty(), "refusing to run: the account holds server-wide privileges " + global);

        List<String> unexpected = jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.TABLES WHERE table_schema = DATABASE()", String.class)
                .stream()
                .filter(t -> !OWNED_TABLES.contains(t.toLowerCase(Locale.ROOT)))
                .toList();
        assertTrue(unexpected.isEmpty(), "refusing to run: '" + schema + "' holds tables this project does not own " + unexpected);
    }

    /** Built from the same files infra/aws/release.sh applies, in the same order --
     * every file is idempotent (CREATE TABLE IF NOT EXISTS / gated ALTERs), so re-running
     * this against the already-migrated shared database is harmless. */
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

    private long createUser() {
        String email = "resume-builder-mysql-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, "not-a-real-hash", "MySQL Resume Builder Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        // Counts below are per user_id. If an earlier TRUNCATE of users left child rows
        // behind, a reused id would inherit them and those counts would be wrong -- fail
        // loudly instead of miscounting. Checked before registering the id, so cleanUp()
        // never deletes rows this test did not create.
        for (String table : List.of("resumes", "jobs", "resume_builder_drafts", "resume_builder_generation_requests")) {
            Long stale = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
            assertEquals(0L, stale, "new user id " + userId + " already owns rows in " + table
                    + " left by an earlier TRUNCATE of users; the database is not isolated");
        }
        createdUserIds.add(userId);
        return userId;
    }

    private String bearerFor(long userId) {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }

    private long createDraft(String auth) throws Exception {
        String body = mockMvc.perform(post("/api/v1/resume-builder/drafts")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"title\":\"MySQL Test Draft\",\"templateType\":\"GENERIC_ATS\",\"locale\":\"en\"}"))
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

    /** Alex Morgan is entirely fictional -- see the resume-builder feature spec's
     * absolute prohibition on real personal data in automated tests. */
    private void fillWithFictionalFacts(String auth, long draftId) throws Exception {
        JsonNode draft = getDraft(auth, draftId);
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) draft.get("content");
        var basicInfo = (com.fasterxml.jackson.databind.node.ObjectNode) content.get("basicInfo");
        basicInfo.put("fullName", "Alex Morgan");
        basicInfo.put("email", "alex.morgan@example.com");
        var jobTarget = (com.fasterxml.jackson.databind.node.ObjectNode) content.get("jobTarget");
        jobTarget.put("targetTitle", "Backend Engineer");

        com.fasterxml.jackson.databind.node.ArrayNode experience = objectMapper.createArrayNode();
        var exp = objectMapper.createObjectNode();
        exp.put("company", "Example Company");
        exp.put("title", "Software Engineer");
        exp.put("location", "");
        exp.put("startDate", "2022-01");
        exp.put("endDate", "2023-01");
        exp.put("current", false);
        exp.set("responsibilities", objectMapper.createArrayNode().add("Built a payments service"));
        exp.set("toolsUsed", objectMapper.createArrayNode());
        exp.set("outcomes", objectMapper.createArrayNode());
        experience.add(exp);
        content.set("experience", experience);

        com.fasterxml.jackson.databind.node.ArrayNode projects = objectMapper.createArrayNode();
        var project = objectMapper.createObjectNode();
        project.put("name", "Project Atlas");
        project.put("purpose", "Internal tooling");
        project.put("startDate", "");
        project.put("endDate", "");
        project.put("actualDuration", "");
        project.set("techStack", objectMapper.createArrayNode());
        project.put("role", "");
        project.putNull("teamSize");
        project.set("features", objectMapper.createArrayNode().add("Search").add("Export"));
        project.set("challenges", objectMapper.createArrayNode());
        project.set("solutions", objectMapper.createArrayNode());
        project.putNull("userCount");
        project.set("outcomes", objectMapper.createArrayNode());
        project.put("githubUrl", "");
        project.put("demoUrl", "");
        projects.add(project);
        content.set("projects", projects);

        var finalBody = objectMapper.createObjectNode();
        finalBody.put("title", "MySQL Test Draft");
        finalBody.put("templateType", "GENERIC_ATS");
        finalBody.putNull("targetRole");
        finalBody.put("locale", "en");
        finalBody.set("content", content);
        finalBody.put("version", draft.get("version").asLong());

        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + draftId)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(finalBody.toString()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------
    // item 4: generate -> appears in "我的简历" -> real extractedText -> quality score
    // -> Match ownership flow (mocked AiResumeService, zero real OpenAI) -> delete
    // ---------------------------------------------------------------------------

    @Test
    void generatedResumeIntegratesWithExistingFeaturesAndNeverCallsOpenAi() throws Exception {
        long userId = createUser();
        String auth = bearerFor(userId);
        long draftId = createDraft(auth);
        fillWithFictionalFacts(auth, draftId);

        mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/preview").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToGenerate").value(true));

        String generateBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/generate")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("GENERATED"))
                .andReturn().getResponse().getContentAsString();
        long resumeId = objectMapper.readTree(generateBody).get("id").asLong();

        // Appears in "我的简历" -- the DEFAULT list, no includeDerived needed.
        mockMvc.perform(get("/api/v1/resumes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + resumeId + ")].origin").value("GENERATED"));

        // Real, non-blank extractedText, read directly from the real MySQL row.
        String extractedText = jdbcTemplate.queryForObject(
                "SELECT extracted_text FROM resumes WHERE id = ?", String.class, resumeId);
        assertNotNull(extractedText);
        assertFalse(extractedText.isBlank());
        assertTrue(extractedText.contains("Alex Morgan"));
        assertTrue(extractedText.contains("Example Company"));

        // Quality score succeeds for real.
        mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/quality-score").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorable").value(true))
                .andExpect(jsonPath("$.score").isNumber());

        // Career materials: separate endpoint, deterministic, never in the ATS resume.
        mockMvc.perform(get("/api/v1/resume-builder/drafts/" + draftId + "/career-materials")
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        assertFalse(extractedText.contains("60-second"), "career materials text must never leak into the ATS resume");

        // Up to this exact point, resume-builder's own code path (drafts CRUD, preview,
        // generate, quality-score, career-materials) must NEVER have touched either
        // OpenAI-calling service.
        verifyNoInteractions(aiResumeService);
        verifyNoInteractions(resumeStructureLlmService);

        // Deleting the DRAFT cleans up its generation_request bookkeeping row but
        // leaves the already-generated resume alone.
        mockMvc.perform(delete("/api/v1/resume-builder/drafts/" + draftId).header("Authorization", auth))
                .andExpect(status().isNoContent());
        Long remainingClaims = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_builder_generation_requests WHERE draft_id = ?", Long.class, draftId);
        assertEquals(0L, remainingClaims);
        Long resumeStillPresent = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE id = ?", Long.class, resumeId);
        assertEquals(1L, resumeStillPresent);

        // Match (an existing, unrelated feature): a GENERATED resume enters the SAME
        // ownership/data flow as any other resume. AiResumeService is mocked, so this
        // is a real HTTP + real MySQL round trip with zero real network call to OpenAI.
        when(aiResumeService.matchResumeToJob(anyString(), anyString()))
                .thenReturn(new JobMatchResponse(85, "Strong match", List.of("Java"), List.of(),
                        List.of("Backend experience"), List.of(), List.of()));
        String jobBody = mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"company\":\"Example Company\",\"title\":\"Backend Engineer\","
                                + "\"description\":\"Java, Spring Boot, REST APIs.\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(jobBody).get("id").asLong();

        mockMvc.perform(post("/api/v1/jobs/" + jobId + "/match")
                        .header("Authorization", auth)
                        .param("resumeId", String.valueOf(resumeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchScore").value(85));

        // File + DB cleanup on delete follows the existing resume-deletion rules.
        String filePath = jdbcTemplate.queryForObject(
                "SELECT file_path FROM resumes WHERE id = ?", String.class, resumeId);
        assertTrue(Files.exists(Path.of(filePath)), "file must exist before delete");
        mockMvc.perform(delete("/api/v1/resumes/" + resumeId).header("Authorization", auth))
                .andExpect(status().isNoContent());
        assertFalse(Files.exists(Path.of(filePath)), "file must be removed on delete");
        Long afterDelete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE id = ?", Long.class, resumeId);
        assertEquals(0L, afterDelete);
    }

    // ---------------------------------------------------------------------------
    // item 5: preview and the generated DOCX agree on sections, order, and blank
    // omission -- both are read FROM THE SAME backend NormalizedResume, never
    // re-derived independently by the frontend or by two different code paths here.
    // ---------------------------------------------------------------------------

    @Test
    void previewAndGeneratedDocxAgreeOnSectionsOrderAndBlankOmission() throws Exception {
        long userId = createUser();
        String auth = bearerFor(userId);
        long draftId = createDraft(auth);
        fillWithFictionalFacts(auth, draftId);
        // Education/volunteer/etc. are deliberately left blank by fillWithFictionalFacts.

        String previewBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/preview")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode preview = objectMapper.readTree(previewBody);
        List<String> previewSectionTitles = new ArrayList<>();
        preview.get("sections").forEach(s -> previewSectionTitles.add(s.get("title").asText()));
        assertTrue(previewSectionTitles.contains("Work experience"));
        assertTrue(previewSectionTitles.contains("Projects"));
        assertTrue(preview.get("omittedSectionIds").toString().contains("EDUCATION"));

        String generateBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/generate")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long resumeId = objectMapper.readTree(generateBody).get("id").asLong();

        byte[] docxBytes = mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/download").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String docxText;
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            docxText = doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        }

        // Every section preview says renders actually appears in the file...
        for (String title : previewSectionTitles) {
            assertTrue(docxText.toUpperCase(Locale.ROOT).contains(title.toUpperCase(Locale.ROOT)),
                    "expected '" + title + "' in generated docx");
        }
        // ...and the SAME relative order.
        int experienceIndex = docxText.toUpperCase(Locale.ROOT).indexOf("EXPERIENCE");
        int projectsIndex = docxText.toUpperCase(Locale.ROOT).indexOf("PROJECTS");
        assertTrue(experienceIndex >= 0 && projectsIndex >= 0 && experienceIndex < projectsIndex);
        // ...and what preview omitted (blank Education) never appears in the file either.
        assertFalse(docxText.toUpperCase(Locale.ROOT).contains("EDUCATION"));

        jdbcTemplate.update("DELETE FROM resumes WHERE id = ?", resumeId);
    }

    // ---------------------------------------------------------------------------
    // regression: a template never hides a section the user fills in; a section the
    // user turns off stays off
    // ---------------------------------------------------------------------------

    @Test
    void genericAtsProjectsSurviveSaveRereadPreviewAndDocxUntilTheUserHidesThem() throws Exception {
        long userId = createUser();
        String auth = bearerFor(userId);
        long draftId = createDraft(auth); // GENERIC_ATS: PROJECTS is not one of its suggested sections
        assertTrue(getDraft(auth, draftId).at("/content/sectionEnabled/PROJECTS").asBoolean(false),
                "a new GENERIC_ATS draft must not start with PROJECTS hidden");

        fillWithFictionalFacts(auth, draftId);
        JsonNode reread = getDraft(auth, draftId);
        assertEquals("Project Atlas", reread.at("/content/projects/0/name").asText());
        assertTrue(reread.at("/content/sectionEnabled/PROJECTS").asBoolean(false));
        assertTrue(previewSectionTitles(auth, draftId).contains("Projects"));
        assertTrue(generatedDocxText(auth, draftId).contains("Project Atlas"));

        var content = (com.fasterxml.jackson.databind.node.ObjectNode) reread.get("content");
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.get("sectionEnabled")).put("PROJECTS", false);
        var body = objectMapper.createObjectNode();
        body.put("title", "MySQL Test Draft");
        body.put("templateType", "GENERIC_ATS");
        body.putNull("targetRole");
        body.put("locale", "en");
        body.set("content", content);
        body.put("version", reread.get("version").asLong());
        mockMvc.perform(put("/api/v1/resume-builder/drafts/" + draftId)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(body.toString()))
                .andExpect(status().isOk());

        JsonNode hidden = getDraft(auth, draftId);
        assertFalse(hidden.at("/content/sectionEnabled/PROJECTS").asBoolean(true), "the user's choice must be kept");
        assertEquals("Project Atlas", hidden.at("/content/projects/0/name").asText(), "hiding must not delete entries");
        assertFalse(previewSectionTitles(auth, draftId).contains("Projects"));
        assertFalse(generatedDocxText(auth, draftId).contains("Project Atlas"));
    }

    private List<String> previewSectionTitles(String auth, long draftId) throws Exception {
        String previewBody = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/preview")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> titles = new ArrayList<>();
        objectMapper.readTree(previewBody).get("sections").forEach(s -> titles.add(s.get("title").asText()));
        return titles;
    }

    private String generatedDocxText(String auth, long draftId) throws Exception {
        String resumeId = generate(auth, draftId, UUID.randomUUID().toString());
        byte[] docxBytes = mockMvc.perform(get("/api/v1/resumes/" + resumeId + "/download").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            return doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        }
    }

    // ---------------------------------------------------------------------------
    // item 6: idempotency and concurrency against the REAL unique constraint
    // ---------------------------------------------------------------------------

    @Test
    void sameKeyTwiceProducesExactlyOneResumeDifferentKeyProducesADifferentOne() throws Exception {
        long userId = createUser();
        String auth = bearerFor(userId);
        long draftId = createDraft(auth);
        fillWithFictionalFacts(auth, draftId);
        String key1 = UUID.randomUUID().toString();

        String first = generate(auth, draftId, key1);
        String second = generate(auth, draftId, key1);
        assertEquals(first, second, "the same idempotency key must return the SAME resume");

        Long resumeCountForKey1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE user_id = ?", Long.class, userId);
        assertEquals(1L, resumeCountForKey1);
        Long claimCountForKey1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_builder_generation_requests WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key1);
        assertEquals(1L, claimCountForKey1);

        String key2 = UUID.randomUUID().toString();
        String third = generate(auth, draftId, key2);
        assertFalse(third.equals(first), "a DIFFERENT idempotency key must produce a DIFFERENT resume");

        Long totalResumes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE user_id = ?", Long.class, userId);
        assertEquals(2L, totalResumes);
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyLeaveExactlyOneResume() throws Exception {
        long userId = createUser();
        String auth = bearerFor(userId);
        long draftId = createDraft(auth);
        fillWithFictionalFacts(auth, draftId);
        String key = UUID.randomUUID().toString();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicLong[] resumeIds = new AtomicLong[]{new AtomicLong(-1), new AtomicLong(-1)};

        for (int i = 0; i < 2; i++) {
            final int slot = i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    String body = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/generate")
                                    .header("Authorization", auth)
                                    .contentType("application/json")
                                    .content("{\"idempotencyKey\":\"" + key + "\"}"))
                            .andReturn().getResponse().getContentAsString();
                    resumeIds[slot].set(objectMapper.readTree(body).get("id").asLong());
                } catch (Exception ignored) {
                    // recorded as -1 below
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertTrue(resumeIds[0].get() > 0 && resumeIds[1].get() > 0, "both requests must succeed");
        assertEquals(resumeIds[0].get(), resumeIds[1].get(), "racing requests with the same key must agree on one resume");

        Long resumeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resumes WHERE user_id = ?", Long.class, userId);
        assertEquals(1L, resumeCount, "a race must never leave two resumes for the same key");
        Long claimCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_builder_generation_requests WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key);
        assertEquals(1L, claimCount, "a race must never leave two claim rows for the same key");
    }

    private String generate(String auth, long draftId, String key) throws Exception {
        String body = mockMvc.perform(post("/api/v1/resume-builder/drafts/" + draftId + "/generate")
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
