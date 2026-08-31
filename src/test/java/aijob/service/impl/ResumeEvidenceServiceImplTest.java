package aijob.service.impl;

import aijob.dto.AnswerStatus;
import aijob.dto.RequirementCategory;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.entity.Resume;
import aijob.entity.ResumeEvidenceItem;
import aijob.entity.User;
import aijob.exception.EvidenceConflictException;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.exception.ResumeNotFoundException;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeEvidenceService;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeRequirementMatcherTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeEvidenceServiceImplTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private UserMapper userMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private ResumeEvidenceMapper resumeEvidenceMapper;
    @Mock
    private ResumeDocumentService resumeDocumentService;

    @TempDir
    Path tempDir;

    private ResumeEvidenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ResumeEvidenceServiceImpl(
                userMapper,
                resumeMapper,
                resumeEvidenceMapper,
                new ResumeAnalysisInputFactory(resumeMapper, resumeDocumentService, new ObjectMapper()),
                new ObjectMapper()
        );
    }

    @Test
    void topicKeyIsNormalizedByServer() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenReturn(null);

        SaveResumeEvidenceRequest request = usedDocker("  Docker  ", "PROJECT:prime-deals", List.of("p-13", "p-16"));
        service.save(EMAIL, 9L, request);

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper).insert(captor.capture());
        assertEquals("docker", captor.getValue().getTopicKey());
        assertEquals("Docker", captor.getValue().getTopicLabel());
    }

    @Test
    void sameTopicAndEntryUpdatesInsteadOfInserting() {
        stubOwnedDocx();
        ResumeEvidenceItem existing = new ResumeEvidenceItem();
        existing.setId(12L);
        existing.setTopicKey("docker");
        existing.setEntryKey("PROJECT:prime-deals");
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenReturn(existing);

        ResumeEvidenceService.SaveResult result = service.save(EMAIL, 9L, usedDocker(
                "Docker", "PROJECT:prime-deals", List.of("p-16")));

        assertFalse(result.created());
        verify(resumeEvidenceMapper).updateByTopicAndEntry(any());
        verify(resumeEvidenceMapper, never()).insert(any());
    }

    @Test
    void retrySameTopicAndEntryLeavesOneEffectiveRow() {
        stubOwnedDocx();
        ResumeEvidenceItem[] row = new ResumeEvidenceItem[1];
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenAnswer(invocation -> row[0]);
        when(resumeEvidenceMapper.insert(any())).thenAnswer(invocation -> {
            ResumeEvidenceItem item = invocation.getArgument(0);
            ResumeEvidenceItem stored = new ResumeEvidenceItem();
            stored.setId(12L);
            stored.setUserId(item.getUserId());
            stored.setResumeId(item.getResumeId());
            stored.setTopicKey(item.getTopicKey());
            stored.setEntryKey(item.getEntryKey());
            stored.setAnswerStatus(item.getAnswerStatus());
            stored.setActionText(item.getActionText());
            row[0] = stored;
            return 1;
        });
        when(resumeEvidenceMapper.updateByTopicAndEntry(any())).thenAnswer(invocation -> {
            ResumeEvidenceItem item = invocation.getArgument(0);
            ResumeEvidenceItem stored = new ResumeEvidenceItem();
            stored.setId(item.getId());
            stored.setUserId(item.getUserId());
            stored.setResumeId(item.getResumeId());
            stored.setTopicKey(item.getTopicKey());
            stored.setEntryKey(item.getEntryKey());
            stored.setAnswerStatus(item.getAnswerStatus());
            stored.setActionText(item.getActionText());
            row[0] = stored;
            return 1;
        });

        ResumeEvidenceService.SaveResult first = service.save(
                EMAIL, 9L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));
        SaveResumeEvidenceRequest retry = usedDocker("Docker", "PROJECT:prime-deals", List.of("p-13", "p-16"));
        retry.setAction("Containerized Prime Deals with Docker Compose");
        ResumeEvidenceService.SaveResult second = service.save(EMAIL, 9L, retry);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(12L, first.item().getId());
        assertEquals(12L, second.item().getId());
        assertEquals("Containerized Prime Deals with Docker Compose", second.item().getAction());
        assertEquals(12L, row[0].getId());
        verify(resumeEvidenceMapper, times(1)).insert(any());
        verify(resumeEvidenceMapper, times(1)).updateByTopicAndEntry(any());
    }

    @Test
    void relatedNodeIdsAcrossEntriesAreRejected() {
        stubOwnedDocx();
        SaveResumeEvidenceRequest request = usedDocker(
                "Docker", "PROJECT:prime-deals", List.of("p-13", "p-19"));
        assertThrows(InvalidEvidenceRequestException.class, () -> service.save(EMAIL, 9L, request));
        verify(resumeEvidenceMapper, never()).insert(any());
    }

    @Test
    void unknownNodeIdIsRejected() {
        stubOwnedDocx();
        SaveResumeEvidenceRequest request = usedDocker(
                "Docker", "PROJECT:prime-deals", List.of("missing-node"));
        assertThrows(InvalidEvidenceRequestException.class, () -> service.save(EMAIL, 9L, request));
    }

    @Test
    void otherUsersResumeIs404() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L));
        Resume foreign = docxResume();
        foreign.setUserId(2L);
        when(resumeMapper.findById(9L)).thenReturn(foreign);
        assertThrows(ResumeNotFoundException.class, () -> service.list(EMAIL, 9L));
    }

    @Test
    void templateChildMapsToParentUpload() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L));
        Resume parent = docxResume();
        parent.setOrigin("UPLOAD");
        Resume child = docxResume();
        child.setId(20L);
        child.setOrigin("TEMPLATE");
        child.setParentResumeId(9L);
        when(resumeMapper.findById(20L)).thenReturn(child);
        when(resumeMapper.findById(9L)).thenReturn(parent);
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenReturn(null);

        service.save(EMAIL, 20L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getResumeId());
    }

    @Test
    void savingUsedClearsNotUsedForSameTopic() {
        stubOwnedDocx();
        ResumeEvidenceItem rejected = new ResumeEvidenceItem();
        rejected.setId(8L);
        rejected.setTopicKey("docker");
        rejected.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(rejected));
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenReturn(null);

        service.save(EMAIL, 9L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));

        verify(resumeEvidenceMapper).deleteNotUsedByTopic(1L, 9L, "docker");
        verify(resumeEvidenceMapper).insert(any());
    }

    @Test
    void savingNotUsedWhenUsedExistsThrowsConflict() {
        stubUserAndResume();
        ResumeEvidenceItem used = new ResumeEvidenceItem();
        used.setId(12L);
        used.setTopicKey("docker");
        used.setTopicLabel("Docker");
        used.setCategory(RequirementCategory.HARD_SKILL);
        used.setActionText("Containerized the Spring Boot application with Docker");
        used.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(used));

        SaveResumeEvidenceRequest request = notUsed("Docker");
        assertThrows(EvidenceConflictException.class, () -> service.save(EMAIL, 9L, request));
        verify(resumeEvidenceMapper, never()).insert(any());
        verify(resumeEvidenceMapper, never()).deleteNotUsedByTopic(any(), any(), any());
    }

    @Test
    void differentEntriesCanSaveMultipleUsedItems() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of());
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:prime-deals"))
                .thenReturn(null);
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "docker", "PROJECT:smartpc"))
                .thenReturn(null);

        service.save(EMAIL, 9L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));
        service.save(EMAIL, 9L, usedDocker("Docker", "PROJECT:smartpc", List.of("p-19")));

        verify(resumeEvidenceMapper, times(2)).insert(any());
        verify(resumeEvidenceMapper, times(2))
                .deleteNotUsedByTopic(1L, 9L, "docker");
    }

    @Test
    void confirmedMustBeTrue() {
        stubUserAndResume();
        SaveResumeEvidenceRequest request = usedDocker("Docker", "GENERAL", List.of());
        request.setConfirmed(false);
        assertThrows(InvalidEvidenceRequestException.class, () -> service.save(EMAIL, 9L, request));
    }

    @Test
    void evidenceNotOnPathResumeIs404() {
        stubUserAndResume();
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setId(12L);
        item.setUserId(1L);
        item.setResumeId(99L);
        when(resumeEvidenceMapper.findByIdAndUser(12L, 1L)).thenReturn(item);
        assertThrows(ResumeNotFoundException.class, () -> service.delete(EMAIL, 9L, 12L));
        verify(resumeEvidenceMapper, never()).deleteByIdAndUser(any(), any());
    }

    @Test
    void deleteWrongUserIs404() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(2L));
        when(resumeMapper.findById(9L)).thenReturn(docxResume());
        assertThrows(ResumeNotFoundException.class, () -> service.delete(EMAIL, 9L, 12L));
        verify(resumeEvidenceMapper, never()).deleteByIdAndUser(any(), any());
    }

    @Test
    void deleteOwnedEvidenceRemovesRowOnly() {
        stubOwnedDocx();
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setId(12L);
        item.setUserId(1L);
        item.setResumeId(9L);
        when(resumeEvidenceMapper.findByIdAndUser(12L, 1L)).thenReturn(item);
        service.delete(EMAIL, 9L, 12L);
        verify(resumeEvidenceMapper).deleteByIdAndUser(12L, 1L);
    }

    private void stubOwnedDocx() {
        stubUserAndResume();
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
    }

    private void stubUserAndResume() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L));
        when(resumeMapper.findById(9L)).thenReturn(docxResume());
    }

    @Test
    void debugMvcSaveIsRejected() {
        stubOwnedDocx();
        SaveResumeEvidenceRequest request = used(
                "debug",
                RequirementCategory.RESPONSIBILITY,
                "GENERAL",
                List.of(),
                "Designed Spring Boot application using MVC architecture"
        );
        InvalidEvidenceRequestException ex = assertThrows(
                InvalidEvidenceRequestException.class,
                () -> service.save(EMAIL, 9L, request)
        );
        assertTrue(ex.getMessage().contains("EVIDENCE_TOPIC_MISMATCH"));
        verify(resumeEvidenceMapper, never()).insert(any());
    }

    @Test
    void debugFixedBugSaveIsAccepted() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "debug", "GENERAL")).thenReturn(null);
        SaveResumeEvidenceRequest request = used(
                "debug",
                RequirementCategory.RESPONSIBILITY,
                "GENERAL",
                List.of(),
                "Fixed an authentication bug in Spring Boot"
        );
        service.save(EMAIL, 9L, request);
        verify(resumeEvidenceMapper).insert(any());
    }

    @Test
    void unknownPrefixedNodeIdsAreRejected() {
        stubOwnedDocx();
        SaveResumeEvidenceRequest request = usedDocker(
                "Docker", "PROJECT:prime-deals", List.of("p-999", "b-p-not-real", "t-99"));
        InvalidEvidenceRequestException ex = assertThrows(
                InvalidEvidenceRequestException.class,
                () -> service.save(EMAIL, 9L, request)
        );
        assertTrue(ex.getMessage().contains("EVIDENCE_NODE_NOT_FOUND"));
        verify(resumeEvidenceMapper, never()).insert(any());
    }

    @Test
    void enhancedMetricConfirmedUsedSavesWithoutRequiringTheWordMetric() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "metric", "PROJECT:prime-deals"))
                .thenReturn(null);

        SaveResumeEvidenceRequest request = used(
                "metric",
                RequirementCategory.HARD_SKILL,
                "PROJECT:prime-deals",
                List.of(),
                "handled about 2000 daily checkout users after launch");
        ResumeEvidenceService.SaveResult result = service.save(EMAIL, 9L, request);

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper).insert(captor.capture());
        assertEquals("metric", captor.getValue().getTopicKey());
        assertEquals("metric", captor.getValue().getTopicLabel());
        assertEquals("PROJECT:prime-deals", captor.getValue().getEntryKey());
        assertEquals(AnswerStatus.CONFIRMED_USED, captor.getValue().getAnswerStatus());
        assertEquals("USER_CONFIRMED", result.item().getProvenance());
    }

    @Test
    void enhancedMetricConfirmedNotUsedIsUserRejected() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "metric", "GENERAL"))
                .thenReturn(null);

        ResumeEvidenceService.SaveResult result = service.save(EMAIL, 9L, notUsed("metric"));

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper).insert(captor.capture());
        assertEquals("metric", captor.getValue().getTopicKey());
        assertEquals(AnswerStatus.CONFIRMED_NOT_USED, captor.getValue().getAnswerStatus());
        assertEquals("USER_REJECTED", result.item().getProvenance());
    }

    @Test
    void enhancedMetricReanswerUpdatesTheSameRow() {
        stubOwnedDocx();
        ResumeEvidenceItem[] row = new ResumeEvidenceItem[1];
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "metric", "PROJECT:prime-deals"))
                .thenAnswer(invocation -> row[0]);
        when(resumeEvidenceMapper.insert(any())).thenAnswer(invocation -> {
            ResumeEvidenceItem item = invocation.getArgument(0);
            ResumeEvidenceItem stored = new ResumeEvidenceItem();
            stored.setId(70L);
            stored.setUserId(item.getUserId());
            stored.setResumeId(item.getResumeId());
            stored.setTopicKey(item.getTopicKey());
            stored.setTopicLabel(item.getTopicLabel());
            stored.setCategory(item.getCategory());
            stored.setEntryKey(item.getEntryKey());
            stored.setAnswerStatus(item.getAnswerStatus());
            stored.setActionText(item.getActionText());
            row[0] = stored;
            return 1;
        });
        when(resumeEvidenceMapper.updateByTopicAndEntry(any())).thenAnswer(invocation -> {
            ResumeEvidenceItem item = invocation.getArgument(0);
            ResumeEvidenceItem stored = new ResumeEvidenceItem();
            stored.setId(item.getId());
            stored.setUserId(item.getUserId());
            stored.setResumeId(item.getResumeId());
            stored.setTopicKey(item.getTopicKey());
            stored.setTopicLabel(item.getTopicLabel());
            stored.setCategory(item.getCategory());
            stored.setEntryKey(item.getEntryKey());
            stored.setAnswerStatus(item.getAnswerStatus());
            stored.setActionText(item.getActionText());
            row[0] = stored;
            return 1;
        });

        SaveResumeEvidenceRequest first = used(
                "metric",
                RequirementCategory.HARD_SKILL,
                "PROJECT:prime-deals",
                List.of(),
                "handled about 2000 daily checkout users after launch");
        ResumeEvidenceService.SaveResult created = service.save(EMAIL, 9L, first);
        SaveResumeEvidenceRequest retry = used(
                "metric",
                RequirementCategory.HARD_SKILL,
                "PROJECT:prime-deals",
                List.of(),
                "processed about 1500 checkout requests per day");
        ResumeEvidenceService.SaveResult updated = service.save(EMAIL, 9L, retry);

        assertTrue(created.created());
        assertFalse(updated.created());
        assertEquals(70L, created.item().getId());
        assertEquals(70L, updated.item().getId());
        assertEquals("processed about 1500 checkout requests per day", updated.item().getAction());
        verify(resumeEvidenceMapper, times(1)).insert(any());
        verify(resumeEvidenceMapper, times(1)).updateByTopicAndEntry(any());
    }

    @Test
    void englishMetricAskableCopyIsNotRemappedAndIsRejected() {
        stubOwnedDocx();
        SaveResumeEvidenceRequest request = used(
                "A confirmable performance or volume figure",
                RequirementCategory.HARD_SKILL,
                "PROJECT:prime-deals",
                List.of(),
                "handled about 2000 daily checkout users after launch");
        InvalidEvidenceRequestException ex = assertThrows(
                InvalidEvidenceRequestException.class,
                () -> service.save(EMAIL, 9L, request));
        assertTrue(ex.getMessage().contains("EVIDENCE_TOPIC_MISMATCH"));
        verify(resumeEvidenceMapper, never()).insert(any());
    }

    @Test
    void maintainConfirmedUsedStillSaves() {
        stubOwnedDocx();
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 9L, "maintain", "PROJECT:prime-deals"))
                .thenReturn(null);

        service.save(EMAIL, 9L, used(
                "maintain",
                RequirementCategory.HARD_SKILL,
                "PROJECT:prime-deals",
                List.of(),
                "maintained the AWS application with weekly patching"));

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper).insert(captor.capture());
        assertEquals("maintain", captor.getValue().getTopicKey());
        assertEquals(RequirementCategory.HARD_SKILL, captor.getValue().getCategory());
    }

    @Test
    void parentAndChildShareRootAndEntryCatalog() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L));
        Resume parent = pdfParent();
        Resume child = templateChild(parent);
        when(resumeMapper.findById(21L)).thenReturn(parent);
        when(resumeMapper.findById(22L)).thenReturn(child);
        when(resumeDocumentService.parseDocx(any())).thenReturn(ResumeRequirementMatcherTest.yaohangNodes());
        when(resumeEvidenceMapper.findByTopicAndEntry(1L, 21L, "docker", "PROJECT:prime-deals"))
                .thenReturn(null);

        service.save(EMAIL, 21L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));
        service.save(EMAIL, 22L, usedDocker("Docker", "PROJECT:prime-deals", List.of("p-16")));

        ArgumentCaptor<ResumeEvidenceItem> captor = ArgumentCaptor.forClass(ResumeEvidenceItem.class);
        verify(resumeEvidenceMapper, times(2)).insert(captor.capture());
        assertEquals(21L, captor.getAllValues().get(0).getResumeId());
        assertEquals(21L, captor.getAllValues().get(1).getResumeId());
    }

    @Test
    void oldInvalidDebugEvidenceIsNeedsReviewOnList() {
        stubOwnedDocx();
        ResumeEvidenceItem stale = new ResumeEvidenceItem();
        stale.setId(44L);
        stale.setResumeId(9L);
        stale.setTopicKey("debug");
        stale.setTopicLabel("debug");
        stale.setCategory(RequirementCategory.RESPONSIBILITY);
        stale.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        stale.setActionText("Designed Spring Boot application using MVC architecture");
        when(resumeEvidenceMapper.findByResumeAndUser(9L, 1L)).thenReturn(List.of(stale));

        var listed = service.list(EMAIL, 9L);
        assertEquals(1, listed.size());
        assertEquals("NEEDS_REVIEW", listed.get(0).getValidationStatus());
        assertTrue(listed.get(0).getValidationReasonCode().contains("EVIDENCE_TOPIC_MISMATCH"));
    }

    @Test
    void user2CannotListUser1Evidence() {
        when(userMapper.findByEmail("user2@example.com")).thenReturn(user(2L));
        Resume ownedByOne = docxResume();
        ownedByOne.setUserId(1L);
        when(resumeMapper.findById(9L)).thenReturn(ownedByOne);
        assertThrows(ResumeNotFoundException.class, () -> service.list("user2@example.com", 9L));
    }

    private SaveResumeEvidenceRequest used(
            String topic,
            String category,
            String entryKey,
            List<String> nodeIds,
            String action) {
        SaveResumeEvidenceRequest request = new SaveResumeEvidenceRequest();
        request.setTopic(topic);
        request.setCategory(category);
        request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        request.setEntryKey(entryKey);
        request.setRelatedNodeIds(nodeIds);
        request.setContext("Prime Deals personal project");
        request.setAction(action);
        request.setResult("Shipped internally");
        request.setConfirmed(true);
        return request;
    }

    private SaveResumeEvidenceRequest notUsed(String topic) {
        SaveResumeEvidenceRequest request = new SaveResumeEvidenceRequest();
        request.setTopic(topic);
        request.setCategory(RequirementCategory.HARD_SKILL);
        request.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
        request.setEntryKey("GENERAL");
        request.setRelatedNodeIds(List.of());
        request.setContext("");
        request.setAction("");
        request.setResult("");
        request.setMetric("");
        request.setConfirmed(true);
        return request;
    }

    private SaveResumeEvidenceRequest usedDocker(String topic, String entryKey, List<String> nodeIds) {
        return used(topic, RequirementCategory.HARD_SKILL, entryKey, nodeIds,
                "Containerized the Spring Boot application with Docker");
    }

    private Resume pdfParent() {
        Resume parent = docxResume();
        parent.setId(21L);
        parent.setFileType("pdf");
        parent.setOrigin("UPLOAD");
        parent.setCanonicalResumeId(22L);
        parent.setExtractedText("Yaohang Tang\nJava Spring Boot");
        return parent;
    }

    private Resume templateChild(Resume parent) {
        Resume child = docxResume();
        child.setId(22L);
        child.setOrigin("TEMPLATE");
        child.setParentResumeId(parent.getId());
        child.setFileType("docx");
        child.setReviewStatus("READY");
        return child;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail(EMAIL);
        return user;
    }

    private Resume docxResume() {
        try {
            Path file = tempDir.resolve("resume.docx");
            if (!Files.exists(file)) {
                Files.writeString(file, "placeholder");
            }
            Resume resume = new Resume();
            resume.setId(9L);
            resume.setUserId(1L);
            resume.setFileType("docx");
            resume.setFilePath(file.toString());
            resume.setOrigin("UPLOAD");
            resume.setExtractedText("Yaohang Tang\nJava Spring Boot");
            return resume;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
