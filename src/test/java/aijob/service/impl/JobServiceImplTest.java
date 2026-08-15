package aijob.service.impl;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.enums.TailorMode;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.TailorProposalNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
class JobServiceImplTest {

    private static final String EMAIL = "user@example.com";
    private static final String NODE_TEXT =
            "●   Developed and integrated IP rate-limiting features.";
    private static final String NEW_TEXT =
            "●   Developed Spring Boot security features using IP rate-limiting.";
    private static final String NEW_TEXT_SECOND =
            "●   Developed Redis caching and IP rate-limiting features.";
    private static final String AWS_NODE_TEXT =
            "●   Deployed backend services on AWS for production workloads.";
    private static final String AWS_HA_TEXT =
            "●   Deployed backend services on AWS with high availability and scalability.";
    private static final String AWS_POLISH_TEXT =
            "●   Deployed production backend services on AWS.";

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
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private JobServiceImpl jobService;

    @TempDir
    Path tempDir;

    private Path resumeFile;

    @BeforeEach
    void setUp() throws Exception {
        resumeFile = tempDir.resolve("resume.docx");
        Files.writeString(resumeFile, "placeholder");
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
    void tailorResume_duplicateNodeId_writerReceivesOnlyFirst() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(
                        verifiedChange("p-23", NODE_TEXT, NEW_TEXT),
                        verifiedChange("p-23", NODE_TEXT, NEW_TEXT_SECOND)
                ));
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
        assertEquals(NEW_TEXT, writerChanges.getValue().get(0).getNewText());
        assertEquals(1, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 1);
        assertEquals(RejectedTailorChange.DUPLICATE_NODE_ID,
                response.getRejectedChanges().get(0).getReasonCode());
        assertFalse(response.isUnchanged());
        assertEquals(1, response.getChanges().size());
    }

    @Test
    void tailorResume_emptyChanges_unchangedTrue() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of());
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 0);
        assertTrue(response.getChanges().isEmpty());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
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
    }

    @Test
    void tailorResume_unknownEvidenceType_isRejectedAndNotWritten() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        TailorChange unknown = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        unknown.setEvidenceType("LIKELY");
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(unknown));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 1);
        assertEquals(RejectedTailorChange.UNKNOWN_EVIDENCE_TYPE,
                response.getRejectedChanges().get(0).getReasonCode());
        assertTrue(response.getChanges().isEmpty());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
    }

    @Test
    void tailorResume_conservativeInferred_isRejected() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        TailorChange inferred = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        inferred.setEvidenceType(TailorChange.EVIDENCE_INFERRED);
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(inferred));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 1);
        assertEquals(RejectedTailorChange.INFERRED_NOT_ALLOWED,
                response.getRejectedChanges().get(0).getReasonCode());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
    }

    @Test
    void tailorResume_elevenVerified_writesFirstTenAndRejectsEleventh() {
        stubOwnedResources();
        List<ResumeDocumentNode> nodes = manyExperienceNodes(11);
        List<TailorChange> proposed = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            proposed.add(verifiedChange(
                    "p-" + i,
                    bulletText(i),
                    rewrittenBullet(i)
            ));
        }
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(proposed);
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TailorChange>> writerChanges =
                ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(
                any(), writerChanges.capture(), eq(1L), eq(10L));

        assertEquals(10, writerChanges.getValue().size());
        assertEquals(10, response.getAppliedChangeCount());
        assertFalse(response.isUnchanged());
        assertEquals(10, response.getChanges().size());
        assertRejectedCountMatches(response, 1);
        assertEquals("p-11", response.getRejectedChanges().get(0).getNodeId());
        assertEquals(
                RejectedTailorChange.MAX_CHANGE_LIMIT_EXCEEDED,
                response.getRejectedChanges().get(0).getReasonCode()
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
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
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
    }

    @Test
    void tailorResume_invalidNodeIdAndOriginalTextMismatch_rejectedChangeCountIsCorrect() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(
                        verifiedChange("p-999", NODE_TEXT, NEW_TEXT),
                        verifiedChange("p-23", "completely different original", NEW_TEXT),
                        verifiedChange("p-23", NODE_TEXT, NEW_TEXT)
                ));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals(1, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 2);
        assertEquals(RejectedTailorChange.UNKNOWN_NODE_ID,
                response.getRejectedChanges().get(0).getReasonCode());
        assertEquals(RejectedTailorChange.ORIGINAL_TEXT_MISMATCH,
                response.getRejectedChanges().get(1).getReasonCode());
        assertFalse(response.isUnchanged());
        assertEquals(1, response.getChanges().size());
    }

    @Test
    void tailorResume_conservative_stillCallsWriterAndDoesNotInsertProposal() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(resumeDocxTailorService).createTailoredDocx(any(), anyList(), eq(1L), eq(10L));
        verify(tailorProposalMapper, never()).insert(any());
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
        assertEquals("out.docx", response.getGeneratedFileName());
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

    private void assertRejectedCountMatches(TailoredResumeResponse response, int expected) {
        assertEquals(expected, response.getRejectedChangeCount());
        assertEquals(expected, response.getRejectedChanges().size());
        assertEquals(response.getRejectedChanges().size(), response.getRejectedChangeCount());
    }

    private void stubOwnedResources() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);

        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Need Spring Boot security experience.");
        when(jobMapper.findById(10L)).thenReturn(job);

        Resume resume = new Resume();
        resume.setId(3L);
        resume.setUserId(1L);
        resume.setFileType("docx");
        resume.setFilePath(resumeFile.toString());
        when(resumeMapper.findById(3L)).thenReturn(resume);
    }

    private List<ResumeDocumentNode> experienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT),
                new ResumeDocumentNode("p-24", "PARAGRAPH", NODE_TEXT)
        );
    }

    private List<ResumeDocumentNode> awsExperienceNodes() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-33", "PARAGRAPH", AWS_NODE_TEXT)
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
        return "●   Developed Spring Boot backend feature number " + index + " for production services.";
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
                .thenReturn(tempDir.resolve("out.docx"));
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
