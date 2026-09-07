package aijob.service.impl;

import aijob.config.StoragePathResolver;
import aijob.dto.PdfLayout;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeFileDownload;
import aijob.dto.ResumeResponse;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeBullet;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import aijob.dto.ResumeTextBlock;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.enums.ResumeExtractionStatus;
import aijob.enums.ResumeOrigin;
import aijob.enums.ResumeReviewStatus;
import aijob.exception.AiServiceException;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeNotFoundException;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeStructureLlmService;
import aijob.util.PdfLayoutExtractor;
import aijob.util.ResumeStructureDraftBuilder;
import aijob.util.ResumeTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeServiceImplTest {

    private static final String EMAIL = "user@example.com";
    private static final String OTHER_EMAIL = "other@example.com";
    private static final String SAMPLE_TEXT = """
            Jane Doe
            jane.doe@example.com
            SUMMARY OF QUALIFICATIONS
            Java, Spring Boot
            """;

    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ResumeTextExtractor resumeTextExtractor;
    @Mock
    private AiResumeService aiResumeService;
    @Mock
    private ResumeDocumentService resumeDocumentService;
    @Mock
    private PdfLayoutExtractor pdfLayoutExtractor;
    @Mock
    private ResumeStructureLlmService resumeStructureLlmService;
    @Spy
    private ResumeTemplateDocxServiceImpl resumeTemplateDocxService = new ResumeTemplateDocxServiceImpl();
    @Spy
    private ResumeStructureDraftBuilder resumeStructureDraftBuilder = new ResumeStructureDraftBuilder();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private StoragePathResolver storagePathResolver;
    private ResumeServiceImpl resumeService;
    private Resume lastInserted;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(2L);
        owner.setEmail(EMAIL);
        storagePathResolver = StoragePathResolver.forRoot(tempDir.resolve("uploads"));
        resumeService = new ResumeServiceImpl(
                resumeMapper,
                userMapper,
                resumeTextExtractor,
                aiResumeService,
                resumeDocumentService,
                pdfLayoutExtractor,
                resumeStructureDraftBuilder,
                resumeStructureLlmService,
                resumeTemplateDocxService,
                new aijob.util.ResumeQualityScorer(),
                objectMapper,
                storagePathResolver
        );
    }

    @Test
    void uploadPdf_keepsExtractedText_writesLayoutAndUnassignedDraft() throws Exception {
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubInsert();
        when(resumeTextExtractor.extract(any(Path.class), eq("pdf"))).thenReturn(SAMPLE_TEXT);
        when(pdfLayoutExtractor.extract(any(Path.class))).thenReturn(sampleLayout());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "jane.pdf",
                "application/pdf",
                "dummy-pdf".getBytes(StandardCharsets.UTF_8)
        );

        ResumeResponse response = resumeService.uploadResume(EMAIL, file);

        assertEquals(ResumeOrigin.UPLOAD.name(), response.getOrigin());
        assertEquals(ResumeReviewStatus.PENDING_REVIEW.name(), response.getReviewStatus());
        assertEquals("/api/v1/resumes/12/download", response.getDownloadUrl());
        assertTrue(response.isFileAvailable());
        assertEquals(SAMPLE_TEXT, lastInserted.getExtractedText());
        assertTrue(lastInserted.getFilePath().toLowerCase().endsWith(".pdf"));
        assertEquals(ResumeExtractionStatus.LAYOUT.name(), lastInserted.getExtractionStatus());
        assertNotNull(lastInserted.getLayoutJson());

        ResumeStructure draft = objectMapper.readValue(
                lastInserted.getStructuredJson(),
                ResumeStructure.class
        );
        assertEquals("Jane Doe", draft.getContact().getFullName());
        assertEquals("jane.doe@example.com", draft.getContact().getEmail());
        assertTrue(draft.getSkills().isEmpty());
        assertTrue(draft.getExperience().isEmpty());
        assertTrue(unassignedText(draft).contains("SUMMARY OF QUALIFICATIONS"));
        assertFalse(unassignedText(draft).isBlank());
        assertFalse(Boolean.TRUE.equals(draft.getLlmClassified()));
        verify(resumeDocumentService, never()).parseDocx(any());
        verify(resumeStructureLlmService, never()).classify(any(), any());
    }

    @Test
    void uploadAndTeardown_neverTouchProjectUploadsOrSentinel() throws Exception {
        Path projectUploads = Path.of("uploads", "resumes").toAbsolutePath().normalize();
        Set<String> beforeProject = snapshotFiles(projectUploads);

        Path simulatedRealDir = tempDir.resolve("simulated-project")
                .resolve("uploads")
                .resolve("resumes")
                .resolve("2");
        Files.createDirectories(simulatedRealDir);
        Path sentinel = simulatedRealDir.resolve("sentinel-existing.pdf");
        Files.writeString(sentinel, "keep-me");

        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubInsert();
        when(resumeTextExtractor.extract(any(Path.class), eq("pdf"))).thenReturn(SAMPLE_TEXT);
        when(pdfLayoutExtractor.extract(any(Path.class))).thenReturn(sampleLayout());

        resumeService.uploadResume(EMAIL, new MockMultipartFile(
                "file",
                "jane.pdf",
                "application/pdf",
                "dummy-pdf".getBytes(StandardCharsets.UTF_8)
        ));

        Path written = Path.of(lastInserted.getFilePath()).toAbsolutePath().normalize();
        assertTrue(written.startsWith(tempDir.toAbsolutePath().normalize()));
        assertTrue(written.startsWith(storagePathResolver.resumeDirectory(2L)));
        assertFalse(written.startsWith(projectUploads));
        assertFalse(written.startsWith(simulatedRealDir));
        assertEquals(beforeProject, snapshotFiles(projectUploads));
        assertTrue(Files.exists(sentinel));
        assertEquals("keep-me", Files.readString(sentinel));
    }

    @Test
    void uploadPdf_imageOnly_keepsFileAndEmptyUnassigned() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubInsert();
        when(resumeTextExtractor.extract(any(Path.class), eq("pdf"))).thenReturn("");
        when(pdfLayoutExtractor.extract(any(Path.class))).thenReturn(PdfLayout.imageOnly());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scan.pdf",
                "application/pdf",
                "dummy-pdf".getBytes(StandardCharsets.UTF_8)
        );

        resumeService.uploadResume(EMAIL, file);

        assertEquals("", lastInserted.getExtractedText());
        assertEquals(ResumeExtractionStatus.IMAGE_ONLY.name(), lastInserted.getExtractionStatus());
        assertTrue(lastInserted.getFilePath().toLowerCase().endsWith(".pdf"));
        ResumeStructure draft = readStructure(lastInserted.getStructuredJson());
        assertTrue(draft.getUnassigned().isEmpty());
        assertEquals("", draft.getContact().getFullName());
    }

    @Test
    void uploadDocx_staysReadyWithoutLayout() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubInsert();
        when(resumeTextExtractor.extract(any(Path.class), eq("docx"))).thenReturn("DOCX TEXT");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "jane.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "dummy-docx".getBytes(StandardCharsets.UTF_8)
        );

        ResumeResponse response = resumeService.uploadResume(EMAIL, file);

        assertEquals("docx", response.getFileType());
        assertEquals(ResumeReviewStatus.READY.name(), response.getReviewStatus());
        assertEquals(ResumeExtractionStatus.TEXT.name(), lastInserted.getExtractionStatus());
        assertNull(lastInserted.getLayoutJson());
        assertNull(lastInserted.getStructuredJson());
        verify(pdfLayoutExtractor, never()).extract(any());
    }

    @Test
    void getStructure_pdf_returnsStoredDraftWithoutRewritingLayout() throws Exception {
        Resume resume = storedPdfResume("original-bytes");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);

        ResumeStructure structure = resumeService.getResumeStructure(EMAIL, 12L);

        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals(ResumeExtractionStatus.LAYOUT.name(), structure.getExtractionStatus());
        verify(resumeMapper, never()).updateStructure(any(), any(), any(), any());
        verify(resumeMapper, never()).updateLayoutAndStructure(any(), any(), any(), any(), any(), any());
        verify(pdfLayoutExtractor, never()).extract(any());
        assertEquals("original-bytes", Files.readString(Path.of(resume.getFilePath())));
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
        verify(resumeStructureLlmService, never()).classify(any(), any());
    }

    @Test
    void getStructure_classifiesUnassignedBlocksAndPersistsPendingReview() throws Exception {
        Resume resume = storedPdfResumeAwaitingClassification("original-bytes");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeMapper.updateStructure(eq(12L), eq(2L), anyString(), eq("PENDING_REVIEW")))
                .thenReturn(1);

        ResumeStructure classified = ResumeStructure.empty();
        classified.getContact().setFullName("Jane Doe");
        classified.getContact().setEmail("jane.doe@example.com");
        classified.setSummary("Java, Spring Boot");
        ResumeSkill java = new ResumeSkill();
        java.setName("Java");
        java.setSourceBlockIds(List.of("b-p1-3"));
        java.setSourcePage(1);
        java.setSourceText("Java");
        java.setConfidence(ResumeTextBlock.CONFIDENCE_HIGH);
        java.setProvenance(ResumeTextBlock.PROVENANCE_LLM);
        classified.getSkills().add(java);
        classified.setLlmClassified(Boolean.TRUE);
        when(resumeStructureLlmService.classify(any(PdfLayout.class), any(ResumeStructure.class)))
                .thenReturn(classified);

        ResumeStructure structure = resumeService.getResumeStructure(EMAIL, 12L);

        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals("jane.doe@example.com", structure.getContact().getEmail());
        assertEquals("Java, Spring Boot", structure.getSummary());
        assertEquals("Java", structure.getSkills().get(0).getName());
        assertTrue(Boolean.TRUE.equals(structure.getLlmClassified()));
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), structure.getStructureFormatVersion());
        assertEquals(ResumeReviewStatus.PENDING_REVIEW.name(), structure.getReviewStatus());
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
        assertEquals("original-bytes", Files.readString(Path.of(resume.getFilePath())));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resumeMapper).updateStructure(eq(12L), eq(2L), jsonCaptor.capture(), eq("PENDING_REVIEW"));
        ResumeStructure stored = objectMapper.readValue(jsonCaptor.getValue(), ResumeStructure.class);
        assertTrue(Boolean.TRUE.equals(stored.getLlmClassified()));
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), stored.getStructureFormatVersion());
        assertEquals(ResumeReviewStatus.PENDING_REVIEW.name(), stored.getReviewStatus());
        verify(resumeMapper, never()).updateLayoutAndStructure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getStructure_oldLlmJsonMissingNewFields_reclassifies() throws Exception {
        Resume resume = storedPdfResumeOldLlmFormat("original-bytes");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeMapper.updateStructure(eq(12L), eq(2L), anyString(), eq("PENDING_REVIEW")))
                .thenReturn(1);

        ResumeStructure classified = ResumeStructure.empty();
        classified.getContact().setFullName("Jane Doe");
        ResumeStructure.ResumeBullet bullet = new ResumeStructure.ResumeBullet();
        bullet.setText("Java, Spring Boot");
        bullet.setSourceBlockIds(List.of("b-p1-3"));
        bullet.setSourcePage(1);
        bullet.setSourceText("Java, Spring Boot");
        bullet.setProvenance(ResumeTextBlock.PROVENANCE_LLM);
        classified.getSummaryBullets().add(bullet);
        classified.setLlmClassified(Boolean.TRUE);
        classified.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
        when(resumeStructureLlmService.classify(any(PdfLayout.class), any(ResumeStructure.class)))
                .thenReturn(classified);

        ResumeStructure structure = resumeService.getResumeStructure(EMAIL, 12L);

        assertEquals(1, structure.getSummaryBullets().size());
        assertEquals("Java, Spring Boot", structure.getSummaryBullets().get(0).getText());
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), structure.getStructureFormatVersion());
        verify(resumeStructureLlmService).classify(any(PdfLayout.class), any(ResumeStructure.class));
        verify(resumeMapper).updateStructure(eq(12L), eq(2L), anyString(), eq("PENDING_REVIEW"));
        assertEquals("original-bytes", Files.readString(Path.of(resume.getFilePath())));
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
    }

    @Test
    void getStructure_openaiFailure_returnsRuleDraftWithoutChangingPdf() throws Exception {
        Resume resume = storedPdfResumeAwaitingClassification("original-bytes");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeStructureLlmService.classify(any(PdfLayout.class), any(ResumeStructure.class)))
                .thenThrow(new AiServiceException("AI analysis is temporarily unavailable"));

        ResumeStructure structure = resumeService.getResumeStructure(EMAIL, 12L);

        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals("", structure.getSummary());
        assertTrue(structure.getSkills().isEmpty());
        assertFalse(Boolean.TRUE.equals(structure.getLlmClassified()));
        assertEquals(ResumeReviewStatus.PENDING_REVIEW.name(), structure.getReviewStatus());
        verify(resumeMapper, never()).updateStructure(any(), any(), any(), any());
        verify(resumeMapper, never()).updateLayoutAndStructure(any(), any(), any(), any(), any(), any());
        assertEquals("original-bytes", Files.readString(Path.of(resume.getFilePath())));
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
    }

    @Test
    void getStructure_missingLayout_lazilyExtractsAndSaves() throws Exception {
        Resume resume = storedPdfResume("keep");
        resume.setLayoutJson(null);
        resume.setStructuredJson(null);
        resume.setExtractionStatus(ResumeExtractionStatus.TEXT.name());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(pdfLayoutExtractor.extract(any(Path.class))).thenReturn(sampleLayout());
        when(resumeMapper.updateLayoutAndStructure(
                eq(12L), eq(2L), anyString(), eq("LAYOUT"), anyString(), eq("PENDING_REVIEW")))
                .thenReturn(1);

        ResumeStructure structure = resumeService.getResumeStructure(EMAIL, 12L);

        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals(ResumeExtractionStatus.LAYOUT.name(), structure.getExtractionStatus());
        verify(resumeMapper).updateLayoutAndStructure(
                eq(12L), eq(2L), anyString(), eq("LAYOUT"), anyString(), eq("PENDING_REVIEW"));
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
        assertEquals("keep", Files.readString(Path.of(resume.getFilePath())));
    }

    @Test
    void getStructure_docx_throwsBadRequest() throws Exception {
        Resume resume = storedPdfResume("keep");
        resume.setFileType("docx");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);

        assertThrows(
                InvalidResumeFileException.class,
                () -> resumeService.getResumeStructure(EMAIL, 12L)
        );
        verify(pdfLayoutExtractor, never()).extract(any());
    }

    @Test
    void getStructure_otherUser_throwsNotFound() throws Exception {
        Resume resume = storedPdfResume("keep");
        User other = new User();
        other.setId(99L);
        other.setEmail(OTHER_EMAIL);
        when(userMapper.findByEmail(OTHER_EMAIL)).thenReturn(other);
        when(resumeMapper.findById(12L)).thenReturn(resume);

        assertThrows(
                ResumeNotFoundException.class,
                () -> resumeService.getResumeStructure(OTHER_EMAIL, 12L)
        );
    }

    @Test
    void putStructure_doesNotChangeFileLayoutOrExtractedText_andStampsUserEdited() throws Exception {
        Resume resume = storedPdfResume("original-bytes");
        String layoutBefore = resume.getLayoutJson();
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeMapper.updateStructure(eq(12L), eq(2L), anyString(), eq("PENDING_REVIEW")))
                .thenReturn(1);

        ResumeStructure edited = ResumeStructure.empty();
        edited.getContact().setFullName("Janet Doe");
        ResumeSkill kotlin = new ResumeSkill();
        kotlin.setName("Kotlin");
        edited.getSkills().add(kotlin);

        ResumeStructure updated = resumeService.updateResumeStructure(EMAIL, 12L, edited);

        assertEquals("Janet Doe", updated.getContact().getFullName());
        assertTrue(Boolean.TRUE.equals(updated.getLlmClassified()));
        assertEquals(Integer.valueOf(ResumeStructure.STRUCTURE_FORMAT_VERSION), updated.getStructureFormatVersion());
        assertEquals(ResumeTextBlock.PROVENANCE_USER_EDITED,
                updated.getFieldProvenance().get("contact.fullName"));
        assertEquals(ResumeTextBlock.PROVENANCE_USER_EDITED,
                updated.getFieldProvenance().get("skills"));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resumeMapper).updateStructure(eq(12L), eq(2L), jsonCaptor.capture(), eq("PENDING_REVIEW"));
        verify(resumeMapper, never()).updateLayoutAndStructure(any(), any(), any(), any(), any(), any());
        verify(resumeMapper, never()).insert(any());
        assertEquals(layoutBefore, resume.getLayoutJson());
        assertEquals(SAMPLE_TEXT, resume.getExtractedText());
        assertEquals("original-bytes", Files.readString(Path.of(resume.getFilePath())));
        ResumeStructure stored = objectMapper.readValue(jsonCaptor.getValue(), ResumeStructure.class);
        assertEquals("Janet Doe", stored.getContact().getFullName());
    }

    @Test
    void getResume_doesNotExposeFilePathExtractedTextOrLayout() throws Exception {
        Resume resume = storedPdfResume("secret");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);

        ResumeResponse response = resumeService.getResume(EMAIL, 12L);
        String json = objectMapper.writeValueAsString(response);

        assertFalse(response.isFileAvailable());
        assertNull(response.getDownloadUrl());
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("filePath"));
        assertFalse(json.contains(resume.getFilePath().replace("\\", "\\\\")));
        assertFalse(json.contains("extractedText"));
        assertFalse(json.contains("structuredJson"));
        assertFalse(json.contains("layoutJson"));
    }

    @Test
    void getQualityScore_neverCallsAiResumeServiceOrResumeStructureLlmService() throws Exception {
        Resume resume = storedPdfResume("Jordan Lee\njordan.lee@example.com\n\nEXPERIENCE\nEngineer, Acme, 2020 - 2022\nShipped a feature used by 500 customers\n\nSKILLS\nJava");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);

        aijob.dto.ResumeQualityScoreResponse response = resumeService.getQualityScore(EMAIL, 12L);

        assertTrue(response.isScorable());
        // The two service-layer gateways to OpenAI in this codebase, verified to have
        // NEVER been touched -- this is what "the resume scoring endpoint never calls
        // OpenAI" actually means at the unit level, not just an absence of an obvious
        // import.
        verifyNoInteractions(aiResumeService);
        verifyNoInteractions(resumeStructureLlmService);
        verifyNoInteractions(resumeDocumentService);
    }

    @Test
    void downloadResume_ownPdf_returnsStoredFile() throws Exception {
        byte[] payload = "%PDF-1.4 own-pdf".getBytes(StandardCharsets.UTF_8);
        Resume resume = storedOwnedUpload(4L, "pdf", "yaohang.pdf", payload);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        ResumeFileDownload download = resumeService.downloadResume(EMAIL, 4L);

        assertEquals("pdf", download.getFileType());
        assertEquals("yaohang.pdf", download.getFileName());
        assertArrayEquals(payload, Files.readAllBytes(download.getPath()));
        assertTrue(download.getPath().startsWith(
                storagePathResolver.resumeDirectory(2L).toAbsolutePath().normalize()));
    }

    @Test
    void downloadResume_ownCanonicalDocx_returnsStoredFile() throws Exception {
        byte[] payload = "canonical-docx".getBytes(StandardCharsets.UTF_8);
        Resume resume = storedOwnedUpload(41L, "docx", "Yaohang_Tang_canonical.docx", payload);
        resume.setOrigin(ResumeOrigin.TEMPLATE.name());
        resume.setParentResumeId(4L);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(41L)).thenReturn(resume);

        ResumeFileDownload download = resumeService.downloadResume(EMAIL, 41L);

        assertEquals("docx", download.getFileType());
        assertEquals("Yaohang_Tang_canonical.docx", download.getFileName());
        assertArrayEquals(payload, Files.readAllBytes(download.getPath()));
    }

    @Test
    void downloadResume_otherUser_is404() throws Exception {
        Resume resume = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        User other = new User();
        other.setId(99L);
        other.setEmail(OTHER_EMAIL);
        when(userMapper.findByEmail(OTHER_EMAIL)).thenReturn(other);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        assertThrows(ResumeNotFoundException.class, () -> resumeService.downloadResume(OTHER_EMAIL, 4L));
    }

    @Test
    void downloadResume_filePathOutsideUserUploads_is404() throws Exception {
        Path outside = tempDir.resolve("escape.pdf");
        Files.writeString(outside, "outside");
        Resume resume = storedPdfResume(4L, "keep", yaohangStructure());
        resume.setFilePath(outside.toAbsolutePath().toString());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        assertThrows(ResumeNotFoundException.class, () -> resumeService.downloadResume(EMAIL, 4L));
        assertTrue(Files.exists(outside));
    }

    @Test
    void downloadResume_missingFile_is404() throws Exception {
        Path missing = storagePathResolver.resumeDirectory(2L).resolve("missing.pdf");
        Resume resume = storedPdfResume(4L, "keep", yaohangStructure());
        resume.setFilePath(missing.toString());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        assertThrows(ResumeNotFoundException.class, () -> resumeService.downloadResume(EMAIL, 4L));
    }

    @Test
    void getMyResumes_default_excludesTemplateChild() throws Exception {
        Resume upload = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        Resume child = storedOwnedUpload(41L, "docx", "hidden.docx", "docx".getBytes(StandardCharsets.UTF_8));
        child.setOrigin(ResumeOrigin.TEMPLATE.name());
        child.setParentResumeId(4L);
        upload.setCanonicalResumeId(41L);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findByUserId(2L)).thenReturn(List.of(upload, child));

        List<ResumeResponse> resumes = resumeService.getMyResumes(EMAIL, false);

        assertEquals(1, resumes.size());
        assertEquals(4L, resumes.get(0).getId());
        assertEquals(ResumeOrigin.UPLOAD.name(), resumes.get(0).getOrigin());
        assertTrue(resumes.get(0).isFileAvailable());
        assertEquals("/api/v1/resumes/4/download", resumes.get(0).getDownloadUrl());
    }

    @Test
    void getMyResumes_includeDerived_returnsTemplateChild() throws Exception {
        Resume upload = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        Resume child = storedOwnedUpload(41L, "docx", "hidden.docx", "docx".getBytes(StandardCharsets.UTF_8));
        child.setOrigin(ResumeOrigin.TEMPLATE.name());
        child.setParentResumeId(4L);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findByUserId(2L)).thenReturn(List.of(upload, child));

        List<ResumeResponse> resumes = resumeService.getMyResumes(EMAIL, true);

        assertEquals(2, resumes.size());
        assertTrue(resumes.stream().anyMatch(item -> Long.valueOf(41L).equals(item.getId())));
    }

    @Test
    void getResume_existingFile_setsFileAvailableAndDownloadUrl() throws Exception {
        Resume resume = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        ResumeResponse response = resumeService.getResume(EMAIL, 4L);

        assertTrue(response.isFileAvailable());
        assertEquals("/api/v1/resumes/4/download", response.getDownloadUrl());
    }

    @Test
    void getResume_missingFile_setsUnavailableAndNullDownloadUrl() {
        Path missing = storagePathResolver.resumeDirectory(2L).resolve("missing.pdf");
        Resume resume = storedPdfResume(4L, "keep", yaohangStructure());
        resume.setFilePath(missing.toString());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        ResumeResponse response = resumeService.getResume(EMAIL, 4L);

        assertFalse(response.isFileAvailable());
        assertNull(response.getDownloadUrl());
    }

    @Test
    void deleteResume_uploadedDocx_deletesDbRowAndFile() throws Exception {
        byte[] payload = "docx-bytes".getBytes(StandardCharsets.UTF_8);
        Resume resume = storedOwnedUpload(12L, "docx", "cv.docx", payload);
        Path stored = Path.of(resume.getFilePath());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeMapper.deleteByIdAndUserId(12L, 2L)).thenReturn(1);

        resumeService.deleteResume(EMAIL, 12L);

        verify(resumeMapper).deleteByIdAndUserId(12L, 2L);
        assertFalse(Files.exists(stored));
    }

    @Test
    void deleteResume_missingFile_stillDeletesDbRow() {
        Path missing = storagePathResolver.resumeDirectory(2L).resolve("gone.docx");
        Resume resume = storedOwnedUpload(12L, "docx", "cv.docx", "x".getBytes(StandardCharsets.UTF_8));
        resume.setFilePath(missing.toString());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(12L)).thenReturn(resume);
        when(resumeMapper.deleteByIdAndUserId(12L, 2L)).thenReturn(1);

        resumeService.deleteResume(EMAIL, 12L);

        verify(resumeMapper).deleteByIdAndUserId(12L, 2L);
    }

    @Test
    void deleteResumeFamily_deletesParentAndOwnedChild() throws Exception {
        Resume parent = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        Resume child = storedOwnedUpload(41L, "docx", "hidden.docx", "docx".getBytes(StandardCharsets.UTF_8));
        child.setOrigin(ResumeOrigin.TEMPLATE.name());
        child.setParentResumeId(4L);
        parent.setCanonicalResumeId(41L);
        Path parentPath = Path.of(parent.getFilePath());
        Path childPath = Path.of(child.getFilePath());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(parent);
        when(resumeMapper.findById(41L)).thenReturn(child);
        when(resumeMapper.findByParentResumeIdAndUserId(4L, 2L)).thenReturn(List.of(child));
        when(resumeMapper.deleteByIdAndUserId(41L, 2L)).thenReturn(1);
        when(resumeMapper.deleteByIdAndUserId(4L, 2L)).thenReturn(1);

        resumeService.deleteResumeFamily(EMAIL, 4L);

        verify(resumeMapper).clearCanonicalResumeId(2L, 4L);
        verify(resumeMapper).clearCanonicalResumeId(2L, 41L);
        verify(resumeMapper).clearParentResumeId(2L, 4L);
        verify(resumeMapper).clearParentResumeId(2L, 41L);
        verify(resumeMapper).deleteByIdAndUserId(41L, 2L);
        verify(resumeMapper).deleteByIdAndUserId(4L, 2L);
        assertFalse(Files.exists(parentPath));
        assertFalse(Files.exists(childPath));
    }

    @Test
    void deleteResumeFamily_otherUser_is404() throws Exception {
        Resume parent = storedOwnedUpload(4L, "pdf", "yaohang.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        User other = new User();
        other.setId(99L);
        other.setEmail(OTHER_EMAIL);
        when(userMapper.findByEmail(OTHER_EMAIL)).thenReturn(other);
        when(resumeMapper.findById(4L)).thenReturn(parent);

        assertThrows(ResumeNotFoundException.class,
                () -> resumeService.deleteResumeFamily(OTHER_EMAIL, 4L));
        verify(resumeMapper, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void canonicalize_resumeId4_createsDocxChildWithoutChangingPdf() throws Exception {
        Resume parent = storedPdfResume(4L, "original-pdf-bytes", yaohangStructure());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubCanonicalLookup(parent);
        when(resumeTextExtractor.extract(any(Path.class), eq("docx")))
                .thenAnswer(invocation -> new ResumeTextExtractor().extract(invocation.getArgument(0), "docx"));
        when(resumeDocumentService.parseDocx(any(Path.class)))
                .thenAnswer(invocation -> new ResumeDocumentServiceImpl().parseDocx(invocation.getArgument(0)));
        when(resumeMapper.updateCanonicalParent(eq(4L), eq(2L), eq(41L), eq("READY"))).thenReturn(1);

        ResumeResponse response = resumeService.canonicalizeResume(EMAIL, 4L);

        assertEquals(41L, response.getId());
        assertEquals("docx", response.getFileType());
        assertEquals(ResumeOrigin.TEMPLATE.name(), response.getOrigin());
        assertEquals(ResumeReviewStatus.READY.name(), response.getReviewStatus());
        assertEquals(4L, response.getParentResumeId());
        assertEquals("/api/v1/resumes/41/download", response.getDownloadUrl());
        assertTrue(response.isFileAvailable());
        assertNull(response.getCanonicalResumeId());
        assertEquals(41L, parent.getCanonicalResumeId());
        assertEquals(ResumeReviewStatus.READY.name(), parent.getReviewStatus());
        assertEquals("original-pdf-bytes", Files.readString(Path.of(parent.getFilePath())));
        assertTrue(parent.getFilePath().toLowerCase().endsWith(".pdf"));
        assertEquals(SAMPLE_TEXT, parent.getExtractedText());
        assertNotNull(lastInserted.getExtractedText());
        assertFalse(lastInserted.getExtractedText().isBlank());
        assertTrue(lastInserted.getExtractedText().contains("Yaohang Tang"));
        assertEquals(ResumeExtractionStatus.TEXT.name(), lastInserted.getExtractionStatus());
        assertTrue(Files.exists(Path.of(lastInserted.getFilePath())));

        try (InputStream inputStream = Files.newInputStream(Path.of(lastInserted.getFilePath()));
             XWPFDocument document = new XWPFDocument(inputStream)) {
            String text = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText() == null ? "" : paragraph.getText())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            assertTrue(text.contains("Yaohang Tang"), text);
            assertTrue(text.contains("Prime Deals"), text);
            assertTrue(text.contains("Personal Project"), text);
            assertTrue(text.contains("Sept 2024 - Present"), text);
            assertTrue(text.contains("SmartPC Builder"), text);
            assertTrue(text.contains("Bachelor of Computer Science"), text);
            assertTrue(text.contains("Relevant Courses: Algorithm, Data Structure, Advanced OOP"), text);
            assertTrue(text.contains("Strong understanding of SDLC methodologies"), text);
            assertTrue(text.contains("http://35.183.41.31:8080/login.html"), text);
        }

        List<ResumeDocumentNode> nodes = resumeService.getDocumentNodes(EMAIL, 41L);
        assertFalse(nodes.isEmpty());
        assertTrue(nodes.stream().anyMatch(node -> node.getText().contains("Yaohang Tang")));
        verify(resumeStructureLlmService, never()).classify(any(), any());
        verify(resumeMapper).insert(any(Resume.class));
        verify(resumeMapper).updateCanonicalParent(eq(4L), eq(2L), eq(41L), eq("READY"));
        verify(resumeMapper, never()).updateCanonicalChildFile(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void canonicalize_missingFullName_is400AndCreatesNothing() throws Exception {
        ResumeStructure structure = yaohangStructure();
        structure.getContact().setFullName("  ");
        Resume parent = storedPdfResume(4L, "original-pdf-bytes", structure);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(parent);

        assertThrows(InvalidResumeFileException.class, () -> resumeService.canonicalizeResume(EMAIL, 4L));

        verify(resumeMapper, never()).insert(any());
        verify(resumeMapper, never()).updateCanonicalParent(any(), any(), any(), any());
        verify(resumeTemplateDocxService, never()).write(any(), any());
        assertEquals("original-pdf-bytes", Files.readString(Path.of(parent.getFilePath())));
        assertNull(parent.getCanonicalResumeId());
    }

    @Test
    void canonicalize_emptySkillsAndNoBullets_is400AndCreatesNothing() throws Exception {
        ResumeStructure structure = ResumeStructure.empty();
        structure.getContact().setFullName("Yaohang Tang");
        ResumeProject project = new ResumeProject();
        project.normalize();
        project.setName("Prime Deals");
        structure.getProjects().add(project);
        Resume parent = storedPdfResume(4L, "original-pdf-bytes", structure);
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(parent);

        assertThrows(InvalidResumeFileException.class, () -> resumeService.canonicalizeResume(EMAIL, 4L));

        verify(resumeMapper, never()).insert(any());
        verify(resumeTemplateDocxService, never()).write(any(), any());
        assertEquals("original-pdf-bytes", Files.readString(Path.of(parent.getFilePath())));
    }

    @Test
    void canonicalize_nonPdf_is400() throws Exception {
        Resume resume = storedPdfResume(4L, "original-pdf-bytes", yaohangStructure());
        resume.setFileType("docx");
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        assertThrows(InvalidResumeFileException.class, () -> resumeService.canonicalizeResume(EMAIL, 4L));
        verify(resumeMapper, never()).insert(any());
        verify(resumeTemplateDocxService, never()).write(any(), any());
    }

    @Test
    void canonicalize_otherUser_is404() throws Exception {
        Resume resume = storedPdfResume(4L, "original-pdf-bytes", yaohangStructure());
        User other = new User();
        other.setId(99L);
        other.setEmail(OTHER_EMAIL);
        when(userMapper.findByEmail(OTHER_EMAIL)).thenReturn(other);
        when(resumeMapper.findById(4L)).thenReturn(resume);

        assertThrows(ResumeNotFoundException.class, () -> resumeService.canonicalizeResume(OTHER_EMAIL, 4L));
        verify(resumeMapper, never()).insert(any());
        verify(resumeTemplateDocxService, never()).write(any(), any());
    }

    @Test
    void canonicalize_secondCall_keepsChildIdAndDeletesOldFileAfterSuccess() throws Exception {
        Resume parent = storedPdfResume(4L, "original-pdf-bytes", yaohangStructure());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubCanonicalLookup(parent);
        when(resumeTextExtractor.extract(any(Path.class), eq("docx")))
                .thenAnswer(invocation -> new ResumeTextExtractor().extract(invocation.getArgument(0), "docx"));
        when(resumeMapper.updateCanonicalParent(eq(4L), eq(2L), eq(41L), eq("READY"))).thenReturn(1);
        when(resumeMapper.updateCanonicalChildFile(
                eq(41L), eq(2L), anyString(), eq("docx"), anyString(), anyString(), eq("READY"), eq("TEXT")))
                .thenReturn(1);

        resumeService.canonicalizeResume(EMAIL, 4L);
        Path firstChildPath = Path.of(lastInserted.getFilePath());
        assertTrue(Files.exists(firstChildPath));

        ResumeResponse second = resumeService.canonicalizeResume(EMAIL, 4L);

        assertEquals(41L, second.getId());
        Path secondChildPath = Path.of(lastInserted.getFilePath());
        assertFalse(secondChildPath.equals(firstChildPath));
        assertTrue(Files.exists(secondChildPath));
        assertFalse(Files.exists(firstChildPath));
        assertEquals("original-pdf-bytes", Files.readString(Path.of(parent.getFilePath())));
        verify(resumeMapper).insert(any(Resume.class));
        verify(resumeMapper).updateCanonicalChildFile(
                eq(41L), eq(2L), anyString(), eq("docx"), anyString(), anyString(), eq("READY"), eq("TEXT"));
    }

    @Test
    void canonicalize_templateWriteFailure_leavesParentAndOldChildUnchanged() throws Exception {
        Resume parent = storedPdfResume(4L, "original-pdf-bytes", yaohangStructure());
        when(userMapper.findByEmail(EMAIL)).thenReturn(owner);
        stubCanonicalLookup(parent);
        when(resumeTextExtractor.extract(any(Path.class), eq("docx")))
                .thenAnswer(invocation -> new ResumeTextExtractor().extract(invocation.getArgument(0), "docx"));
        when(resumeMapper.updateCanonicalParent(eq(4L), eq(2L), eq(41L), eq("READY"))).thenReturn(1);

        resumeService.canonicalizeResume(EMAIL, 4L);
        Path firstChildPath = Path.of(lastInserted.getFilePath());
        String firstExtracted = lastInserted.getExtractedText();
        assertTrue(Files.exists(firstChildPath));

        doThrow(new UncheckedIOException("write failed", new java.io.IOException("write failed")))
                .when(resumeTemplateDocxService).write(any(), any());

        assertThrows(UncheckedIOException.class, () -> resumeService.canonicalizeResume(EMAIL, 4L));

        assertTrue(Files.exists(firstChildPath));
        assertEquals(firstChildPath.toString(), lastInserted.getFilePath());
        assertEquals(firstExtracted, lastInserted.getExtractedText());
        assertEquals("original-pdf-bytes", Files.readString(Path.of(parent.getFilePath())));
        assertEquals(41L, parent.getCanonicalResumeId());
        verify(resumeMapper).insert(any(Resume.class));
        verify(resumeMapper, never()).updateCanonicalChildFile(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void stubInsert() {
        when(resumeMapper.insert(any(Resume.class))).thenAnswer(invocation -> {
            lastInserted = invocation.getArgument(0);
            lastInserted.setId(12L);
            return 1;
        });
        when(resumeMapper.findById(12L)).thenAnswer(invocation -> lastInserted);
    }

    private void stubCanonicalLookup(Resume parent) {
        when(resumeMapper.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (parent.getId().equals(id)) {
                return parent;
            }
            if (lastInserted != null && lastInserted.getId() != null && lastInserted.getId().equals(id)) {
                return lastInserted;
            }
            return null;
        });
        when(resumeMapper.insert(any(Resume.class))).thenAnswer(invocation -> {
            lastInserted = invocation.getArgument(0);
            lastInserted.setId(41L);
            return 1;
        });
    }

    private Resume storedOwnedUpload(long id, String fileType, String displayName, byte[] bytes) {
        try {
            Path directory = storagePathResolver.resumeDirectory(2L);
            Files.createDirectories(directory);
            Path stored = directory.resolve(id + "-stored." + fileType).toAbsolutePath().normalize();
            Files.write(stored, bytes);
            Resume resume = new Resume();
            resume.setId(id);
            resume.setUserId(2L);
            resume.setFileName(displayName);
            resume.setFileType(fileType);
            resume.setFilePath(stored.toString());
            resume.setOrigin(ResumeOrigin.UPLOAD.name());
            resume.setReviewStatus(ResumeReviewStatus.READY.name());
            return resume;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Resume storedPdfResume(String fileContents) {
        return storedPdfResume(12L, fileContents, classifiedDraft());
    }

    private Resume storedPdfResume(long id, String fileContents, ResumeStructure structure) {
        try {
            Path file = tempDir.resolve("original-" + id + ".pdf");
            Files.writeString(file, fileContents);
            PdfLayout layout = sampleLayout();
            structure.setResumeId(id);
            structure.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
            Resume resume = new Resume();
            resume.setId(id);
            resume.setUserId(2L);
            resume.setFileName("jane.pdf");
            resume.setFileType("pdf");
            resume.setFilePath(file.toAbsolutePath().toString());
            resume.setExtractedText(SAMPLE_TEXT);
            resume.setOrigin(ResumeOrigin.UPLOAD.name());
            resume.setReviewStatus(ResumeReviewStatus.PENDING_REVIEW.name());
            resume.setExtractionStatus(ResumeExtractionStatus.LAYOUT.name());
            resume.setLayoutJson(objectMapper.writeValueAsString(layout));
            resume.setStructuredJson(objectMapper.writeValueAsString(structure));
            return resume;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResumeStructure classifiedDraft() {
        PdfLayout layout = sampleLayout();
        ResumeStructure draft = resumeStructureDraftBuilder.build(layout);
        draft.setLlmClassified(Boolean.TRUE);
        draft.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
        return draft;
    }

    private static ResumeStructure yaohangStructure() {
        ResumeStructure structure = ResumeStructure.empty();
        structure.setLlmClassified(Boolean.TRUE);
        structure.setStructureFormatVersion(ResumeStructure.STRUCTURE_FORMAT_VERSION);
        structure.getContact().setFullName("Yaohang Tang");
        structure.getContact().setEmail("yaohangtang@gmail.com");
        structure.getContact().setPhone("(647) 853-8928");
        structure.getContact().getLinks().add("https://github.com/tyh-star");
        structure.getSummaryBullets().add(summaryBullet("Strong understanding of SDLC methodologies, including Agile and Waterfall"));
        structure.getSummaryBullets().add(summaryBullet("Exceptional analytical and problem-solving skills with continuous thrive in optimizing program functionalities"));
        structure.getSummaryBullets().add(summaryBullet("Quick learner with a passion for adapting new business processes, latest technologies and concepts"));
        structure.getSummaryBullets().add(summaryBullet("Proficient in multiple programming languages, including Java, Python, Vue, Javascript"));
        structure.getSummaryBullets().add(summaryBullet("Strong understanding of computer science fundamentals including data structures and algorithms"));
        structure.getSummaryBullets().add(summaryBullet("Excellent communication and teamwork skills, with a proven ability to work independently and with a team"));
        structure.getSummaryBullets().add(summaryBullet("Experience with Git flow and version control"));
        ResumeSkill java = new ResumeSkill();
        java.setName("Java");
        structure.getSkills().add(java);
        ResumeProject prime = new ResumeProject();
        prime.normalize();
        prime.setName("Prime Deals");
        prime.setOrganizationOrType("Personal Project");
        prime.setDates("Sept 2024 - Present");
        prime.getBullets().add("Lead and collaborate with a friend to develop a shopping website");
        prime.getLinks().add("http://35.183.41.31:8080/login.html");
        structure.getProjects().add(prime);
        ResumeProject smart = new ResumeProject();
        smart.normalize();
        smart.setName("SmartPC Builder");
        smart.setOrganizationOrType("York University");
        smart.setDates("Jan 2025 - Mar 2025");
        smart.getBullets().add("Developed a Java Maven project that helps users select optimal components within a given budget");
        structure.getProjects().add(smart);
        ResumeEducation education = new ResumeEducation();
        education.setSchool("York University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        education.setDetails("Relevant Courses: Algorithm, Data Structure, Advanced OOP");
        structure.getEducation().add(education);
        return structure;
    }

    private static ResumeBullet summaryBullet(String text) {
        ResumeBullet bullet = new ResumeBullet();
        bullet.setText(text);
        return bullet;
    }

    private Resume storedPdfResumeAwaitingClassification(String fileContents) {
        try {
            Resume resume = storedPdfResume(fileContents);
            ResumeStructure draft = objectMapper.readValue(resume.getStructuredJson(), ResumeStructure.class);
            draft.setLlmClassified(Boolean.FALSE);
            draft.setStructureFormatVersion(null);
            resume.setStructuredJson(objectMapper.writeValueAsString(draft));
            return resume;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Resume storedPdfResumeOldLlmFormat(String fileContents) {
        try {
            Resume resume = storedPdfResume(fileContents);
            ResumeStructure old = objectMapper.readValue(resume.getStructuredJson(), ResumeStructure.class);
            old.setLlmClassified(Boolean.TRUE);
            old.setStructureFormatVersion(null);
            old.setSummary("Java, Spring Boot");
            old.setSummaryBullets(new ArrayList<>());
            resume.setStructuredJson(objectMapper.writeValueAsString(old));
            return resume;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PdfLayout sampleLayout() {
        ResumeTextBlock name = block("b-p1-0", 1, 72, 40, 22, "Jane Doe", List.of(ResumeTextBlock.FEATURE_LARGE_FONT));
        ResumeTextBlock email = block(
                "b-p1-1",
                1,
                72,
                70,
                11,
                "jane.doe@example.com",
                List.of(ResumeTextBlock.FEATURE_EMAIL)
        );
        ResumeTextBlock heading = block(
                "b-p1-2",
                1,
                72,
                120,
                13,
                "SUMMARY OF QUALIFICATIONS",
                List.of(ResumeTextBlock.FEATURE_ALL_CAPS)
        );
        ResumeTextBlock skills = block(
                "b-p1-3",
                1,
                72,
                150,
                11,
                "Java, Spring Boot",
                List.of(ResumeTextBlock.FEATURE_COMMA_LIST)
        );
        return PdfLayout.ofBlocks(612, 792, List.of(name, email, heading, skills));
    }

    private ResumeTextBlock block(
            String id,
            int page,
            float x,
            float y,
            float fontSize,
            String text,
            List<String> features) {
        ResumeTextBlock block = new ResumeTextBlock();
        block.setId(id);
        block.setPage(page);
        block.setX(x);
        block.setY(y);
        block.setWidth(200);
        block.setHeight(14);
        block.setFontSize(fontSize);
        block.setText(text);
        block.setFeatures(new ArrayList<>(features));
        block.setConfidence(ResumeTextBlock.CONFIDENCE_LOW);
        block.normalize();
        return block;
    }

    private ResumeStructure readStructure(String json) {
        try {
            return objectMapper.readValue(json, ResumeStructure.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unassignedText(ResumeStructure structure) {
        return structure.getUnassigned().stream()
                .map(ResumeTextBlock::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
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
