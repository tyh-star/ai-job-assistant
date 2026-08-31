package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.config.TailorEngineProperties;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.enums.PdfExportStatus;
import aijob.enums.TailorMode;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.tailor.v3.TailorEngineRouter;
import aijob.tailor.v3.V3BasicRunResult;
import aijob.tailor.v3.V3BasicTailorService;
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
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceImplV3BasicTest {

    private static final String EMAIL = "user@example.com";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private AiResumeService aiResumeService;
    @Mock private ResumeDocumentService resumeDocumentService;
    @Mock private ResumeDocxTailorService resumeDocxTailorService;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private GreenhouseJobClient greenhouseJobClient;
    @Mock private PdfExportService pdfExportService;
    @Mock private V3BasicTailorService v3BasicTailorService;
    @Mock private ResumeAnalysisInputFactory resumeAnalysisInputFactory;

    @TempDir
    Path tempDir;

    private JobServiceImpl jobService;
    private Path childDocx;
    private Path parentPdf;
    private Path generatedDocx;
    private Path generatedPdf;

    @BeforeEach
    void setUp() throws Exception {
        childDocx = tempDir.resolve("canonical.docx");
        parentPdf = tempDir.resolve("yaohang.pdf");
        generatedDocx = tempDir.resolve("out.docx");
        generatedPdf = tempDir.resolve("out.pdf");
        Files.writeString(childDocx, "docx");
        Files.writeString(parentPdf, "pdf");
        Files.writeString(generatedDocx, "generated");
        Files.writeString(generatedPdf, "pdf-out");
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V3");
        TailorEngineRouter router = new TailorEngineRouter(new TailorEngineProperties(env));
        jobService = new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                new ObjectMapper(),
                greenhouseJobClient,
                pdfExportService,
                StoragePathResolver.forRoot(tempDir.resolve("uploads")),
                null,
                null,
                null,
                JobSearchProperties.defaults(),
                router,
                v3BasicTailorService,
                resumeAnalysisInputFactory
        );
        stubOwner();
    }

    @Test
    void v3BasicPdfParentWritesFilesWithoutProposal() {
        Resume selected = resume(8L, "pdf", parentPdf);
        Resume child = resume(9L, "docx", childDocx);
        when(resumeMapper.findById(8L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, child, List.of(), Map.of(), null, 8L));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(primeAndSmart());
        TailorChange change = appliedChange("p-2");
        when(v3BasicTailorService.run(anyList(), any())).thenReturn(new V3BasicRunResult(
                List.of(change), List.of(), 1, 1, 2, 0, 1, List.of()));
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        TailoredResumeResponse response = jobService.tailorResume(EMAIL, 10L, 8L, TailorMode.CONSERVATIVE);

        assertEquals("V3", response.getEngineVersion());
        assertEquals("BASIC", response.getProductMode());
        assertEquals(1, response.getRewrittenEntryCount());
        assertEquals(1, response.getUnchangedEntryCount());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
        assertNull(response.getProposalId());
        assertFalse(response.getDownloadUrl() == null || response.getDownloadUrl().isBlank());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
        verify(tailorProposalMapper, never()).insert(any());
        ArgumentCaptor<List<TailorChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(resumeDocxTailorService).createTailoredDocx(eq(childDocx), captor.capture(), eq(1L), eq(10L));
        assertEquals(1, captor.getValue().size());
        assertEquals("p-2", captor.getValue().get(0).getNodeId());
        verify(pdfExportService).exportPdf(generatedDocx);
    }

    @Test
    void v3BasicNativeDocxUsesSelectedFile() {
        Resume selected = resume(3L, "docx", childDocx);
        when(resumeMapper.findById(3L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, null, List.of(), Map.of(), null, 3L));
        when(resumeDocumentService.parseDocx(childDocx)).thenReturn(primeAndSmart());
        when(v3BasicTailorService.run(anyList(), any())).thenReturn(new V3BasicRunResult(
                List.of(), List.of(), 0, 2, 2, 0, 0, List.of()));
        when(resumeDocxTailorService.createTailoredDocx(eq(childDocx), anyList(), eq(1L), eq(10L)))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(generatedDocx)).thenReturn(generatedPdf);

        TailoredResumeResponse response = jobService.tailorResume(EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        assertEquals("V3", response.getEngineVersion());
        assertEquals("BASIC", response.getProductMode());
        assertEquals(0, response.getRewrittenEntryCount());
        verify(resumeDocxTailorService).createTailoredDocx(eq(childDocx), eq(List.of()), eq(1L), eq(10L));
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());
        verify(tailorProposalMapper, never()).insert(any());
    }

    @Test
    void v2FlagKeepsConservativeGenerator() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V2");
        JobServiceImpl v2 = new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                resumeDocumentService,
                resumeDocxTailorService,
                new ObjectMapper(),
                greenhouseJobClient,
                pdfExportService,
                StoragePathResolver.forRoot(tempDir.resolve("uploads-v2")),
                null,
                null,
                null,
                JobSearchProperties.defaults(),
                new TailorEngineRouter(new TailorEngineProperties(env)),
                v3BasicTailorService,
                resumeAnalysisInputFactory
        );
        Resume selected = resume(3L, "docx", childDocx);
        when(resumeMapper.findById(3L)).thenReturn(selected);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH",
                        "Developed and integrated IP rate-limiting features.")));
        org.mockito.Mockito.lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> aijob.util.ConservativeLanguageJudge.passthroughRanking(
                        invocation.getArgument(3)));
        when(resumeDocxTailorService.createTailoredDocx(any(), anyList(), anyLong(), anyLong()))
                .thenReturn(generatedDocx);
        when(pdfExportService.exportPdf(any())).thenReturn(generatedPdf);

        v2.tailorResume(EMAIL, 10L, 3L, TailorMode.CONSERVATIVE);

        verify(v3BasicTailorService, never()).run(anyList(), any());
        verify(resumeAnalysisInputFactory, never()).resolve(any(), any());
        verify(aiResumeService).generateConstrainedTailorCandidates(any(), any(), any(), any());
    }

    private void stubOwner() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Junior Java backend intern using Spring Boot and AWS.");
        when(jobMapper.findById(10L)).thenReturn(job);
    }

    private static Resume resume(Long id, String type, Path path) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(1L);
        resume.setFileType(type);
        resume.setFilePath(path.toString());
        return resume;
    }

    private static TailorChange appliedChange(String nodeId) {
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText("Design and implement the application using Java Spring Boot with an MVC pattern.");
        change.setNewText("Designed and implemented the application in Java Spring Boot using MVC.");
        change.setApplied(true);
        change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
        return change;
    }
}
