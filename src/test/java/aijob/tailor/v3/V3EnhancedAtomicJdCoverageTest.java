package aijob.tailor.v3;

import aijob.dto.AnswerEnhancedQuestionRequest;
import aijob.dto.AnswerStatus;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.service.ResumeEvidenceService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.util.ClaimLedger;
import aijob.util.ResumeAnalysisInput;
import aijob.util.YaohangCanonicalDocxFixture;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.config.TailorEngineProperties;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.util.ResumeAnalysisInputFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V3EnhancedAtomicJdCoverageTest {

    static final String JD_VIVA = """
            Viva Tech Solutions Software Engineer

            JavaScript skills and API/event contract design.
            Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests).
            Experience with event-driven systems (Kafka or equivalent), including retries, idempotency, and schema/version evolution.
            """;

    private static final String EMAIL = "user@example.com";
    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";
    private static final String JS_CONTRACT = "JavaScript skills and API/event contract design";
    private static final String NX = "Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)";
    private static final String KAFKA = "Experience with event-driven systems (Kafka or equivalent), including retries, idempotency, and schema/version evolution";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private AiResumeService aiResumeService;
    @Mock private ResumeDocumentService resumeDocumentService;
    @Mock private ResumeDocxTailorService resumeDocxTailorService;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private PdfExportService pdfExportService;
    @Mock private ResumeAnalysisInputFactory resumeAnalysisInputFactory;
    @Mock private ResumeEvidenceService resumeEvidenceService;

    @TempDir
    Path tempDir;

    @Test
    void yaohangVivaMapSplitsCompoundRequirementsAndAsksSkillGaps() throws Exception {
        Path uploads = Path.of("uploads");
        Map<String, String> before = snapshotUploads(uploads);
        List<ResumeDocumentNode> nodes = yaohangNodes();
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(nodes, JD_VIVA);
        assertEquals(0, map.writerCalls());
        assertEquals(0, map.openaiCalls());
        assertFalse(map.wroteJobOrResume());
        V3OpportunityEntry prime = entry(map, PRIME);
        V3OpportunityEntry smart = entry(map, SMART);
        V3JdRequirementBreakdown js = requirement(prime, "javascript");
        V3JdRequirementBreakdown nx = requirement(prime, "nx");
        V3JdRequirementBreakdown kafka = requirement(prime, "event-driven");
        assertEquals(V3JdRequirementBreakdown.PARTIALLY_COVERED, js.status());
        assertTrue(js.coveredAtoms().stream().anyMatch(item -> item.equalsIgnoreCase("JavaScript")));
        assertTrue(js.missingAtoms().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("contract")));
        assertFalse(prime.coveredRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("javascript")
                        && item.toLowerCase(Locale.ROOT).contains("contract")));
        assertEquals(V3JdRequirementBreakdown.MISSING, nx.status());
        assertFalse(prime.coveredRequirements().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("nx")));
        assertTrue(prime.missingRequirements().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("nx")));
        assertEquals(V3JdRequirementBreakdown.MISSING, kafka.status());
        assertTrue(prime.missingRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("kafka")));
        assertFalse(prime.canStrengthenWithExistingFacts().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("kafka")
                        || item.toLowerCase(Locale.ROOT).contains("event-driven")));
        assertEquals(V3OpportunityPriority.HIGH, prime.priority());
        assertEquals(V3OpportunityPriority.NO_ACTION, smart.priority());
        List<String> topics = map.questions().stream().map(V3EnhancedQuestion::topic).toList();
        assertTrue(topics.size() >= 2 && topics.size() <= 3, String.valueOf(topics));
        assertTrue(topics.contains(V3EnhancedAtomicCoverage.API_CONTRACT));
        assertTrue(topics.contains(V3EnhancedAtomicCoverage.NX_MONOREPO));
        assertTrue(topics.contains(V3EnhancedAtomicCoverage.EVENT_DRIVEN) || topics.size() == 2);
        for (V3EnhancedQuestion question : map.questions()) {
            assertFalse(question.requirementId().isBlank());
            assertFalse(question.requirementText().isBlank());
            assertTrue(question.allowedEntryKeys().contains(PRIME));
            assertTrue(question.allowedEntryKeys().contains(SMART));
            assertTrue(question.skipAllowed());
            assertFalse(V3EnhancedQuestionPlanner.looksLikeYesNoTrap(question.promptEn()));
            assertFalse(question.promptZh().contains("topicKey"));
            assertFalse(question.promptZh().contains("reasonCode"));
        }
        V3UserEvidence metric = new V3UserEvidence(
                12L, PRIME, AnswerStatus.CONFIRMED_USED,
                "handled about 2000 daily checkout users after launch", "", "", "", "metric");
        V3OpportunityMap afterMetric = new V3EnhancedOpportunityService().scan(nodes, JD_VIVA, List.of(metric));
        assertTrue(afterMetric.questions().stream().anyMatch(question ->
                V3EnhancedAtomicCoverage.NX_MONOREPO.equals(question.topic())));
        assertTrue(afterMetric.questions().stream().anyMatch(question ->
                V3EnhancedAtomicCoverage.API_CONTRACT.equals(question.topic())));
        V3UserEvidence rejectedNx = new V3UserEvidence(
                80L, PRIME, AnswerStatus.CONFIRMED_NOT_USED, "", "", "", "", "nx-monorepo");
        V3OpportunityMap afterReject = new V3EnhancedOpportunityService().scan(nodes, JD_VIVA, List.of(rejectedNx));
        assertTrue(afterReject.questions().stream().noneMatch(question ->
                V3EnhancedAtomicCoverage.NX_MONOREPO.equals(question.topic())));
        V3SkillsProjector.Result rejectedSkills = V3SkillsProjector.project(nodes, List.of(rejectedNx));
        assertTrue(rejectedSkills.reviews().isEmpty()
                || rejectedSkills.reviews().stream().noneMatch(review ->
                review.getAddedSkills() != null && review.getAddedSkills().stream()
                        .anyMatch(skill -> skill.toLowerCase(Locale.ROOT).contains("nx"))));
        V3UserEvidence confirmedNx = new V3UserEvidence(
                81L, SMART, AnswerStatus.CONFIRMED_USED,
                "Used Nx monorepo with shared libraries, dependency graph, and affected builds/tests in SmartPC Builder",
                "", "", "", "nx-monorepo");
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(nodes), List.of(confirmedNx));
        assertTrue(graphs.entry(SMART).factsOf(V3FactKind.USER_CONFIRMED).stream()
                .anyMatch(fact -> Long.valueOf(81L).equals(fact.evidenceItemId())));
        assertTrue(graphs.entry(PRIME).facts().stream()
                .noneMatch(fact -> Long.valueOf(81L).equals(fact.evidenceItemId())));
        V3SkillsProjector.Result skills = V3SkillsProjector.project(nodes, List.of(confirmedNx));
        assertFalse(skills.reviews().isEmpty());
        assertTrue(skills.reviews().get(0).getAddedSkills().stream()
                .anyMatch(skill -> skill.toLowerCase(Locale.ROOT).contains("nx")));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(tempDir.resolve("viva-opportunity.json").toFile(), map);
        assertEquals(before, snapshotUploads(uploads));
    }

    @Test
    void answerNxRejectsBareYesAndForgedKeysThenBindsChosenEntry() throws Exception {
        List<ResumeDocumentNode> nodes = yaohangNodes();
        Path childDocx = tempDir.resolve("canonical.docx");
        Files.writeString(childDocx, "docx");
        JobServiceImpl jobService = jobService(childDocx, nodes);
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of());
        V3EnhancedQuestion nx = map.questions().stream()
                .filter(question -> V3EnhancedAtomicCoverage.NX_MONOREPO.equals(question.topic()))
                .findFirst()
                .orElseThrow();
        AnswerEnhancedQuestionRequest yes = new AnswerEnhancedQuestionRequest();
        yes.setQuestionId(nx.id());
        yes.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        yes.setAnswerText("有");
        yes.setEntryKey(PRIME);
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, yes));
        AnswerEnhancedQuestionRequest forgedQuestion = new AnswerEnhancedQuestionRequest();
        forgedQuestion.setQuestionId("eq-skill-missing-nx-monorepo");
        forgedQuestion.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        forgedQuestion.setAnswerText("Used Nx monorepo with shared libraries in Prime Deals");
        forgedQuestion.setEntryKey(PRIME);
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, forgedQuestion));
        AnswerEnhancedQuestionRequest forgedEntry = new AnswerEnhancedQuestionRequest();
        forgedEntry.setQuestionId(nx.id());
        forgedEntry.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        forgedEntry.setAnswerText("Used Nx monorepo with shared libraries in a made-up project");
        forgedEntry.setEntryKey("PROJECT:does-not-exist");
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, forgedEntry));
        ResumeEvidenceItemResponse stored = new ResumeEvidenceItemResponse();
        stored.setId(81L);
        stored.setEntryKey(SMART);
        stored.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        stored.setAction("Used Nx monorepo with shared libraries in SmartPC Builder");
        stored.setTopic("nx-monorepo");
        stored.setTopicKey("nx-monorepo");
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(stored, true));
        AnswerEnhancedQuestionRequest confirm = new AnswerEnhancedQuestionRequest();
        confirm.setQuestionId(nx.id());
        confirm.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        confirm.setAnswerText("Used Nx monorepo with shared libraries, dependency graph, and affected builds in SmartPC Builder");
        confirm.setEntryKey(SMART);
        var answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, confirm);
        assertEquals(SMART, answered.getCanonicalEntryKey());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(3L), captor.capture());
        assertEquals(SMART, captor.getValue().getEntryKey());
        assertEquals("nx-monorepo", captor.getValue().getTopic());
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());

        ResumeEvidenceItemResponse rejected = new ResumeEvidenceItemResponse();
        rejected.setId(80L);
        rejected.setEntryKey(PRIME);
        rejected.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
        rejected.setTopic("nx-monorepo");
        rejected.setTopicKey("nx-monorepo");
        rejected.setProvenance("USER_REJECTED");
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(rejected, true));
        AnswerEnhancedQuestionRequest notUsed = new AnswerEnhancedQuestionRequest();
        notUsed.setQuestionId(nx.id());
        notUsed.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
        notUsed.setEntryKey(PRIME);
        var rejectedAnswer = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, notUsed);
        assertEquals(AnswerStatus.CONFIRMED_NOT_USED, rejectedAnswer.getAnswerStatus());
        assertEquals("USER_REJECTED", rejected.getProvenance());
    }

    private JobServiceImpl jobService(Path childDocx, List<ResumeDocumentNode> nodes) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V3");
        JobServiceImpl jobService = new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                new ObjectMapper(),
                null,
                pdfExportService,
                StoragePathResolver.forRoot(tempDir.resolve("uploads")),
                null,
                null,
                null,
                JobSearchProperties.defaults(),
                new TailorEngineRouter(new TailorEngineProperties(env)),
                null,
                resumeAnalysisInputFactory,
                new V3EnhancedOpportunityService(),
                new V3EnhancedTailorService(request -> V3EntryRewriteResult.noChange(request.canonicalEntryKey()),
                        V3RewriteTestSupport.acceptBestAi()),
                resumeEvidenceService
        );
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription(JD_VIVA);
        when(jobMapper.findById(10L)).thenReturn(job);
        Resume selected = new Resume();
        selected.setId(3L);
        selected.setUserId(1L);
        selected.setFileType("docx");
        selected.setFilePath(childDocx.toString());
        when(resumeMapper.findById(3L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, null, List.of(), Map.of(), null, 3L));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(nodes);
        return jobService;
    }

    private List<ResumeDocumentNode> yaohangNodes() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, tempDir.resolve("yaohang.docx"));
        return new ResumeDocumentServiceImpl().parseDocx(canonical);
    }

    private static V3OpportunityEntry entry(V3OpportunityMap map, String key) {
        return map.entries().stream()
                .filter(item -> key.equals(item.canonicalEntryKey()))
                .findFirst()
                .orElseThrow();
    }

    private static V3JdRequirementBreakdown requirement(V3OpportunityEntry entry, String needle) {
        return entry.requirements().stream()
                .filter(item -> item.requirementText().toLowerCase(Locale.ROOT).contains(needle))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> snapshotUploads(Path uploads) throws Exception {
        TreeMap<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(uploads)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.walk(uploads)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'), sha256(file));
                } catch (Exception ex) {
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'),
                            "LOCKED:" + ex.getClass().getSimpleName());
                }
            });
        }
        return hashes;
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }
}
