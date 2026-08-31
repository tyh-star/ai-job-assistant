package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.ConservativeRewriteRepair;
import aijob.dto.EditedTailorChange;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpliftType;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.enums.PdfExportStatus;
import aijob.enums.TailorMode;
import aijob.exception.InvalidBulkDeleteException;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.JobNotFoundException;
import aijob.exception.PdfExportException;
import aijob.exception.TailorProposalConflictException;
import aijob.exception.TailorProposalNotFoundException;
import aijob.exception.TailoredFileNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.util.ConservativeLanguageJudge;
import aijob.util.ConservativeTailorPlanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    private static final String EMAIL = "user@example.com";
    private static final String NODE_TEXT =
            "●   Developed and integrated IP rate-limiting features.";
    private static final String NEW_TEXT =
            "●   Developed and integrated IP rate-limiting features for traffic.";
    private static final String NEW_TEXT_SECOND =
            "●   Developed IP rate-limiting features and integrated them.";
    private static final String AWS_NODE_TEXT =
            "●   Deployed backend services on AWS for production workloads.";
    private static final String AWS_HA_TEXT =
            "●   Deployed backend services on AWS with high availability and scalability.";
    private static final String AWS_POLISH_TEXT =
            "●   Deployed production backend services on AWS.";
    private static final String P16_ORIGINAL =
            "Design and implement the application using Java Spring Boot with an MVC pattern.";
    private static final String P16_AI =
            "Designed and implemented a Java Spring Boot application using the MVC architecture.";
    private static final String P16_USER =
            "Developed a Java Spring Boot MVC service for checkout.";
    private static final String P17_ORIGINAL =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String P17_AI =
            "Integrated RESTful APIs to manage user authentication, product updates, and shopping cart operations.";
    private static final String TAILORED_FILE_NAME =
            "11111111-2222-3333-4444-555555555555.docx";
    private static final String TAILORED_PDF_NAME =
            "11111111-2222-3333-4444-555555555555.pdf";
    private static final String TAILORED_DOWNLOAD_URL =
            "/api/v1/jobs/10/tailored/" + TAILORED_FILE_NAME;
    private static final String TAILORED_PDF_DOWNLOAD_URL =
            "/api/v1/jobs/10/tailored/" + TAILORED_PDF_NAME;

    @Mock
    private JobMapper jobMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private AiResumeService aiResumeService;
    @Mock
    private ResumeDocumentService resumeDocumentService;
    @Mock
    private ResumeDocxTailorService resumeDocxTailorService;
    @Mock
    private TailorProposalMapper tailorProposalMapper;
    @Mock
    private GreenhouseJobClient greenhouseJobClient;
    @Mock
    private PdfExportService pdfExportService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StoragePathResolver storagePathResolver;
    private JobServiceImpl jobService;
    private Path resumeFile;

    @BeforeEach
    void setUp() throws Exception {
        resumeFile = tempDir.resolve("resume.docx");
        Files.writeString(resumeFile, "placeholder");
        storagePathResolver = StoragePathResolver.forRoot(tempDir.resolve("uploads"));
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
                storagePathResolver
        );
        lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> ConservativeLanguageJudge.passthroughRanking(invocation.getArgument(3)));
    }

    @Test
    void filterSafeChanges_duplicateNodeId_keepsFirstPassingChange() {
        TailorChange first = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        TailorChange second = verifiedChange("p-23", NODE_TEXT, NEW_TEXT_SECOND);

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(first, second),
                TailorMode.CONSERVATIVE
        );

        assertEquals(1, result.accepted().size());
        assertEquals(NEW_TEXT, result.accepted().get(0).getNewText());
        assertEquals(Boolean.TRUE, result.accepted().get(0).getApplied());
        assertEquals(1, result.rejected().size());
        assertEquals(RejectedTailorChange.DUPLICATE_NODE_ID, result.rejected().get(0).getReasonCode());
        assertEquals("p-23", result.rejected().get(0).getNodeId());
    }

    @Test
    void filterSafeChanges_emptyProposed_returnsEmpty() {
        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void filterSafeChanges_blankAndUnknownEvidenceType_areRejected() {
        TailorChange blank = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        blank.setEvidenceType("  ");
        TailorChange unknown = verifiedChange("p-23", NODE_TEXT, NEW_TEXT_SECOND);
        unknown.setEvidenceType("MAYBE");
        TailorChange missing = verifiedChange("p-24", NODE_TEXT, NEW_TEXT);
        missing.setEvidenceType(null);

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(blank, unknown, missing),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(3, result.rejected().size());
        assertTrue(result.rejected().stream().allMatch(
                item -> RejectedTailorChange.UNKNOWN_EVIDENCE_TYPE.equals(item.getReasonCode())));
    }

    @Test
    void filterSafeChanges_conservativeInferred_isRejected() {
        TailorChange inferred = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        inferred.setEvidenceType(TailorChange.EVIDENCE_INFERRED);

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(inferred),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertEquals(RejectedTailorChange.INFERRED_NOT_ALLOWED, result.rejected().get(0).getReasonCode());
    }

    @Test
    void tailorResume_conservative_eachPlannedNodeIsRewrittenSeparately() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT),
                new ResumeDocumentNode("p-24", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern.")
        ));
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                node.getNodeId().equals("p-23")
                        ? NEW_TEXT
                        : "Designed and implemented a Java Spring Boot application using the MVC architecture."
        ));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(aiResumeService, never()).generateTailorChanges(anyList(), any(), any());
        verify(aiResumeService, times(2)).generateConstrainedTailorCandidates(
                any(), any(), any(), isNull());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(2, writerChanges.getValue().size());
        assertEquals(2, response.getAppliedChangeCount());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void tailorResume_emptyChanges_unchangedTrue() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> {
            TailorChange empty = verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT);
            empty.setNewText("  ");
            return empty;
        });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 1);
        assertEquals(RejectedTailorChange.EMPTY_REQUIRED_FIELD,
                response.getRejectedChanges().get(0).getReasonCode());
        assertEquals(TailoredResumeResponse.STATUS_GENERATION_REJECTED, response.getStatus());
        assertTrue(response.getChanges().isEmpty());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
        verify(aiResumeService, times(1)).generateConstrainedTailorCandidates(
                any(), any(), any(), isNull());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull());
    }

    @Test
    void tailorResume_onlyAggressiveInferred_returnsPendingProposalWithoutWriter() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        TailorChange inferred = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        inferred.setEvidenceType(TailorChange.EVIDENCE_INFERRED);
        inferred.setApplied(true);
        inferred.setInferredSkill("Redis");
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.AGGRESSIVE)))
                .thenReturn(List.of(inferred));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.AGGRESSIVE);

        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        ArgumentCaptor<TailorProposal> inserted =
                ArgumentCaptor.forClass(TailorProposal.class);
        verify(tailorProposalMapper).insert(inserted.capture());
        assertTrue(inserted.getValue().getChangesJson().contains("evidenceNodeIds"));
        assertTrue(inserted.getValue().getChangesJson().contains("evidenceQuote"));
        assertEquals(TailorProposal.STATUS_PENDING, inserted.getValue().getStatus());

        assertEquals(42L, response.getProposalId());
        assertEquals(TailorProposal.STATUS_PENDING, response.getStatus());
        assertNotNull(response.getExpiresAt());
        assertTrue(response.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        assertTrue(response.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).plusHours(25)));
        assertNull(response.getGeneratedFilePath());
        assertNull(response.getGeneratedFileName());
        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 0);
        assertEquals(1, response.getChanges().size());
        assertEquals(Boolean.FALSE, response.getChanges().get(0).getApplied());
        assertNull(response.getDownloadUrl());
        assertNull(response.getPdfDownloadUrl());
        assertNull(response.getPdfExportStatus());
        verify(pdfExportService, never()).exportPdf(any());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
    }

    @Test
    void createTailorProposal_returnsPendingWithoutWriterOrPdf() throws Exception {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));

        Path tailoredDir = storagePathResolver.tailoredJobDirectory(1L, 10L);
        int filesBefore = countFiles(tailoredDir);

        TailoredResumeResponse response = jobService.createTailorProposal(EMAIL, 10L, 3L);

        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(pdfExportService, never()).exportPdf(any());
        verify(aiResumeService, never()).generateTailorChanges(anyList(), any(), any());
        verify(aiResumeService).generateConstrainedTailorCandidates(
                any(), any(), any(), isNull());
        assertEquals(filesBefore, countFiles(tailoredDir));

        ArgumentCaptor<TailorProposal> inserted = ArgumentCaptor.forClass(TailorProposal.class);
        verify(tailorProposalMapper).insert(inserted.capture());
        assertEquals(TailorProposal.STATUS_PENDING, inserted.getValue().getStatus());
        assertEquals(TailorMode.CONSERVATIVE.name(), inserted.getValue().getMode());
        assertNull(inserted.getValue().getGeneratedFileName());
        assertNull(inserted.getValue().getGeneratedFilePath());

        assertEquals(42L, response.getProposalId());
        assertEquals(TailorProposal.STATUS_PENDING, response.getStatus());
        assertEquals(TailorMode.CONSERVATIVE, response.getMode());
        assertEquals(3L, response.getResumeId());
        assertEquals(10L, response.getJobId());
        assertNotNull(response.getExpiresAt());
        assertNull(response.getGeneratedFilePath());
        assertNull(response.getGeneratedFileName());
        assertNull(response.getDownloadUrl());
        assertNull(response.getPdfDownloadUrl());
        assertNull(response.getPdfExportStatus());
        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(1, response.getChanges().size());
        assertEquals(Boolean.FALSE, response.getChanges().get(0).getApplied());
        assertEquals(Boolean.TRUE, response.getChanges().get(0).getRequiresConfirmation());
        assertEquals("p-23", response.getChanges().get(0).getNodeId());
        assertEquals(NODE_TEXT, response.getChanges().get(0).getOriginalText());
        assertEquals(NEW_TEXT, response.getChanges().get(0).getNewText());
        assertEquals(TailorChange.EVIDENCE_VERIFIED, response.getChanges().get(0).getEvidenceType());
        assertNotNull(response.getChanges().get(0).getEvidenceNodeIds());
        assertNotNull(response.getChanges().get(0).getEvidenceQuote());

        String json = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(response);
        assertTrue(json.contains("\"proposalId\""));
        assertTrue(json.contains("\"status\""));
        assertTrue(json.contains("PENDING"));
        assertTrue(json.contains("CONSERVATIVE"));
        assertTrue(json.contains("\"changes\""));
        assertTrue(json.contains("\"nodeId\""));
        assertTrue(json.contains("\"originalText\""));
        assertTrue(json.contains("\"newText\""));
        assertTrue(json.contains("\"applied\""));
        assertTrue(json.contains("\"requiresConfirmation\""));
        assertTrue(json.contains("\"evidenceType\""));
        assertTrue(json.contains("\"evidenceNodeIds\""));
        assertTrue(json.contains("\"evidenceQuote\""));
        assertTrue(json.contains("\"expiresAt\""));
        assertFalse(json.contains("generatedFilePath"));
        assertFalse(json.contains("downloadUrl\":\""), json);
    }

    @Test
    void createTailorProposal_thenConfirm_writesOnlySelectedAndCountsFiles() throws Exception {
        Path tailoredDir = storagePathResolver.tailoredJobDirectory(1L, 10L);
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                node.getNodeId().equals("p-23") ? NEW_TEXT : NEW_TEXT_SECOND
        ));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    Files.createDirectories(tailoredDir);
                    Path out = tailoredDir.resolve(TAILORED_FILE_NAME);
                    Files.writeString(out, "docx");
                    return out;
                });
        when(pdfExportService.exportPdf(any())).thenAnswer(invocation -> {
            Path pdf = tailoredDir.resolve(TAILORED_PDF_NAME);
            Files.writeString(pdf, "pdf");
            return pdf;
        });

        int filesBefore = countFiles(tailoredDir);
        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);
        int filesAfterPropose = countFiles(tailoredDir);
        assertEquals(0, filesBefore);
        assertEquals(0, filesAfterPropose);
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());

        ArgumentCaptor<TailorProposal> inserted = ArgumentCaptor.forClass(TailorProposal.class);
        verify(tailorProposalMapper).insert(inserted.capture());
        TailorProposal stored = inserted.getValue();
        stored.setId(42L);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.markConfirmed(eq(42L), any(), any())).thenReturn(1);

        TailoredResumeResponse confirmed = jobService.confirmTailorProposal(
                EMAIL, 10L, 42L, List.of("p-23"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(1, writerChanges.getValue().size());
        assertEquals("p-23", writerChanges.getValue().get(0).getNodeId());
        verify(pdfExportService).exportPdf(any());

        assertEquals(2, countFiles(tailoredDir));
        assertTrue(Files.exists(tailoredDir.resolve(TAILORED_FILE_NAME)));
        assertTrue(Files.exists(tailoredDir.resolve(TAILORED_PDF_NAME)));
        assertEquals(1, confirmed.getAppliedChangeCount());
        assertEquals(Boolean.TRUE, appliedFor(confirmed, "p-23"));
        assertTrue(confirmed.getChanges().stream()
                .filter(change -> !"p-23".equals(change.getNodeId()))
                .noneMatch(change -> Boolean.TRUE.equals(change.getApplied())));
        assertEquals(TailorMode.CONSERVATIVE, confirmed.getMode());
        assertEquals(proposal.getProposalId(), confirmed.getProposalId());
        assertNotNull(confirmed.getDownloadUrl());
        assertNotNull(confirmed.getPdfDownloadUrl());
    }

    @Test
    void createTailorProposal_emptySelection_doesNotWrite() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));

        jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 42L, List.of())
        );
        verify(tailorProposalMapper, never()).claimPending(anyLong(), anyLong(), anyLong());
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(pdfExportService, never()).exportPdf(any());
    }

    @Test
    void createTailorProposal_pdfResume_isRejectedWithoutWriter() {
        stubCurrentUserAndJob();
        Resume resume = new Resume();
        resume.setId(3L);
        resume.setUserId(1L);
        resume.setFileType("pdf");
        resume.setFilePath(resumeFile.toString());
        when(resumeMapper.findById(3L)).thenReturn(resume);

        assertThrows(
                aijob.exception.InvalidResumeFileException.class,
                () -> jobService.createTailorProposal(EMAIL, 10L, 3L)
        );
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(tailorProposalMapper, never()).insert(any());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
    }

    @Test
    void createTailorProposal_doesNotTouchRealUploads() throws Exception {
        Path realUploads = Path.of("C:\\ai job\\ai job\\uploads");
        Set<String> before = snapshotFiles(realUploads);
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));

        jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(before, snapshotFiles(realUploads));
        assertEquals(before, snapshotFiles(Path.of("uploads").toAbsolutePath().normalize()));
    }

    @Test
    void continueTailorProposal_appendsNextBatchWithoutWriter() throws Exception {
        Path tailoredDir = storagePathResolver.tailoredJobDirectory(1L, 10L);
        Map<String, String> uploadsBefore = snapshotUploadsHashes();
        stubOwnedResources();
        stubJobDescription(
                "Need Java REST APIs, Spring Boot MVC, React checkout, MySQL indexes, and AWS production services.");
        TailorChange first = verifiedChange(
                "p-11",
                "●   Developed Java REST APIs for billing services.",
                "●   Developed Java REST APIs for billing services in production.");
        TailorChange second = verifiedChange(
                "p-22",
                "●   Created React checkout pages for retail customers.",
                "●   Created React checkout pages for retail customers in production.");
        TailorChange third = verifiedChange(
                "p-32",
                "●   Tuned MySQL indexes for order search queries.",
                "●   Tuned MySQL indexes for order search queries in production.");
        TailorProposal stored = pendingEnvelope(
                List.of(first, second, third),
                List.of("p-11", "p-22", "p-32"));
        stubContinueLock(stored);
        when(resumeDocumentService.parseDocx(any())).thenReturn(appendContinueNodes());
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                conservativeRewrite(node.getText())));

        int filesBefore = countFiles(tailoredDir);
        TailoredResumeResponse continued = jobService.continueTailorProposal(EMAIL, 10L, 7L);

        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        assertEquals(filesBefore, countFiles(tailoredDir));
        assertEquals(uploadsBefore, snapshotUploadsHashes());
        assertTrue(continued.getAddedSuggestionCount() >= 1);
        Set<String> lockedIds = Set.of("p-11", "p-22", "p-32");
        Set<String> addedIds = continued.getChanges().stream()
                .map(TailorChange::getNodeId)
                .filter(id -> !lockedIds.contains(id))
                .collect(Collectors.toSet());
        assertEquals(continued.getAddedSuggestionCount(), addedIds.size());
        assertFalse(addedIds.isEmpty());
        assertTrue(continued.getChanges().size() <= 8);
        assertEquals(TailorProposal.STATUS_PENDING, continued.getStatus());
        assertNull(continued.getDownloadUrl());
    }

    @Test
    void continueTailorProposal_doesNotRepeatLockedOrFullySubsumedNodes() throws Exception {
        stubOwnedResources();
        stubJobDescription(
                "Need Java Spring Boot, REST APIs, Maven component selection, algorithms, and MySQL indexes.");
        TailorChange p16 = verifiedChange("p-16", P16_ORIGINAL, P16_AI);
        TailorChange p17 = verifiedChange("p-17", P17_ORIGINAL, P17_AI);
        TailorChange p23 = verifiedChange(
                "p-23",
                "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
                "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.");
        TailorProposal stored = pendingEnvelope(
                List.of(p16, p17, p23),
                List.of("p-16", "p-17", "p-23", "p-21"));
        stubContinueLock(stored);
        when(resumeDocumentService.parseDocx(any())).thenReturn(lockedPrimeAndSmartPcNodes());
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                conservativeRewrite(node.getText())));

        TailoredResumeResponse continued = jobService.continueTailorProposal(EMAIL, 10L, 7L);

        Set<String> ids = continued.getChanges().stream()
                .map(TailorChange::getNodeId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("p-16"));
        assertTrue(ids.contains("p-17"));
        assertTrue(ids.contains("p-23"));
        assertFalse(ids.contains("p-21"));
        long repeats = continued.getChanges().stream()
                .map(TailorChange::getNodeId)
                .filter(id -> Set.of("p-16", "p-17", "p-23").contains(id))
                .count();
        assertEquals(3, repeats);
        if (continued.getAddedSuggestionCount() > 0) {
            assertTrue(ids.contains("p-30"));
        }
    }

    @Test
    void continueTailorProposal_noQualifiedNodes_returnsZeroWithoutError() throws Exception {
        stubOwnedResources();
        TailorChange only = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        TailorProposal stored = pendingEnvelope(List.of(only), List.of("p-23"));
        TailorProposalState state = TailorProposalState.parse(objectMapper, stored.getChangesJson());
        state.setHasMoreSuggestions(Boolean.TRUE);
        stored.setChangesJson(toJson(state));
        stubContinueLock(stored);
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());

        Path tailoredDir = storagePathResolver.tailoredJobDirectory(1L, 10L);
        int filesBefore = countFiles(tailoredDir);
        TailoredResumeResponse continued = jobService.continueTailorProposal(EMAIL, 10L, 7L);

        assertEquals(0, continued.getAddedSuggestionCount());
        assertEquals(Boolean.FALSE, continued.getHasMoreSuggestions());
        assertEquals(1, continued.getChanges().size());
        assertEquals(filesBefore, countFiles(tailoredDir));
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
    }

    @Test
    void continueTailorProposal_legacyArrayJson_isReadable() throws Exception {
        stubOwnedResources();
        stubJobDescription("Need Java REST APIs and MySQL indexes.");
        TailorChange existing = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        TailorProposal stored = pendingProposal(1L, 10L, 3L, List.of(existing));
        stored.setMode(TailorMode.CONSERVATIVE.name());
        stubContinueLock(stored);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT),
                new ResumeDocumentNode("p-30", "PARAGRAPH",
                        "Tuned MySQL indexes to speed up order search queries.")
        ));
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                conservativeRewrite(node.getText())));

        TailoredResumeResponse continued = jobService.continueTailorProposal(EMAIL, 10L, 7L);

        assertTrue(continued.getChanges().stream().anyMatch(change -> "p-23".equals(change.getNodeId())));
        assertNotNull(continued.getHasMoreSuggestions());
    }

    @Test
    void continueTailorProposal_otherUsersProposal_is404() {
        stubCurrentUserAndJob();
        TailorProposal foreign = pendingProposal(99L, 10L, 3L, List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(0);
        when(tailorProposalMapper.findById(7L)).thenReturn(foreign);

        assertThrows(
                TailorProposalNotFoundException.class,
                () -> jobService.continueTailorProposal(EMAIL, 10L, 7L)
        );
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void continueTailorProposal_confirmedOrExpired_is409() {
        stubCurrentUserAndJob();
        TailorProposal confirmed = pendingProposal(1L, 10L, 3L, List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
        confirmed.setStatus(TailorProposal.STATUS_CONFIRMED);
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(0);
        when(tailorProposalMapper.findById(7L)).thenReturn(confirmed);
        assertThrows(
                TailorProposalConflictException.class,
                () -> jobService.continueTailorProposal(EMAIL, 10L, 7L)
        );

        TailorProposal expired = pendingProposal(1L, 10L, 3L, List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(tailorProposalMapper.findById(7L)).thenReturn(expired);
        assertThrows(
                TailorProposalConflictException.class,
                () -> jobService.continueTailorProposal(EMAIL, 10L, 7L)
        );
    }

    @Test
    void continueTailorProposal_concurrentContinuesAppendOnce() throws Exception {
        stubOwnedResources();
        stubJobDescription(
                "Need Java REST APIs, Python dashboards, SQL reports, React checkout, Redis caching, and MySQL indexes.");
        TailorChange first = verifiedChange(
                "p-11",
                "●   Developed Java REST APIs for billing services.",
                "●   Developed Java REST APIs for billing services in production.");
        TailorProposal stored = pendingEnvelope(List.of(first), List.of("p-11"));
        stubContinueLock(stored);
        when(resumeDocumentService.parseDocx(any())).thenReturn(eightDistinctExperienceNodes());
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                conservativeRewrite(node.getText())));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable task = () -> {
            ready.countDown();
            try {
                start.await(2, TimeUnit.SECONDS);
                jobService.continueTailorProposal(EMAIL, 10L, 7L);
                successes.incrementAndGet();
            } catch (TailorProposalConflictException | TailorProposalNotFoundException ex) {
                conflicts.incrementAndGet();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(task);
        pool.submit(task);
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
        verify(tailorProposalMapper, times(1)).updateConfirmingContents(eq(7L), any(), any());
    }

    @Test
    void continueAndConfirm_conflictDoesNotWriteDuplicateFiles() throws Exception {
        stubCurrentUserAndJob();
        TailorChange only = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        TailorProposal stored = pendingEnvelope(List.of(only), List.of("p-23"));
        stored.setStatus(TailorProposal.STATUS_CONFIRMING);
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(0);
        when(tailorProposalMapper.findById(7L)).thenReturn(stored);

        assertThrows(
                TailorProposalConflictException.class,
                () -> jobService.continueTailorProposal(EMAIL, 10L, 7L)
        );
        assertThrows(
                TailorProposalConflictException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-23"))
        );
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void tailorResume_unknownEvidenceType_isOverwrittenAndWritten() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> {
            TailorChange unknown = verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT);
            unknown.setEvidenceType("LIKELY");
            unknown.setEvidenceNodeIds(List.of("p-999"));
            unknown.setEvidenceQuote("invented quote");
            return unknown;
        });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        TailorChange written = writerChanges.getValue().get(0);
        assertEquals(TailorChange.EVIDENCE_VERIFIED, written.getEvidenceType());
        assertEquals(List.of("p-23"), written.getEvidenceNodeIds());
        assertEquals(NODE_TEXT, written.getEvidenceQuote());
        assertEquals(1, response.getAppliedChangeCount());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void tailorResume_conservativeInferred_isOverwrittenToVerified() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> {
            TailorChange inferred = verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT);
            inferred.setEvidenceType(TailorChange.EVIDENCE_INFERRED);
            inferred.setInferredSkill("Redis");
            inferred.setRequiresConfirmation(true);
            return inferred;
        });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(1, response.getAppliedChangeCount());
        assertEquals(TailorChange.EVIDENCE_VERIFIED, response.getChanges().get(0).getEvidenceType());
        assertEquals(Boolean.FALSE, response.getChanges().get(0).getRequiresConfirmation());
        verify(resumeDocxTailorService).createTailoredDocx(any(), anyList(), eq(1L), eq(10L));
    }

    @Test
    void tailorResume_conservative_plannerCapsRewritesAtFive() {
        stubOwnedResources();
        List<ResumeDocumentNode> nodes = manyExperienceNodes(11);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        stubConstrainedByNode(node -> verifiedChange(
                node.getNodeId(),
                node.getText(),
                node.getText() + " used during operations"
        ));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(aiResumeService, org.mockito.Mockito.atMost(ConservativeTailorPlanner.GENERATION_PAGE_SIZE + 2))
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertFalse(response.isUnchanged());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void tailorResume_conservative_selectsImprovedCandidateNotLowValue() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        ConservativeRewriteCandidatesResponse batch = candidates(
                lowValueCandidate("●   Integrated and developed IP rate-limiting features."),
                recommendedCandidate("●   Integrated and developed IP rate-limiting features."),
                candidate(NEW_TEXT, false)
        );
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(batch);
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(NEW_TEXT, writerChanges.getValue().get(0).getNewText());
        assertEquals(1, response.getAppliedChangeCount());
        assertNotNull(response.getChanges().get(0).getReasonZh());
    }

    @Test
    void tailorResume_conservative_judgeFailureFallsBackToLocalRanking() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(NEW_TEXT, false), candidate(NEW_TEXT_SECOND, false)));
        org.mockito.Mockito.lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("judge down"));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(0, response.getAppliedChangeCount());
        assertTrue(response.getChanges() == null || response.getChanges().isEmpty()
                || response.getChanges().stream().noneMatch(change ->
                Boolean.TRUE.equals(change.getApplied())));
    }

    @Test
    void tailorResume_conservative_allLowValueCandidatesStayUnchanged() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        ConservativeRewriteCandidatesResponse lowValue = candidates(
                lowValueCandidate("●   Integrated and developed IP rate-limiting features.")
        );
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(lowValue);
        when(aiResumeService.generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull()))
                .thenReturn(lowValue);
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE,
                response.getRejectedChanges().get(0).getReasonCode());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
    }

    @Test
    void tailorResume_grammarFixesOnDifferentNodesAreKept() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Integrated RESTful APIs to handle checkout flows, inventory updates and order managements")
        ));
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    String original = node.getText();
                    return asCandidates(verifiedChange(
                            node.getNodeId(),
                            original,
                            original.replace("managements", "operations")
                                    .replace(" updates and", " updates, and")
                    ));
                });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(2, response.getAppliedChangeCount());
        assertTrue(response.getChanges().stream()
                .allMatch(change -> UpliftType.GRAMMAR_FIX.equals(change.getUpliftType())));
    }

    @Test
    void tailorResume_substantivePreferredOverGrammar() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(implementationAndGrammarNodes());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-2".equals(node.getNodeId())) {
                        return asCandidates(verifiedChange(
                                node.getNodeId(),
                                node.getText(),
                                "Designed and implemented a Java Spring Boot application using the MVC architecture."
                        ));
                    }
                    return asCandidates(verifiedChange(
                            node.getNodeId(),
                            node.getText(),
                            "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations."
                    ));
                });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        long grammar = response.getChanges().stream()
                .filter(change -> UpliftType.GRAMMAR_FIX.equals(change.getUpliftType()))
                .count();
        assertTrue(response.getChanges().stream()
                .anyMatch(change -> UpliftType.CLARITY_IMPROVEMENT.equals(change.getUpliftType())
                        || UpliftType.SUBSTANTIVE_REWRITE.equals(change.getUpliftType())));
        assertTrue(grammar <= 1);
        assertTrue(response.getChanges().stream()
                .anyMatch(change -> change.getNewText().contains("Spring Boot")));
    }

    @Test
    void tailorResume_sameEntryFactsAreNotCopiedOntoAnotherBullet() {
        stubOwnedResources();
        stubJobDescription("Need Java REST APIs and AWS production services.");
        when(resumeDocumentService.parseDocx(any())).thenReturn(sameEntryExperienceNodes());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-16".equals(node.getNodeId())) {
                        ConservativeRewriteCandidate copied = candidate(
                                "●   Built REST APIs in Java and deployed backend services on AWS for production workloads.",
                                false);
                        copied.setUsedFactNodeIds(List.of("p-16", "p-17"));
                        copied.setUsedSupportingNodeIds(List.of("p-17"));
                        return candidates(copied);
                    }
                    ConservativeRewriteCandidate candidate = candidate(
                            "●   Deployed backend services onto AWS for production workloads.", false);
                    candidate.setUsedFactNodeIds(List.of("p-17"));
                    return candidates(candidate);
                });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        long awsOnRest = response.getChanges().stream()
                .filter(change -> "p-16".equals(change.getNodeId())
                        && change.getNewText() != null
                        && change.getNewText().toLowerCase().contains("aws"))
                .count();
        assertEquals(0, awsOnRest);
        assertTrue(response.getRejectedChanges().stream().anyMatch(item ->
                "p-16".equals(item.getNodeId())), response.getRejectedChanges().toString());
    }

    @Test
    void tailorResume_createdInsteadOfDesignedIsRejected() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(implementationAndGrammarNodes());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if (node != null && "p-2".equals(node.getNodeId())) {
                        return asCandidates(verifiedChange(
                                node.getNodeId(),
                                node.getText(),
                                "Created an application using Java Spring Boot, following an MVC design pattern."
                        ));
                    }
                    if (node == null) {
                        return candidates();
                    }
                    return asCandidates(verifiedChange(
                            node.getNodeId(),
                            node.getText(),
                            "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations."
                    ));
                });
        when(aiResumeService.generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if (node == null) {
                        return candidates();
                    }
                    return asCandidates(verifiedChange(
                            node.getNodeId(),
                            node.getText(),
                            "Created an application using Java Spring Boot, following an MVC design pattern."
                    ));
                });
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.getChanges().stream()
                .noneMatch(change -> change.getNewText() != null
                        && change.getNewText().toLowerCase().startsWith("created")));
    }

    @Test
    void tailorResume_inventedMetricIsRejected() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(
                        "●   Developed and integrated 40 IP rate-limiting features.",
                        false)));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(RejectedTailorChange.UNSUPPORTED_CLAIM,
                response.getRejectedChanges().get(0).getReasonCode());
    }

    @Test
    void tailorResume_doesNotTouchRealUploads() throws Exception {
        Path realUploads = Path.of("C:\\ai job\\ai job\\uploads");
        java.util.Set<String> before = snapshotFiles(realUploads);
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(NEW_TEXT, false)));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        jobService.tailorResume(EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(before, snapshotFiles(realUploads));
        assertEquals(before, snapshotFiles(Path.of("uploads").toAbsolutePath().normalize()));
    }

    @Test
    void filterSafeChanges_elevenVerified_keepsFirstTen() {
        List<ResumeDocumentNode> nodes = manyExperienceNodes(11);
        List<TailorChange> proposed = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            proposed.add(verifiedChange("p-" + i, bulletText(i), rewrittenBullet(i)));
        }

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                nodes,
                proposed,
                TailorMode.AGGRESSIVE
        );

        assertEquals(10, result.accepted().size());
        assertEquals(1, result.rejected().size());
        assertEquals("p-11", result.rejected().get(0).getNodeId());
        assertEquals(
                RejectedTailorChange.MAX_CHANGE_LIMIT_EXCEEDED,
                result.rejected().get(0).getReasonCode()
        );
    }

    @Test
    void filterSafeChanges_awsDeployPlusHighAvailability_isUnsupportedClaim() {
        TailorChange change = verifiedChange("p-33", AWS_NODE_TEXT, AWS_HA_TEXT);

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                awsExperienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertEquals(RejectedTailorChange.UNSUPPORTED_CLAIM, result.rejected().get(0).getReasonCode());
        assertEquals("p-33", result.rejected().get(0).getNodeId());
    }

    @Test
    void filterSafeChanges_awsDeployOrdinaryPolish_isAllowed() {
        TailorChange change = verifiedChange("p-33", AWS_NODE_TEXT, AWS_POLISH_TEXT);

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                awsExperienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertEquals(1, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        assertEquals(Boolean.TRUE, result.accepted().get(0).getApplied());
        assertEquals(AWS_POLISH_TEXT, result.accepted().get(0).getNewText());
    }

    @Test
    void filterSafeChanges_conservativeCrossEntryEvidence_isRejected() {
        TailorChange change = verifiedChange(
                "p-16",
                "●   Build REST APIs in Java.",
                "●   Built REST APIs in Java on AWS."
        );
        change.setEvidenceNodeIds(List.of("p-18"));
        change.setEvidenceQuote("AWS");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                crossEntryExperienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertEquals("p-16", result.rejected().get(0).getNodeId());
        assertEquals(
                RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED,
                result.rejected().get(0).getReasonCode()
        );
    }

    @Test
    void filterSafeChanges_conservativeSameEntryEvidence_isAllowed() {
        TailorChange change = verifiedChange(
                "p-16",
                "●   Build REST APIs in Java.",
                "●   Built REST APIs in Java on AWS."
        );
        change.setEvidenceNodeIds(List.of("p-16", "p-17"));
        change.setEvidenceQuote("AWS");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                sameEntryExperienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertEquals(1, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void filterSafeChanges_skillsCannotProveProjectDocker() {
        TailorChange change = verifiedChange(
                "p-4",
                "●   Built a Java Spring Boot application.",
                "●   Built a Java Spring Boot Docker application."
        );

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                List.of(
                        new ResumeDocumentNode("p-0", "PARAGRAPH", "TECHNICAL SKILLS"),
                        new ResumeDocumentNode("p-1", "PARAGRAPH", "Java, Docker, Kubernetes"),
                        new ResumeDocumentNode("p-2", "PARAGRAPH", "RELEVANT PROJECTS"),
                        new ResumeDocumentNode("p-3", "PARAGRAPH", "Store App | 2024"),
                        new ResumeDocumentNode("p-4", "PARAGRAPH", "●   Built a Java Spring Boot application.")
                ),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(RejectedTailorChange.UNSUPPORTED_CLAIM, result.rejected().get(0).getReasonCode());
    }

    @Test
    void filterSafeChanges_aggressiveCrossNodeEvidence_isAllowed() {
        TailorChange change = verifiedChange(
                "p-16",
                "●   Build REST APIs in Java.",
                "●   Built REST APIs in Java on AWS."
        );
        change.setEvidenceNodeIds(List.of("p-18"));
        change.setEvidenceQuote("AWS");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                crossNodeExperienceNodes(),
                List.of(change),
                TailorMode.AGGRESSIVE
        );

        assertEquals(1, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        assertEquals(Boolean.TRUE, result.accepted().get(0).getApplied());
    }

    @Test
    void filterSafeChanges_missingEvidence_isRejected() {
        TailorChange missingIds = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        missingIds.setEvidenceNodeIds(List.of());
        TailorChange missingQuote = verifiedChange("p-24", NODE_TEXT, NEW_TEXT);
        missingQuote.setEvidenceQuote("  ");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(missingIds, missingQuote),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.rejected().size());
        assertTrue(result.rejected().stream().allMatch(
                item -> RejectedTailorChange.MISSING_EVIDENCE.equals(item.getReasonCode())));
    }

    @Test
    void filterSafeChanges_unknownEvidenceNode_isRejected() {
        TailorChange change = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        change.setEvidenceNodeIds(List.of("p-999"));
        change.setEvidenceQuote("IP rate-limiting");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertEquals(RejectedTailorChange.UNKNOWN_EVIDENCE_NODE_ID,
                result.rejected().get(0).getReasonCode());
    }

    @Test
    void filterSafeChanges_evidenceQuoteMismatch_isRejected() {
        TailorChange change = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        change.setEvidenceQuote("high availability and scalability");

        JobServiceImpl.TailorFilterResult result = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(change),
                TailorMode.CONSERVATIVE
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertEquals(RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH,
                result.rejected().get(0).getReasonCode());
    }

    @Test
    void tailorResume_validEvidenceVerified_isWrittenInConservative() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));

        TailorChange written = writerChanges.getValue().get(0);
        assertEquals("p-23", written.getNodeId());
        assertEquals(List.of("p-23"), written.getEvidenceNodeIds());
        assertEquals(NODE_TEXT, written.getEvidenceQuote());
        assertEquals(Boolean.TRUE, written.getApplied());
        assertFalse(response.isUnchanged());
        assertEquals(1, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 0);
        assertEquals(Boolean.TRUE, response.getChanges().get(0).getApplied());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void tailorResume_modelOriginalTextMismatch_isOverwrittenAndWritten() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(
                "p-999",
                "completely different original",
                NEW_TEXT
        ));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(1, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 0);
        assertEquals("p-23", response.getChanges().get(0).getNodeId());
        assertEquals(NODE_TEXT, response.getChanges().get(0).getOriginalText());
        assertFalse(response.isUnchanged());
    }

    @Test
    void tailorResume_conservative_stillCallsWriterAndDoesNotInsertProposal() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(resumeDocxTailorService).createTailoredDocx(any(), anyList(), eq(1L), eq(10L));
        verify(tailorProposalMapper, never()).insert(any());
        verify(aiResumeService, never()).generateTailorChanges(anyList(), any(), any());
        assertNull(response.getProposalId());
        assertEquals(1, response.getAppliedChangeCount());
        assertFalse(response.isUnchanged());
    }

    @Test
    void getAndConfirmTailorProposal_otherUsersProposal_isNotFound() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        when(jobMapper.findById(10L)).thenReturn(job);
        TailorProposal other = pendingProposal(
                99L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        when(tailorProposalMapper.findById(7L)).thenReturn(other);
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(0);

        assertThrows(
                TailorProposalNotFoundException.class,
                () -> jobService.getTailorProposal(EMAIL, 10L, 7L)
        );
        assertThrows(
                TailorProposalNotFoundException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-23"))
        );
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_writesOnlySelectedNodes() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(
                        verifiedChange("p-23", NODE_TEXT, NEW_TEXT),
                        verifiedChange("p-24", NODE_TEXT, NEW_TEXT_SECOND)
                )
        );
        stubConfirmSuccess(proposal);

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-23"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(1, writerChanges.getValue().size());
        assertEquals("p-23", writerChanges.getValue().get(0).getNodeId());
        assertEquals(1, response.getAppliedChangeCount());
        assertEquals(TailorProposal.STATUS_CONFIRMED, response.getStatus());
        assertEquals(TAILORED_FILE_NAME, response.getGeneratedFileName());
        assertNotNull(response.getGeneratedFilePath());
        assertEquals(Boolean.TRUE, appliedFor(response, "p-23"));
        assertEquals(Boolean.FALSE, appliedFor(response, "p-24"));
    }

    @Test
    void confirmTailorProposal_unselectedInferredIsNotWritten() {
        stubOwnedResources();
        TailorChange inferred = inferredChange("p-24", NODE_TEXT, NEW_TEXT_SECOND);
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT), inferred)
        );
        stubConfirmSuccess(proposal);

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-23"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(List.of("p-23"), writerChanges.getValue().stream()
                .map(TailorChange::getNodeId)
                .toList());
        assertEquals(Boolean.FALSE, appliedFor(response, "p-24"));
    }

    @Test
    void confirmTailorProposal_selectedInferredIsWritten() {
        stubOwnedResources();
        TailorChange inferred = inferredChange("p-24", NODE_TEXT, NEW_TEXT_SECOND);
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT), inferred)
        );
        stubConfirmSuccess(proposal);

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-24"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(1, writerChanges.getValue().size());
        assertEquals("p-24", writerChanges.getValue().get(0).getNodeId());
        assertEquals(TailorChange.EVIDENCE_INFERRED, writerChanges.getValue().get(0).getEvidenceType());
        assertEquals(Boolean.TRUE, writerChanges.getValue().get(0).getApplied());
        assertEquals(1, response.getAppliedChangeCount());
        assertEquals(Boolean.TRUE, appliedFor(response, "p-24"));
        assertEquals(Boolean.FALSE, appliedFor(response, "p-23"));
    }

    @Test
    void confirmTailorProposal_originalTextOrEvidenceChanged_isNotWritten() {
        stubOwnedResources();
        TailorChange originalMismatch = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        originalMismatch.setOriginalText("completely different original");
        TailorChange evidenceMismatch = verifiedChange("p-24", NODE_TEXT, NEW_TEXT_SECOND);
        evidenceMismatch.setEvidenceQuote("high availability and scalability");
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(originalMismatch, evidenceMismatch)
        );
        stubConfirmSuccess(proposal);

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-23", "p-24"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertTrue(writerChanges.getValue().isEmpty());
        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(2, response.getRejectedChangeCount());
        assertEquals(
                RejectedTailorChange.ORIGINAL_TEXT_MISMATCH,
                response.getRejectedChanges().get(0).getReasonCode()
        );
        assertEquals(
                RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH,
                response.getRejectedChanges().get(1).getReasonCode()
        );
        verify(aiResumeService, never()).generateTailorChanges(anyList(), any(), any());
    }

    @Test
    void confirmTailorProposal_secondConfirmDoesNotWriteAgain() {
        stubOwnedResources();
        TailorProposal pending = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        TailorProposal confirmed = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        confirmed.setStatus(TailorProposal.STATUS_CONFIRMED);
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(1, 0);
        when(tailorProposalMapper.findById(7L)).thenReturn(pending, confirmed);
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));
        when(tailorProposalMapper.markConfirmed(eq(7L), any(), any())).thenReturn(1);

        jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-23"));
        assertThrows(
                TailorProposalNotFoundException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-23"))
        );
        verify(resumeDocxTailorService, times(1))
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(tailorProposalMapper, times(1)).markConfirmed(eq(7L), any(), any());
    }

    @Test
    void confirmTailorProposal_writerFailure_restoresPending() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.findById(7L)).thenReturn(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("write failed"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-23"))
        );
        assertEquals("write failed", thrown.getMessage());
        verify(tailorProposalMapper).restorePending(7L);
        verify(tailorProposalMapper, never()).markConfirmed(anyLong(), any(), any());
    }

    @Test
    void confirmTailorProposal_emptySelectedNodeIds_isRejected() {
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of())
        );
        verify(tailorProposalMapper, never()).claimPending(anyLong(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_editedP16_writesUserTextAndKeepsUneditedVerified() throws Exception {
        Path projectUploads = Path.of("uploads").toAbsolutePath().normalize();
        Set<String> beforeProject = snapshotFiles(projectUploads);
        Path tailoredDir = storagePathResolver.tailoredJobDirectory(1L, 10L);
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(
                        verifiedChange("p-16", P16_ORIGINAL, P16_AI),
                        verifiedChange("p-17", P17_ORIGINAL, P17_AI)
                )
        );
        stubConfirmSuccess(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(primeDealsNodes());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    Files.createDirectories(tailoredDir);
                    Path out = tailoredDir.resolve(TAILORED_FILE_NAME);
                    Files.writeString(out, "docx");
                    return out;
                });
        when(pdfExportService.exportPdf(any())).thenAnswer(invocation -> {
            Path pdf = tailoredDir.resolve(TAILORED_PDF_NAME);
            Files.writeString(pdf, "pdf");
            return pdf;
        });

        assertEquals(0, countFiles(tailoredDir));
        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-16", "p-17"),
                List.of(new EditedTailorChange("p-16", P16_USER)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService, times(1)).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        verify(pdfExportService, times(1)).exportPdf(any());
        assertEquals(P16_USER, writerChanges.getValue().get(0).getNewText());
        assertEquals(TailorChange.EVIDENCE_USER_EDITED, writerChanges.getValue().get(0).getEvidenceType());
        assertEquals("p-16", writerChanges.getValue().get(0).getNodeId());
        assertEquals(P16_ORIGINAL, writerChanges.getValue().get(0).getOriginalText());
        assertEquals(P17_AI, writerChanges.getValue().get(1).getNewText());
        assertEquals(TailorChange.EVIDENCE_VERIFIED, writerChanges.getValue().get(1).getEvidenceType());
        assertEquals(2, countFiles(tailoredDir));
        assertEquals(beforeProject, snapshotFiles(projectUploads));
        assertEquals(TailorChange.EVIDENCE_USER_EDITED, evidenceTypeFor(response, "p-16"));
        assertEquals(TailorChange.EVIDENCE_VERIFIED, evidenceTypeFor(response, "p-17"));
    }

    @Test
    void confirmTailorProposal_unselectedChangeIsNotWrittenEvenIfEditedAttempted() {
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-17"),
                        List.of(new EditedTailorChange("p-16", P16_USER)))
        );
        verify(tailorProposalMapper, never()).claimPending(anyLong(), anyLong(), anyLong());
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_editedNodeNotInProposal_isRejected() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-16", P16_ORIGINAL, P16_AI))
        );
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.findById(7L)).thenReturn(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(primeDealsNodes());

        InvalidTailorProposalException thrown = assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-16", "p-99"),
                        List.of(new EditedTailorChange("p-99", P16_USER)))
        );
        assertTrue(thrown.getMessage().contains("not part of this proposal"));
        verify(tailorProposalMapper).restorePending(7L);
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_duplicateEditedNodeId_isRejected() {
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-16"),
                        List.of(
                                new EditedTailorChange("p-16", P16_USER),
                                new EditedTailorChange("p-16", P16_AI)))
        );
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(EMAIL, 10L, 7L, List.of("p-16", "p-16"))
        );
        verify(tailorProposalMapper, never()).claimPending(anyLong(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_blankOrTooLongEdit_isRejected() {
        String tooLong = "A".repeat(ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS + 1);
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-16"),
                        List.of(new EditedTailorChange("p-16", "   ")))
        );
        assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-16"),
                        List.of(new EditedTailorChange("p-16", tooLong)))
        );
        verify(tailorProposalMapper, never()).claimPending(anyLong(), anyLong(), anyLong());
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void confirmTailorProposal_headingNodeCannotBeEdited() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-0", "EXPERIENCE", "Work Experience"))
        );
        when(tailorProposalMapper.claimPending(7L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.findById(7L)).thenReturn(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(primeDealsNodes());

        InvalidTailorProposalException thrown = assertThrows(
                InvalidTailorProposalException.class,
                () -> jobService.confirmTailorProposal(
                        EMAIL, 10L, 7L, List.of("p-0"),
                        List.of(new EditedTailorChange("p-0", "Work Experience")))
        );
        assertTrue(thrown.getMessage().contains("cannot be edited"));
        verify(resumeDocxTailorService, never())
                .createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(tailorProposalMapper).restorePending(7L);
    }

    @Test
    void confirmTailorProposal_oldSelectedNodeIdsOnlyRequestStillWorks() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-16", P16_ORIGINAL, P16_AI))
        );
        stubConfirmSuccess(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(primeDealsNodes());

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-16"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(P16_AI, writerChanges.getValue().get(0).getNewText());
        assertEquals(TailorChange.EVIDENCE_VERIFIED, writerChanges.getValue().get(0).getEvidenceType());
        assertEquals(1, response.getAppliedChangeCount());
    }

    @Test
    void tailorResume_conservative_setsDownloadUrlAndOmitsAbsolutePathFromJson() throws Exception {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve(TAILORED_FILE_NAME));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(TAILORED_FILE_NAME, response.getGeneratedFileName());
        assertEquals(TAILORED_DOWNLOAD_URL, response.getDownloadUrl());
        assertTrue(response.getGeneratedFilePath().contains(TAILORED_FILE_NAME));

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains(TAILORED_DOWNLOAD_URL));
        assertFalse(json.contains("generatedFilePath"));
        assertFalse(json.contains("C:\\\\") || json.contains("C:\\"));
    }

    @Test
    void tailorResume_conservative_pdfSuccess_setsPdfDownloadUrl() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve(TAILORED_FILE_NAME));
        when(pdfExportService.exportPdf(any()))
                .thenReturn(tempDir.resolve(TAILORED_PDF_NAME));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(TAILORED_DOWNLOAD_URL, response.getDownloadUrl());
        assertEquals(TAILORED_PDF_DOWNLOAD_URL, response.getPdfDownloadUrl());
        assertEquals(PdfExportStatus.SUCCESS, response.getPdfExportStatus());
    }

    @Test
    void tailorResume_conservative_pdfFailure_stillReturnsDocxDownload() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        stubConstrainedByNode(node -> verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve(TAILORED_FILE_NAME));
        when(pdfExportService.exportPdf(any()))
                .thenThrow(new PdfExportException("LibreOffice exploded at C:\\secret"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(TAILORED_DOWNLOAD_URL, response.getDownloadUrl());
        assertEquals(TAILORED_FILE_NAME, response.getGeneratedFileName());
        assertNull(response.getPdfDownloadUrl());
        assertEquals(PdfExportStatus.FAILED, response.getPdfExportStatus());
    }

    @Test
    void tailorResume_textTooLong_repairsOnceAndWrites() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        doAnswer(invocation -> asCandidates(tooLongChange(invocation.getArgument(0))))
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        doAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    return asCandidates(verifiedChange(node.getNodeId(), node.getText(), NEW_TEXT));
                })
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(
                        any(), any(), any(), org.mockito.ArgumentMatchers.notNull());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        ArgumentCaptor<ConservativeRewriteRepair> repair =
                ArgumentCaptor.forClass(ConservativeRewriteRepair.class);
        verify(aiResumeService, times(2)).generateConstrainedTailorCandidates(
                any(), any(), any(), repair.capture());
        assertEquals(RejectedTailorChange.TEXT_TOO_LONG, repair.getAllValues().get(1).reasonCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(1, writerChanges.getValue().size());
        assertEquals(NEW_TEXT, writerChanges.getValue().get(0).getNewText());
        assertEquals(1, response.getAppliedChangeCount());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void tailorResume_crossEntryFacts_areRejectedWithoutRepair() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(crossEntryExperienceNodes());
        doAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-16".equals(node.getNodeId())) {
                        ConservativeRewriteCandidate candidate = candidate(
                                "●   Built REST APIs in Java on AWS.", false);
                        candidate.setUsedFactNodeIds(List.of("p-16", "p-18"));
                        return candidates(candidate);
                    }
                    return asCandidates(verifiedChange(
                            node.getNodeId(),
                            node.getText(),
                            "●   Deployed backend services on AWS for production workloads used during operations."));
                })
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(aiResumeService, never()).generateConstrainedTailorCandidates(
                any(),
                any(),
                any(),
                argThat(repair -> repair != null
                        && RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(repair.reasonCode())));
        assertTrue(response.getRejectedChanges().stream().anyMatch(item ->
                "p-16".equals(item.getNodeId())
                        && (RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(item.getReasonCode())
                        || RejectedTailorChange.UNSUPPORTED_CLAIM.equals(item.getReasonCode()))));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(any(), writerChanges.capture(), eq(1L), eq(10L));
        assertTrue(writerChanges.getValue().stream().noneMatch(change ->
                "p-16".equals(change.getNodeId()) && change.getNewText().toLowerCase().contains("aws")));
    }

    @Test
    void tailorResume_unsupportedClaim_doesNotRepairOrWrite() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(awsExperienceNodes());
        doAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    return asCandidates(verifiedChange(node.getNodeId(), node.getText(), AWS_HA_TEXT));
                })
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(aiResumeService, never()).generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(RejectedTailorChange.UNSUPPORTED_CLAIM,
                response.getRejectedChanges().get(0).getReasonCode());
        assertEquals(TailoredResumeResponse.STATUS_NEEDS_USER_EVIDENCE, response.getStatus());
    }

    @Test
    void tailorResume_repairStillFails_unchangedTrue() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(singleExperienceNode());
        doAnswer(invocation -> asCandidates(tooLongChange(invocation.getArgument(0))))
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        doAnswer(invocation -> asCandidates(tooLongChange(invocation.getArgument(0))))
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(
                        any(), any(), any(), org.mockito.ArgumentMatchers.notNull());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertEquals(RejectedTailorChange.TEXT_TOO_LONG,
                response.getRejectedChanges().get(0).getReasonCode());
        assertEquals(TailoredResumeResponse.STATUS_GENERATION_REJECTED, response.getStatus());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
    }

    @Test
    void tailorResume_oneSuccessTwoFailures_writesOnlyTheSuccessfulChange() {
        stubOwnedResources();
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Need Java REST APIs for billing services.");
        when(jobMapper.findById(10L)).thenReturn(job);
        when(resumeDocumentService.parseDocx(any())).thenReturn(threeEntryExperienceNodes());
        doAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-1".equals(node.getNodeId())) {
                        return asCandidates(verifiedChange(
                                node.getNodeId(),
                                node.getText(),
                                "●   Designed Java REST APIs for billing services."));
                    }
                    if ("p-2".equals(node.getNodeId())) {
                        return asCandidates(verifiedChange(
                                node.getNodeId(),
                                node.getText(),
                                node.getText() + " with Docker and Kubernetes."
                        ));
                    }
                    return asCandidates(tooLongChange(node));
                })
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(any(), any(), any(), isNull());
        doAnswer(invocation -> asCandidates(tooLongChange(invocation.getArgument(0))))
                .when(aiResumeService)
                .generateConstrainedTailorCandidates(
                        any(), any(), any(), org.mockito.ArgumentMatchers.notNull());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));
        assertEquals(1, writerChanges.getValue().size());
        assertEquals("p-1", writerChanges.getValue().get(0).getNodeId());
        assertEquals(1, response.getAppliedChangeCount());
        assertFalse(response.isUnchanged());
        assertEquals(2, response.getRejectedChangeCount());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
    }

    @Test
    void confirmTailorProposal_pdfFailure_stillConfirmsDocx() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        stubConfirmSuccess(proposal);
        when(pdfExportService.exportPdf(any()))
                .thenThrow(new PdfExportException("converter timeout"));

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-23"));

        assertEquals(TAILORED_DOWNLOAD_URL, response.getDownloadUrl());
        assertNull(response.getPdfDownloadUrl());
        assertEquals(PdfExportStatus.FAILED, response.getPdfExportStatus());
        verify(tailorProposalMapper).markConfirmed(eq(7L), any(), any());
        verify(tailorProposalMapper, never()).restorePending(7L);
    }

    @Test
    void confirmTailorProposal_setsDownloadUrl() {
        stubOwnedResources();
        TailorProposal proposal = pendingProposal(
                1L,
                10L,
                3L,
                List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT))
        );
        stubConfirmSuccess(proposal);

        TailoredResumeResponse response = jobService.confirmTailorProposal(
                EMAIL, 10L, 7L, List.of("p-23"));

        assertEquals(TAILORED_DOWNLOAD_URL, response.getDownloadUrl());
        assertEquals(TAILORED_FILE_NAME, response.getGeneratedFileName());
    }

    @Test
    void downloadTailoredResume_ownFile_returnsResolvedPath() throws Exception {
        stubCurrentUserAndJob();
        Path file = createOwnedTailoredFile(TAILORED_FILE_NAME, "docx-bytes");
        Path downloaded = jobService.downloadTailoredResume(EMAIL, 10L, TAILORED_FILE_NAME);
        assertEquals(file.toAbsolutePath().normalize(), downloaded.toAbsolutePath().normalize());
        assertTrue(downloaded.startsWith(tempDir.toAbsolutePath().normalize()));
        assertEquals("docx-bytes", Files.readString(downloaded));
    }

    @Test
    void downloadTailoredResume_pdfFile_returnsResolvedPath() throws Exception {
        stubCurrentUserAndJob();
        Path file = createOwnedTailoredFile(TAILORED_PDF_NAME, "pdf-bytes");
        Path downloaded = jobService.downloadTailoredResume(EMAIL, 10L, TAILORED_PDF_NAME);
        assertEquals(file.toAbsolutePath().normalize(), downloaded.toAbsolutePath().normalize());
        assertTrue(downloaded.startsWith(tempDir.toAbsolutePath().normalize()));
        assertEquals("pdf-bytes", Files.readString(downloaded));
    }

    @Test
    void downloadTailoredResume_neverTouchesProjectUploadsOrSentinel() throws Exception {
        Path projectUploads = Path.of("uploads").toAbsolutePath().normalize();
        Set<String> beforeProject = snapshotFiles(projectUploads);

        Path simulatedRealDir = tempDir.resolve("simulated-project")
                .resolve("uploads")
                .resolve("tailored")
                .resolve("1")
                .resolve("10");
        Files.createDirectories(simulatedRealDir);
        Path sentinel = simulatedRealDir.resolve("sentinel-existing.pdf");
        Files.writeString(sentinel, "keep-me");

        stubCurrentUserAndJob();
        Path file = createOwnedTailoredFile(TAILORED_FILE_NAME, "docx-bytes");
        Path downloaded = jobService.downloadTailoredResume(EMAIL, 10L, TAILORED_FILE_NAME);

        assertTrue(downloaded.startsWith(tempDir.toAbsolutePath().normalize()));
        assertFalse(downloaded.startsWith(projectUploads));
        assertFalse(downloaded.startsWith(simulatedRealDir));
        assertEquals(beforeProject, snapshotFiles(projectUploads));
        assertTrue(Files.exists(sentinel));
        assertEquals("keep-me", Files.readString(sentinel));
        assertTrue(Files.exists(file));
    }

    @Test
    void downloadTailoredResume_otherUsersJob_isNotFound() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job foreign = new Job();
        foreign.setId(99L);
        foreign.setUserId(2L);
        when(jobMapper.findById(99L)).thenReturn(foreign);

        assertThrows(
                JobNotFoundException.class,
                () -> jobService.downloadTailoredResume(EMAIL, 99L, TAILORED_FILE_NAME)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../secret.docx",
            "..\\secret.docx",
            "a/b.docx",
            "not-a-uuid.docx",
            "11111111-2222-3333-4444-555555555555.docx.bak",
            "11111111-2222-3333-4444-555555555555.exe",
            "11111111-2222-3333-4444-555555555555.html",
            "11111111-2222-3333-4444-555555555555.txt",
            " ",
            ""
    })
    void downloadTailoredResume_illegalFileName_isNotFound(String fileName) {
        stubCurrentUserAndJob();
        assertThrows(
                TailoredFileNotFoundException.class,
                () -> jobService.downloadTailoredResume(EMAIL, 10L, fileName)
        );
    }

    @Test
    void downloadTailoredResume_missingFile_isNotFound() {
        stubCurrentUserAndJob();
        assertThrows(
                TailoredFileNotFoundException.class,
                () -> jobService.downloadTailoredResume(EMAIL, 10L, TAILORED_FILE_NAME)
        );
    }

    @Test
    void bulkDeleteJobs_twoGreenhouseJobs_deletesBoth() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job first = greenhouseJob(11L);
        Job second = greenhouseJob(12L);
        when(jobMapper.findById(11L)).thenReturn(first);
        when(jobMapper.findById(12L)).thenReturn(second);
        when(jobMapper.deleteByIdAndUserId(11L, 1L)).thenReturn(1);
        when(jobMapper.deleteByIdAndUserId(12L, 1L)).thenReturn(1);

        var response = jobService.bulkDeleteJobs(EMAIL, List.of(11L, 12L));

        assertEquals(2, response.getDeletedCount());
        verify(jobMapper).deleteByIdAndUserId(11L, 1L);
        verify(jobMapper).deleteByIdAndUserId(12L, 1L);
    }

    @Test
    void bulkDeleteJobs_empty_is400() {
        assertThrows(
                InvalidBulkDeleteException.class,
                () -> jobService.bulkDeleteJobs(EMAIL, List.of())
        );
        verify(jobMapper, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void bulkDeleteJobs_containsManual_is400AndDeletesNothing() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job greenhouse = greenhouseJob(11L);
        Job manual = greenhouseJob(12L);
        manual.setSource("MANUAL");
        when(jobMapper.findById(11L)).thenReturn(greenhouse);
        when(jobMapper.findById(12L)).thenReturn(manual);

        assertThrows(
                InvalidBulkDeleteException.class,
                () -> jobService.bulkDeleteJobs(EMAIL, List.of(11L, 12L))
        );
        verify(jobMapper, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void bulkDeleteJobs_otherUserJob_is404AndDeletesNothing() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job owned = greenhouseJob(11L);
        Job foreign = greenhouseJob(12L);
        foreign.setUserId(99L);
        when(jobMapper.findById(11L)).thenReturn(owned);
        when(jobMapper.findById(12L)).thenReturn(foreign);

        assertThrows(
                JobNotFoundException.class,
                () -> jobService.bulkDeleteJobs(EMAIL, List.of(11L, 12L))
        );
        verify(jobMapper, never()).deleteByIdAndUserId(any(), any());
    }

    private void assertRejectedCountMatches(TailoredResumeResponse response, int expected) {
        assertEquals(expected, response.getRejectedChangeCount());
        assertEquals(expected, response.getRejectedChanges().size());
        assertEquals(response.getRejectedChanges().size(), response.getRejectedChangeCount());
    }

    private void stubOwnedResources() {
        stubCurrentUserAndJob();

        Resume resume = new Resume();
        resume.setId(3L);
        resume.setUserId(1L);
        resume.setFileType("docx");
        resume.setFilePath(resumeFile.toString());
        when(resumeMapper.findById(3L)).thenReturn(resume);
    }

    private void stubCurrentUserAndJob() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);

        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Need Spring Boot security experience.");
        when(jobMapper.findById(10L)).thenReturn(job);
    }

    private Job greenhouseJob(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(1L);
        job.setCompany("Airbnb");
        job.setTitle("Engineer " + id);
        job.setSource("GREENHOUSE");
        job.setStatus("SAVED");
        return job;
    }

    private List<ResumeDocumentNode> primeDealsNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", P16_ORIGINAL),
                new ResumeDocumentNode("p-17", "PARAGRAPH", P17_ORIGINAL)
        );
    }

    private List<ResumeDocumentNode> experienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT),
                new ResumeDocumentNode("p-24", "PARAGRAPH", NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> singleExperienceNode() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> awsExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-33", "PARAGRAPH", AWS_NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> crossNodeExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "●   Build REST APIs in Java."),
                new ResumeDocumentNode("p-18", "PARAGRAPH", AWS_NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> crossEntryExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-15", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "●   Build REST APIs in Java."),
                new ResumeDocumentNode("p-17", "PARAGRAPH", "Data Analyst | Other Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-18", "PARAGRAPH", AWS_NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> sameEntryExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-15", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "●   Build REST APIs in Java."),
                new ResumeDocumentNode("p-17", "PARAGRAPH", AWS_NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> implementationAndGrammarNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Backend Intern | Acme | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements")
        );
    }

    private List<ResumeDocumentNode> threeEntryExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-10", "PARAGRAPH", "Backend Intern | Acme | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "●   Design Java REST APIs for billing services."),
                new ResumeDocumentNode("p-11", "PARAGRAPH", "Data Analyst | Other Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "●   Build Python scripts for reporting dashboards."),
                new ResumeDocumentNode("p-12", "PARAGRAPH", "SWE Intern | Third Co | Jan 2022 – Dec 2022"),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "●   Implement SQL reports for finance operations.")
        );
    }

    private List<ResumeDocumentNode> manyExperienceNodes(int bullets) {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        nodes.add(new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"));
        for (int i = 1; i <= bullets; i++) {
            nodes.add(new ResumeDocumentNode("p-" + i, "PARAGRAPH", bulletText(i)));
        }
        return nodes;
    }

    private String bulletText(int index) {
        return "●   Developed backend feature number " + index + " for production services.";
    }

    private String rewrittenBullet(int index) {
        return "●   Developed backend feature number " + index + " for live production services.";
    }

    private String conservativeRewrite(String original) {
        String text = original == null ? "" : original.strip();
        if (text.contains("Java REST APIs")) {
            return "●   Developed Java REST APIs for billing services in production.";
        }
        if (text.contains("Python scripts")) {
            return "●   Built Python scripts for reporting dashboards in production.";
        }
        if (text.contains("SQL reports")) {
            return "●   Implemented SQL reports for finance operations in production.";
        }
        if (text.contains("React checkout")) {
            return "●   Created React checkout pages for retail customers in production.";
        }
        if (text.contains("Redis caching")) {
            return "●   Deployed Redis caching for session lookups in production.";
        }
        if (text.contains("MySQL indexes")) {
            return "●   Tuned MySQL indexes for order search queries in production.";
        }
        if (text.contains("Spring Boot")) {
            return P16_AI;
        }
        if (text.contains("RESTful APIs")) {
            return "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.";
        }
        if (text.contains("Maven project")) {
            return "Developed a Java Maven project that helps users select optimal components within a given budget.";
        }
        if (text.contains("algorithm")) {
            return "Implemented an algorithm that dynamically suggests CPU, GPU, and RAM components.";
        }
        if (text.contains("AWS")) {
            return AWS_POLISH_TEXT;
        }
        return text;
    }

    private TailorChange verifiedChange(String nodeId, String originalText, String newText) {
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(originalText);
        change.setNewText(newText);
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
        change.setApplied(false);
        change.setEvidenceNodeIds(List.of(nodeId));
        change.setEvidenceQuote(originalText);
        return change;
    }

    private TailorChange tooLongChange(ResumeDocumentNode node) {
        return verifiedChange(
                node.getNodeId(),
                node.getText(),
                node.getText() + " showcasing ability to deliver highly detailed additional context."
        );
    }

    private void stubConstrainedByNode(
            java.util.function.Function<ResumeDocumentNode, TailorChange> factory) {
        doAnswer(invocation -> {
            ResumeDocumentNode node = invocation.getArgument(0);
            TailorChange change = factory.apply(node);
            ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
            candidate.setNewText(change.getNewText());
            candidate.setReason(change.getReason());
            ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
            response.setNodeId(node.getNodeId());
            response.setCandidates(List.of(candidate));
            return response;
        }).when(aiResumeService)
                .generateConstrainedTailorCandidates(
                        any(), any(), any(), nullable(ConservativeRewriteRepair.class));
    }

    private TailorChange inferredChange(String nodeId, String originalText, String newText) {
        TailorChange change = verifiedChange(nodeId, originalText, newText);
        change.setEvidenceType(TailorChange.EVIDENCE_INFERRED);
        change.setInferredSkill("Redis");
        change.setRequiresConfirmation(true);
        change.setApplied(false);
        change.setEvidenceNodeIds(List.of());
        change.setEvidenceQuote(null);
        return change;
    }

    private ConservativeRewriteCandidatesResponse asCandidates(TailorChange change) {
        ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
        candidate.setNewText(change == null ? null : change.getNewText());
        candidate.setReason(change == null ? null : change.getReason());
        ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
        if (change != null) {
            response.setNodeId(change.getNodeId());
        }
        response.setCandidates(List.of(candidate));
        return response;
    }

    private ConservativeRewriteCandidatesResponse candidates(ConservativeRewriteCandidate... values) {
        ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
        response.setNodeId("p-23");
        response.setCandidates(List.of(values));
        return response;
    }

    private ConservativeRewriteCandidate candidate(String newText, boolean recommended) {
        ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
        candidate.setNewText(newText);
        candidate.setReason("Improved verb tense and clarified the development work.");
        candidate.setRecommendedByGenerator(recommended);
        return candidate;
    }

    private ConservativeRewriteCandidate lowValueCandidate(String newText) {
        ConservativeRewriteCandidate candidate = candidate(newText, false);
        candidate.setReason("Minor wording tweak");
        return candidate;
    }

    private ConservativeRewriteCandidate recommendedCandidate(String newText) {
        return candidate(newText, true);
    }

    private void stubInsertProposal(long id) {
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(id);
            return 1;
        });
    }

    private void stubConfirmSuccess(TailorProposal proposal) {
        when(tailorProposalMapper.claimPending(proposal.getId(), 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.findById(proposal.getId())).thenReturn(proposal);
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve(TAILORED_FILE_NAME));
        when(tailorProposalMapper.markConfirmed(eq(proposal.getId()), any(), any())).thenReturn(1);
    }

    private TailorProposal pendingProposal(
            Long userId,
            Long jobId,
            Long resumeId,
            List<TailorChange> changes) {

        TailorProposal proposal = new TailorProposal();
        proposal.setId(7L);
        proposal.setUserId(userId);
        proposal.setJobId(jobId);
        proposal.setResumeId(resumeId);
        proposal.setMode(TailorMode.AGGRESSIVE.name());
        proposal.setStatus(TailorProposal.STATUS_PENDING);
        proposal.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
        proposal.setChangesJson(toJson(changes));
        proposal.setRejectedJson("[]");
        return proposal;
    }

    private Boolean appliedFor(TailoredResumeResponse response, String nodeId) {
        return response.getChanges().stream()
                .filter(change -> nodeId.equals(change.getNodeId()))
                .findFirst()
                .map(TailorChange::getApplied)
                .orElse(null);
    }

    private String evidenceTypeFor(TailoredResumeResponse response, String nodeId) {
        return response.getChanges().stream()
                .filter(change -> nodeId.equals(change.getNodeId()))
                .findFirst()
                .map(TailorChange::getEvidenceType)
                .orElse(null);
    }

    private void stubJobDescription(String description) {
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription(description);
        when(jobMapper.findById(10L)).thenReturn(job);
    }

    private void stubContinueLock(TailorProposal proposal) {
        when(tailorProposalMapper.claimPending(proposal.getId(), 1L, 10L)).thenAnswer(invocation -> {
            synchronized (proposal) {
                if (TailorProposal.STATUS_PENDING.equals(proposal.getStatus())) {
                    proposal.setStatus(TailorProposal.STATUS_CONFIRMING);
                    return 1;
                }
                return 0;
            }
        });
        when(tailorProposalMapper.findById(proposal.getId())).thenReturn(proposal);
        when(tailorProposalMapper.updateConfirmingContents(eq(proposal.getId()), any(), any()))
                .thenAnswer(invocation -> {
                    synchronized (proposal) {
                        if (!TailorProposal.STATUS_CONFIRMING.equals(proposal.getStatus())) {
                            return 0;
                        }
                        proposal.setChangesJson(invocation.getArgument(1));
                        proposal.setRejectedJson(invocation.getArgument(2));
                        return 1;
                    }
                });
        when(tailorProposalMapper.restorePending(proposal.getId())).thenAnswer(invocation -> {
            synchronized (proposal) {
                if (!TailorProposal.STATUS_CONFIRMING.equals(proposal.getStatus())) {
                    return 0;
                }
                proposal.setStatus(TailorProposal.STATUS_PENDING);
                return 1;
            }
        });
    }

    private TailorProposal pendingEnvelope(List<TailorChange> changes, List<String> exploredNodeIds) {
        TailorProposal proposal = pendingProposal(1L, 10L, 3L, changes);
        proposal.setMode(TailorMode.CONSERVATIVE.name());
        TailorProposalState state = TailorProposalState.fromChanges(changes, exploredNodeIds);
        state.setHasMoreSuggestions(Boolean.TRUE);
        proposal.setChangesJson(toJson(state));
        return proposal;
    }

    private List<ResumeDocumentNode> appendContinueNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-10", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-11", "PARAGRAPH",
                        "●   Developed Java REST APIs for billing services."),
                new ResumeDocumentNode("p-40", "PARAGRAPH", "SWE Intern | Python Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-12", "PARAGRAPH", P16_ORIGINAL),
                new ResumeDocumentNode("p-20", "PARAGRAPH", "Frontend Intern | Retail Co | Jan 2022 – Dec 2022"),
                new ResumeDocumentNode("p-22", "PARAGRAPH",
                        "●   Created React checkout pages for retail customers."),
                new ResumeDocumentNode("p-50", "PARAGRAPH", "DBA Intern | Data Co | Jan 2021 – Dec 2021"),
                new ResumeDocumentNode("p-32", "PARAGRAPH",
                        "●   Tuned MySQL indexes for order search queries."),
                new ResumeDocumentNode("p-60", "PARAGRAPH", "Cloud Intern | Cloud Co | Jan 2020 – Dec 2020"),
                new ResumeDocumentNode("p-31", "PARAGRAPH", AWS_NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> eightDistinctExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-10", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-11", "PARAGRAPH",
                        "●   Developed Java REST APIs for billing services."),
                new ResumeDocumentNode("p-12", "PARAGRAPH",
                        "●   Built Python scripts for reporting dashboards."),
                new ResumeDocumentNode("p-20", "PARAGRAPH", "Data Analyst | Other Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-21", "PARAGRAPH",
                        "●   Implemented SQL reports for finance operations."),
                new ResumeDocumentNode("p-22", "PARAGRAPH",
                        "●   Created React checkout pages for retail customers."),
                new ResumeDocumentNode("p-30", "PARAGRAPH", "SWE Intern | Third Co | Jan 2022 – Dec 2022"),
                new ResumeDocumentNode("p-31", "PARAGRAPH",
                        "●   Deployed Redis caching for session lookups."),
                new ResumeDocumentNode("p-32", "PARAGRAPH",
                        "●   Tuned MySQL indexes for order search queries.")
        );
    }

    private List<ResumeDocumentNode> lockedPrimeAndSmartPcNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "PROJECTS"),
                new ResumeDocumentNode("p-15", "PARAGRAPH", "Prime Deals | Personal Project | 2024"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", P16_ORIGINAL),
                new ResumeDocumentNode("p-17", "PARAGRAPH", P17_ORIGINAL),
                new ResumeDocumentNode("p-20", "PARAGRAPH", "SmartPC | Personal Project | 2023"),
                new ResumeDocumentNode("p-21", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget"),
                new ResumeDocumentNode("p-23", "PARAGRAPH",
                        "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)"),
                new ResumeDocumentNode("p-30", "PARAGRAPH",
                        "Tuned MySQL indexes to speed up order search queries.")
        );
    }

    private static Map<String, String> snapshotUploadsHashes() throws Exception {
        Path root = Path.of("uploads");
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        java.util.TreeMap<String, String> snapshot = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String hash;
                try {
                    hash = java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
                } catch (Exception ex) {
                    hash = "locked";
                }
                snapshot.put(
                        root.relativize(file).toString().replace('\\', '/'),
                        Files.size(file) + "|" + hash
                );
            }
        }
        return snapshot;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Path createOwnedTailoredFile(String fileName, String contents) throws Exception {
        Path file = storagePathResolver.tailoredJobDirectory(1L, 10L).resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private static int countFiles(Path directory) throws Exception {
        return snapshotFiles(directory).size();
    }

    private static Set<String> snapshotFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }
}
