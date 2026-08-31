package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.dto.AnswerEnhancedQuestionRequest;
import aijob.dto.AnswerEnhancedQuestionResponse;
import aijob.dto.AnswerStatus;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.EditedTailorChange;
import aijob.dto.EditedTailorEntry;
import aijob.dto.RequirementCategory;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.V3EntryReview;
import aijob.dto.V3ReviewNode;
import aijob.dto.V3SkillReview;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.enums.PdfExportStatus;
import aijob.enums.TailorMode;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.ResumeEvidenceService;
import aijob.tailor.v3.TailorEngineRouter;
import aijob.tailor.v3.V3BasicLiveQualityAcceptanceTest;
import aijob.tailor.v3.V3EnhancedOpportunityService;
import aijob.tailor.v3.V3EnhancedQuestion;
import aijob.tailor.v3.V3EnhancedTailorService;
import aijob.tailor.v3.V3EntryRewriteRequest;
import aijob.tailor.v3.V3EntryRewriteResult;
import aijob.tailor.v3.V3OpportunityMap;
import aijob.tailor.v3.V3RewriteTestSupport;
import aijob.config.TailorEngineProperties;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceImplV3EnhancedTest {

    private static final String EMAIL = "user@example.com";
    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private AiResumeService aiResumeService;
    @Mock private ResumeDocumentService resumeDocumentService;
    @Mock private ResumeDocxTailorService resumeDocxTailorService;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private GreenhouseJobClient greenhouseJobClient;
    @Mock private PdfExportService pdfExportService;
    @Mock private ResumeAnalysisInputFactory resumeAnalysisInputFactory;
    @Mock private ResumeEvidenceService resumeEvidenceService;

    @TempDir
    Path tempDir;

    private JobServiceImpl jobService;
    private Path childDocx;
    private Path generatedDocx;
    private Path generatedPdf;
    private MaintainAwareGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        childDocx = tempDir.resolve("canonical.docx");
        generatedDocx = tempDir.resolve("out.docx");
        generatedPdf = tempDir.resolve("out.pdf");
        Files.writeString(childDocx, "docx");
        Files.writeString(generatedDocx, "generated");
        Files.writeString(generatedPdf, "pdf-out");
        generator = new MaintainAwareGenerator();
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V3");
        jobService = new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                objectMapper,
                greenhouseJobClient,
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
                new V3EnhancedTailorService(generator, V3RewriteTestSupport.acceptBestAi()),
                resumeEvidenceService
        );
        stubOwner();
    }

    @Test
    void scanAndAnswer_confirmedUsedAttachesOnlyToPrime() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of());
        V3EnhancedQuestion maintain = map.questions().stream()
                .filter(question -> "maintain".equals(question.topic()))
                .findFirst()
                .orElseThrow();
        assertEquals(PRIME, maintain.canonicalEntryKey());
        ResumeEvidenceItemResponse stored = evidence(41L, PRIME, AnswerStatus.CONFIRMED_USED,
                "maintained the AWS application with weekly patching");
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(stored, true));

        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId(maintain.id());
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("maintained the AWS application with weekly patching");
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);

        assertEquals(PRIME, answered.getCanonicalEntryKey());
        assertEquals(41L, answered.getEvidence().getId());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(3L), captor.capture());
        assertEquals(PRIME, captor.getValue().getEntryKey());
        assertEquals("maintain", captor.getValue().getTopic());
        assertEquals(AnswerStatus.CONFIRMED_USED, captor.getValue().getAnswerStatus());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
    }

    @Test
    void primeMetricConfirmedUsedSavesCanonicalMetricTopic() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(
                        evidence(50L, PRIME, AnswerStatus.CONFIRMED_USED,
                                "handled about 2000 daily checkout users after launch"),
                        true));
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId("eq-PROJECT-prime-deals-metric");
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("handled about 2000 daily checkout users after launch");
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);

        assertEquals(PRIME, answered.getCanonicalEntryKey());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(3L), captor.capture());
        assertEquals("metric", captor.getValue().getTopic());
        assertEquals(RequirementCategory.RESPONSIBILITY, captor.getValue().getCategory());
        assertEquals(PRIME, captor.getValue().getEntryKey());
        assertEquals(AnswerStatus.CONFIRMED_USED, captor.getValue().getAnswerStatus());
        assertFalse(captor.getValue().getTopic().toLowerCase(Locale.ROOT).contains("confirmable"));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
        verify(pdfExportService, never()).exportPdf(any());
    }

    @Test
    void smartPcMetricConfirmedUsedSavesCanonicalMetricTopic() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(
                        evidence(51L, SMART, AnswerStatus.CONFIRMED_USED,
                                "compared about 120 component combinations per budget"),
                        true));
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId("eq-PROJECT-smartpc-builder-metric");
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("compared about 120 component combinations per budget");
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);

        assertEquals(SMART, answered.getCanonicalEntryKey());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(3L), captor.capture());
        assertEquals("metric", captor.getValue().getTopic());
        assertEquals(SMART, captor.getValue().getEntryKey());
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
    }

    @Test
    void metricConfirmedNotUsedWritesRejectionWithoutOpenAi() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        ResumeEvidenceItemResponse stored = evidence(52L, PRIME, AnswerStatus.CONFIRMED_NOT_USED, "");
        stored.setProvenance("USER_REJECTED");
        stored.setTopic("metric");
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(stored, true));
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId("eq-PROJECT-prime-deals-metric");
        request.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);

        assertEquals(AnswerStatus.CONFIRMED_NOT_USED, answered.getAnswerStatus());
        assertEquals("USER_REJECTED", answered.getEvidence().getProvenance());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(3L), captor.capture());
        assertEquals("metric", captor.getValue().getTopic());
        assertEquals(AnswerStatus.CONFIRMED_NOT_USED, captor.getValue().getAnswerStatus());
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void metricWrongTopicAndForgedQuestionIdAreRejected() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        AnswerEnhancedQuestionRequest forged = new AnswerEnhancedQuestionRequest();
        forged.setQuestionId("eq-PROJECT-prime-deals-performance");
        forged.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        forged.setAnswerText("handled about 2000 daily checkout users after launch");
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, forged));

        AnswerEnhancedQuestionRequest english = new AnswerEnhancedQuestionRequest();
        english.setQuestionId("A confirmable performance or volume figure");
        english.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        english.setAnswerText("handled about 2000 daily checkout users after launch");
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, english));
        verify(resumeEvidenceService, never()).save(any(), any(), any());
    }

    @Test
    void pdfParentCanonicalChildAnswersMetricOnRequestedResumeAndPrimeEntry() {
        Resume parent = resume(11L, "pdf", tempDir.resolve("cv.pdf"));
        parent.setOrigin("UPLOAD");
        Resume child = resume(99L, "docx", childDocx);
        child.setOrigin("TEMPLATE");
        child.setParentResumeId(11L);
        when(resumeMapper.findById(11L)).thenReturn(parent);
        when(resumeMapper.findById(99L)).thenReturn(child);
        when(resumeAnalysisInputFactory.resolve(child, 1L)).thenReturn(new ResumeAnalysisInput(
                child, parent, child, List.of(), Map.of(), null, 11L));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(primeAndSmart());
        when(resumeEvidenceService.list(EMAIL, 99L)).thenReturn(List.of());
        when(resumeEvidenceService.save(eq(EMAIL), eq(99L), any())).thenReturn(
                new ResumeEvidenceService.SaveResult(
                        evidence(53L, PRIME, AnswerStatus.CONFIRMED_USED,
                                "handled about 2000 daily checkout users after launch"),
                        true));

        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId("eq-PROJECT-prime-deals-metric");
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("handled about 2000 daily checkout users after launch");
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 99L, request);

        assertEquals(PRIME, answered.getCanonicalEntryKey());
        ArgumentCaptor<SaveResumeEvidenceRequest> captor = ArgumentCaptor.forClass(SaveResumeEvidenceRequest.class);
        verify(resumeEvidenceService).save(eq(EMAIL), eq(99L), captor.capture());
        assertEquals("metric", captor.getValue().getTopic());
        assertEquals(PRIME, captor.getValue().getEntryKey());
        verify(resumeDocumentService).parseDocx(childDocx);
        verify(aiResumeService, never()).generateTailorChanges(any(), any(), any());
    }

    @Test
    void skippedAnswerDoesNotWriteEvidenceAndCanContinue() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of());
        String skipped = map.questions().get(0).id();
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId(skipped);
        request.setAnswerStatus("SKIPPED");
        AnswerEnhancedQuestionResponse answered = jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);
        assertTrue(answered.isSkipped());
        verify(resumeEvidenceService, never()).save(any(), any(), any());
        V3OpportunityMap continued = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of(skipped));
        assertTrue(continued.questions().stream().noneMatch(question -> skipped.equals(question.id())));
        assertFalse(continued.questions().isEmpty());
    }

    @Test
    void forgedQuestionWrongUserAndWrongResumeAreRejected() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        AnswerEnhancedQuestionRequest forged = new AnswerEnhancedQuestionRequest();
        forged.setQuestionId("eq-PROJECT-prime-deals-docker");
        forged.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        forged.setAnswerText("used docker in Prime Deals for local packaging");
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, forged));

        when(userMapper.findByEmail("other@example.com")).thenReturn(null);
        AnswerEnhancedQuestionRequest ok = new AnswerEnhancedQuestionRequest();
        ok.setQuestionId("eq-PROJECT-prime-deals-maintain");
        ok.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        ok.setAnswerText("applied weekly security patches after deploy");
        assertThrows(UserNotFoundException.class,
                () -> jobService.answerEnhancedQuestion("other@example.com", 10L, 3L, ok));

        Resume other = resume(99L, "docx", childDocx);
        other.setUserId(9L);
        when(resumeMapper.findById(99L)).thenReturn(other);
        assertThrows(ResumeNotFoundException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 99L, ok));
        verify(resumeEvidenceService, never()).save(any(), any(), any());
    }

    @Test
    void answerRejectedWhenJobBelongsToAnotherUser() {
        stubDocxParse();
        Job foreign = new Job();
        foreign.setId(88L);
        foreign.setUserId(2L);
        foreign.setDescription(V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        when(jobMapper.findById(88L)).thenReturn(foreign);
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId("eq-PROJECT-prime-deals-maintain");
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("maintained the AWS application with weekly patching");
        assertThrows(JobNotFoundException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 88L, 3L, request));
        verify(resumeEvidenceService, never()).save(any(), any(), any());
    }

    @Test
    void answeringTheSameQuestionTwiceReusesEvidenceSave() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        when(resumeEvidenceService.save(eq(EMAIL), eq(3L), any())).thenReturn(savedMaintain());
        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of());
        String maintainId = map.questions().stream()
                .filter(question -> "maintain".equals(question.topic()))
                .map(V3EnhancedQuestion::id)
                .findFirst()
                .orElseThrow();
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId(maintainId);
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("maintained the AWS application with weekly patching");
        jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);
        jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);
        verify(resumeEvidenceService, times(2)).save(eq(EMAIL), eq(3L), any());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void getPendingEnhancedProposalReturnsEntryReviews() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        TailoredResumeResponse loaded = jobService.getTailorProposal(EMAIL, 10L, 42L);
        assertEquals("ENHANCED", loaded.getProductMode());
        assertFalse(loaded.getEntryReviews().isEmpty());
        assertTrue(loaded.getEntryReviews().stream().anyMatch(review -> PRIME.equals(review.getCanonicalEntryKey())));
        assertTrue(loaded.getEvidenceUsage() != null && !loaded.getEvidenceUsage().isEmpty());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void yesOnlyMaintainIsRejected() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of());
        String maintainId = map.questions().stream()
                .filter(question -> "maintain".equals(question.topic()))
                .map(V3EnhancedQuestion::id)
                .findFirst()
                .orElseThrow();
        AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
        request.setQuestionId(maintainId);
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setAnswerText("yes");
        assertThrows(InvalidEvidenceRequestException.class,
                () -> jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request));
        verify(resumeEvidenceService, never()).save(any(), any(), any());
    }

    @Test
    void enhancedCreateIsPendingWithoutWriter() throws Exception {
        Path uploads = Path.of("uploads");
        Map<String, String> before = snapshotUploads(uploads);
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(42L);
            return 1;
        });

        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED");

        assertEquals(TailorProposal.STATUS_PENDING, response.getStatus());
        assertEquals("ENHANCED", response.getProductMode());
        assertEquals("V3", response.getEngineVersion());
        assertTrue(response.isHasSuggestions());
        assertEquals(0, response.getAppliedChangeCount());
        assertNull(response.getDownloadUrl());
        assertNull(response.getPdfDownloadUrl());
        assertTrue(response.getEntryReviews().stream().anyMatch(review -> PRIME.equals(review.getCanonicalEntryKey())));
        assertTrue(response.getEvidenceItemIds().contains(41L));
        assertTrue(response.getEvidenceUsage() != null && response.getEvidenceUsage().stream()
                .anyMatch(item -> Long.valueOf(41L).equals(item.getEvidenceItemId())));
        assertEquals(
                response.getEntryReviews().get(0).getOriginalNodes().size(),
                response.getEntryReviews().get(0).getProposedNodes().size());
        for (int i = 0; i < response.getEntryReviews().get(0).getOriginalNodes().size(); i++) {
            assertEquals(
                    response.getEntryReviews().get(0).getOriginalNodes().get(i).getNodeId(),
                    response.getEntryReviews().get(0).getProposedNodes().get(i).getNodeId());
        }
        ArgumentCaptor<TailorProposal> stored = ArgumentCaptor.forClass(TailorProposal.class);
        verify(tailorProposalMapper).insert(stored.capture());
        TailorProposalState state = TailorProposalState.parse(objectMapper, stored.getValue().getChangesJson());
        assertEquals("ENHANCED", state.getProductMode());
        assertEquals(state.getChanges().get(0).getNodeId(),
                state.getEntryReviews().get(0).getProposedNodes().stream()
                        .filter(node -> node.getNodeId().equals(state.getChanges().get(0).getNodeId()))
                        .findFirst()
                        .orElseThrow()
                        .getNodeId());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(pdfExportService, never()).exportPdf(any());
        assertEquals(before, snapshotUploads(uploads));
        assertTrue(generator.lastProductModeEnhanced);
        assertFalse(generator.usedSmartPcMaintain);
    }

    @Test
    void rejectedMaintainDoesNotEnterProposal() {
        stubDocxParse();
        ResumeEvidenceItemResponse rejected = evidence(42L, PRIME, AnswerStatus.CONFIRMED_NOT_USED, "maintain");
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(rejected));
        generator.forceMaintainOnPrime = true;
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(7L);
            return 1;
        });
        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED");
        assertTrue(response.getChanges() == null || response.getChanges().isEmpty()
                || response.getChanges().stream().noneMatch(change ->
                change.getNewText() != null && change.getNewText().toLowerCase(Locale.ROOT).contains("maintained")));
    }

    @Test
    void smartPcMaintainIsRejectedAsUnsupported() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        generator.forceMaintainOnSmart = true;
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(8L);
            return 1;
        });
        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED");
        assertTrue(response.getEntryReviews().stream().noneMatch(review -> SMART.equals(review.getCanonicalEntryKey())
                && review.getProposedNodes().stream().anyMatch(node ->
                node.getText() != null && node.getText().toLowerCase(Locale.ROOT).contains("maintained"))));
    }

    @Test
    void noHighQualitySuggestionReturnsEmptyPending() {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of());
        generator.noChange = true;
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(9L);
            return 1;
        });
        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED");
        assertEquals(TailorProposal.STATUS_PENDING, response.getStatus());
        assertFalse(response.isHasSuggestions());
        assertTrue(response.getChanges() == null || response.getChanges().isEmpty());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmPrimeOnlyWritesPrimeAndDoubleClickWritesOnce() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1, 0);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedEntryKeys(List.of(PRIME));
        TailoredResumeResponse confirmed = jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);
        assertEquals(TailorProposal.STATUS_CONFIRMED, confirmed.getStatus());
        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService, times(1)).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        assertTrue(written.getValue().stream().allMatch(change ->
                List.of("p-2", "p-3", "p-4").contains(change.getNodeId())));
        assertTrue(written.getValue().stream().noneMatch(change -> "p-6".equals(change.getNodeId())));
        verify(pdfExportService, times(1)).exportPdf(generatedDocx);

        assertThrows(Exception.class, () -> jobService.confirmTailorProposal(EMAIL, 10L, 42L, request));
        verify(resumeDocxTailorService, times(1)).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmIgnoresPartialSelectedNodeIdsWhenEntryKeysPresent() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        TailorProposalState state = TailorProposalState.parse(objectMapper, stored.getChangesJson());
        List<String> primeChangeIds = state.getChanges() == null
                ? List.of()
                : state.getChanges().stream()
                        .map(TailorChange::getNodeId)
                        .filter(id -> List.of("p-2", "p-3", "p-4").contains(id))
                        .toList();
        assertFalse(primeChangeIds.isEmpty());
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedEntryKeys(List.of(PRIME));
        request.setSelectedNodeIds(List.of(primeChangeIds.get(primeChangeIds.size() - 1)));
        jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);

        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        List<String> writtenIds = written.getValue().stream().map(TailorChange::getNodeId).toList();
        assertTrue(writtenIds.containsAll(primeChangeIds));
        assertTrue(written.getValue().stream().noneMatch(change -> "p-6".equals(change.getNodeId())));
    }

    @Test
    void confirmSkillsOnlyWritesSkillsAndLeavesEntries() throws Exception {
        stubDocxParseWithSkills();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain(), confirmedMysql()));
        TailorProposal stored = captureEnhancedProposal();
        TailorProposalState state = TailorProposalState.parse(objectMapper, stored.getChangesJson());
        assertFalse(state.getSkillReviews().isEmpty());
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedSkillReviewIds(List.of(V3SkillReview.REVIEW_ID));
        TailoredResumeResponse confirmed = jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);
        assertEquals(TailorProposal.STATUS_CONFIRMED, confirmed.getStatus());
        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService, times(1)).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        verify(pdfExportService, times(1)).exportPdf(generatedDocx);
        assertTrue(written.getValue().stream().anyMatch(change -> "p-s1".equals(change.getNodeId())));
        assertTrue(written.getValue().stream().noneMatch(change -> "p-2".equals(change.getNodeId())));
        assertTrue(written.getValue().stream().noneMatch(change -> "p-4".equals(change.getNodeId())));
    }

    @Test
    void confirmEntryOnlyDoesNotWriteSkills() throws Exception {
        stubDocxParseWithSkills();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain(), confirmedMysql()));
        TailorProposal stored = captureEnhancedProposal();
        TailorProposalState state = TailorProposalState.parse(objectMapper, stored.getChangesJson());
        assertTrue(state.getEntryReviews().stream().anyMatch(review -> PRIME.equals(review.getCanonicalEntryKey())));
        assertFalse(state.getSkillReviews().isEmpty());
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedEntryKeys(List.of(PRIME));
        jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);
        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        assertTrue(written.getValue().stream().anyMatch(change -> "p-4".equals(change.getNodeId())));
        assertTrue(written.getValue().stream().noneMatch(change -> "p-s1".equals(change.getNodeId())));
    }

    @Test
    void confirmBothWritesEntryAndSkills() throws Exception {
        stubDocxParseWithSkills();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain(), confirmedMysql()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedEntryKeys(List.of(PRIME));
        request.setSelectedSkillReviewIds(List.of(V3SkillReview.REVIEW_ID));
        jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);
        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService, times(1)).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        verify(pdfExportService, times(1)).exportPdf(generatedDocx);
        List<String> writtenIds = written.getValue().stream().map(TailorChange::getNodeId).toList();
        assertTrue(writtenIds.contains("p-s1"));
    }

    @Test
    void confirmAllCancelledDoesNotWrite() throws Exception {
        stubDocxParseWithSkills();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain(), confirmedMysql()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        ConfirmTailorProposalRequest empty = new ConfirmTailorProposalRequest();
        empty.setSelectedEntryKeys(List.of());
        empty.setSelectedSkillReviewIds(List.of());
        empty.setSelectedNodeIds(List.of());
        assertThrows(InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 42L, empty));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void emptySelectionAndHeadingEditAreRejectedWithoutWriter() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);

        ConfirmTailorProposalRequest empty = new ConfirmTailorProposalRequest();
        empty.setSelectedEntryKeys(List.of());
        empty.setSelectedNodeIds(List.of());
        assertThrows(InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 42L, empty));
        verify(tailorProposalMapper, never()).claimPending(any(), any(), any());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());

        ConfirmTailorProposalRequest heading = new ConfirmTailorProposalRequest();
        heading.setSelectedEntryKeys(List.of(PRIME));
        EditedTailorEntry edited = new EditedTailorEntry();
        edited.setCanonicalEntryKey(PRIME);
        edited.setNodes(List.of(new EditedTailorChange("p-1", "Renamed heading")));
        heading.setEditedEntries(List.of(edited));
        assertThrows(InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 42L, heading));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void userEditedEntryCanConfirm() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        ConfirmTailorProposalRequest request = new ConfirmTailorProposalRequest();
        request.setSelectedEntryKeys(List.of(PRIME));
        EditedTailorEntry edited = new EditedTailorEntry();
        edited.setCanonicalEntryKey(PRIME);
        edited.setNodes(List.of(new EditedTailorChange(
                "p-4",
                "Deployed and maintained the initial version of the project on AWS with weekly patching.")));
        request.setEditedEntries(List.of(edited));
        TailoredResumeResponse confirmed = jobService.confirmTailorProposal(EMAIL, 10L, 42L, request);
        assertEquals(TailorProposal.STATUS_CONFIRMED, confirmed.getStatus());
        ArgumentCaptor<List<TailorChange>> written = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(eq(childDocx), written.capture(), eq(1L), eq(10L));
        assertTrue(written.getValue().stream().anyMatch(change ->
                TailorChange.EVIDENCE_USER_EDITED.equals(change.getEvidenceType())
                        && change.getNewText().contains("weekly patching")));
    }

    @Test
    void continueIsRejectedForEnhanced() throws Exception {
        stubDocxParse();
        when(resumeEvidenceService.list(EMAIL, 3L)).thenReturn(List.of(confirmedMaintain()));
        TailorProposal stored = captureEnhancedProposal();
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        assertThrows(InvalidTailorProposalException.class,
                () -> jobService.continueTailorProposal(EMAIL, 10L, 42L));
        verify(tailorProposalMapper).claimPending(42L, 1L, 10L);
        verify(tailorProposalMapper).restorePending(42L);
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
    }

    @Test
    void missingProductModeKeepsV2CreateProposal() {
        when(resumeMapper.findById(3L)).thenReturn(resume(3L, "docx", childDocx));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH",
                        "Developed and integrated IP rate-limiting features.")));
        org.mockito.Mockito.lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> aijob.util.ConservativeLanguageJudge.passthroughRanking(
                        invocation.getArgument(3)));
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(1L);
            return 1;
        });
        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L);
        assertNull(response.getProductMode());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    private TailorProposal captureEnhancedProposal() throws Exception {
        ArgumentCaptor<TailorProposal> captor = ArgumentCaptor.forClass(TailorProposal.class);
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(42L);
            return 1;
        });
        jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED");
        verify(tailorProposalMapper).insert(captor.capture());
        TailorProposal stored = captor.getValue();
        stored.setId(42L);
        stored.setStatus(TailorProposal.STATUS_PENDING);
        stored.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(12));
        return stored;
    }

    private void stubOwner() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription(V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        when(jobMapper.findById(10L)).thenReturn(job);
    }

    private void stubDocxParse() {
        Resume selected = resume(3L, "docx", childDocx);
        when(resumeMapper.findById(3L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, null, List.of(), Map.of(), null, 3L));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(primeAndSmart());
    }

    private void stubDocxParseWithSkills() {
        Resume selected = resume(3L, "docx", childDocx);
        when(resumeMapper.findById(3L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, null, List.of(), Map.of(), null, 3L));
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(new ResumeDocumentNode("p-s0", "PARAGRAPH", "TECHNICAL SKILLS"));
        nodes.add(new ResumeDocumentNode("p-s1", "PARAGRAPH", "Java, Python, SQL"));
        nodes.addAll(primeAndSmart());
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(nodes);
    }

    private static Resume resume(Long id, String type, Path path) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(1L);
        resume.setFileType(type);
        resume.setFilePath(path.toString());
        return resume;
    }

    private static ResumeEvidenceItemResponse confirmedMaintain() {
        return evidence(41L, PRIME, AnswerStatus.CONFIRMED_USED,
                "maintained the AWS application with weekly patching");
    }

    private static ResumeEvidenceService.SaveResult savedMaintain() {
        return new ResumeEvidenceService.SaveResult(confirmedMaintain(), false);
    }

    private static ResumeEvidenceItemResponse confirmedMysql() {
        ResumeEvidenceItemResponse item = evidence(21L, PRIME, AnswerStatus.CONFIRMED_USED,
                "Used MySQL to store product and cart data in Prime Deals");
        item.setTopic("data-store");
        item.setTopicKey("data-store");
        return item;
    }

    private static ResumeEvidenceItemResponse evidence(
            Long id, String entryKey, String status, String action) {
        ResumeEvidenceItemResponse item = new ResumeEvidenceItemResponse();
        item.setId(id);
        item.setResumeId(3L);
        item.setEntryKey(entryKey);
        item.setAnswerStatus(status);
        item.setAction(action);
        item.setTopic("maintain");
        return item;
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

    private static final class MaintainAwareGenerator implements aijob.tailor.v3.V3EnhancedEntryRewriteGenerator {
        private boolean lastProductModeEnhanced;
        private boolean usedSmartPcMaintain;
        private boolean forceMaintainOnPrime;
        private boolean forceMaintainOnSmart;
        private boolean noChange;

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            lastProductModeEnhanced = request.productMode() == aijob.tailor.v3.V3ProductMode.ENHANCED;
            if (noChange) {
                return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
            }
            List<String> bullets = new ArrayList<>();
            for (var node : request.writableNodes()) {
                String text = node.originalText();
                if (PRIME.equals(request.canonicalEntryKey())
                        && text.toLowerCase(Locale.ROOT).contains("deployed")) {
                    boolean confirmed = request.userConfirmedFacts().stream().anyMatch(item ->
                            item.combinedText().toLowerCase(Locale.ROOT).contains("maintain"));
                    boolean rejected = request.rejectedFacts().stream().anyMatch(item ->
                            item.combinedText().toLowerCase(Locale.ROOT).contains("maintain"));
                    if ((confirmed || forceMaintainOnPrime) && !rejected) {
                        text = text.replace("Successfully ", "")
                                .replace("successfully ", "")
                                .replace("Deployed ", "Deployed and maintained ")
                                .replace("deployed ", "deployed and maintained ");
                        if (!text.toLowerCase(Locale.ROOT).contains("weekly")) {
                            text = text.replace("AWS", "AWS with weekly patching");
                        }
                    }
                }
                if (SMART.equals(request.canonicalEntryKey()) && forceMaintainOnSmart) {
                    usedSmartPcMaintain = true;
                    text = "Developed and maintained a Java Maven project on AWS";
                }
                bullets.add(text);
            }
            return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
        }
    }
}
