package aijob.service.impl;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.enums.TailorMode;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    void tailorResume_onlyAggressiveInferred_unchangedTrue() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        TailorChange inferred = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        inferred.setEvidenceType(TailorChange.EVIDENCE_INFERRED);
        inferred.setApplied(true);
        inferred.setInferredSkill("Redis");
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.AGGRESSIVE)))
                .thenReturn(List.of(inferred));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.AGGRESSIVE);

        assertTrue(response.isUnchanged());
        assertEquals(0, response.getAppliedChangeCount());
        assertRejectedCountMatches(response, 0);
        assertEquals(1, response.getChanges().size());
        assertEquals(Boolean.FALSE, response.getChanges().get(0).getApplied());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
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
    void tailorResume_verified_appliedChangeCountIsCorrect() {
        stubOwnedResources();
        when(resumeDocumentService.parseDocx(any())).thenReturn(experienceNodes());
        when(aiResumeService.generateTailorChanges(anyList(), any(), eq(TailorMode.CONSERVATIVE)))
                .thenReturn(List.of(verifiedChange("p-23", NODE_TEXT, NEW_TEXT)));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(tempDir.resolve("out.docx"));

        TailoredResumeResponse response = jobService.tailorResume(
                EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

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
        return change;
    }
}
