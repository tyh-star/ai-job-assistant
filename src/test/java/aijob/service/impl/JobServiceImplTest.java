package aijob.service.impl;

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
        List<ResumeDocumentNode> nodes = experienceNodes();
        TailorChange first = verifiedChange("p-23", NODE_TEXT, NEW_TEXT);
        TailorChange second = verifiedChange("p-23", NODE_TEXT, NEW_TEXT_SECOND);

        List<TailorChange> accepted = jobService.filterSafeChanges(
                nodes,
                List.of(first, second),
                TailorMode.CONSERVATIVE
        );

        assertEquals(1, accepted.size());
        assertEquals(NEW_TEXT, accepted.get(0).getNewText());
        assertEquals(Boolean.TRUE, accepted.get(0).getApplied());
    }

    @Test
    void filterSafeChanges_emptyProposed_returnsEmpty() {
        List<TailorChange> accepted = jobService.filterSafeChanges(
                experienceNodes(),
                List.of(),
                TailorMode.CONSERVATIVE
        );

        assertTrue(accepted.isEmpty());
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
        assertEquals(1, response.getRejectedChangeCount());
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
        assertEquals(0, response.getRejectedChangeCount());
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
        assertEquals(0, response.getRejectedChangeCount());
        assertEquals(1, response.getChanges().size());
        assertEquals(Boolean.FALSE, response.getChanges().get(0).getApplied());
        verify(resumeDocxTailorService).createTailoredDocx(any(), eq(List.of()), eq(1L), eq(10L));
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
        assertEquals(0, response.getRejectedChangeCount());
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
        assertEquals(2, response.getRejectedChangeCount());
        assertFalse(response.isUnchanged());
        assertEquals(1, response.getChanges().size());
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
                new ResumeDocumentNode("p-23", "PARAGRAPH", NODE_TEXT)
        );
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
