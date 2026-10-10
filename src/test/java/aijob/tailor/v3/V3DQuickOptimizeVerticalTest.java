package aijob.tailor.v3;

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
import aijob.enums.TailorMode;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.GeneratedResumePdfFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3-3D Quick Optimize mock vertical. Frozen V3 BASIC path with ScriptedGenerator.
 * No OpenAI. Writer writes only under {@code @TempDir}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V3DQuickOptimizeVerticalTest {

    private static final String EMAIL = "v3d-quick@example.com";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private AiResumeService aiResumeService;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private GreenhouseJobClient greenhouseJobClient;
    @Mock private PdfExportService pdfExportService;
    @Mock private ResumeAnalysisInputFactory resumeAnalysisInputFactory;

    @TempDir
    Path tempDir;

    @Test
    void quickOptimizePdfParentUsesFrozenV3BasicAndWritesOnce() throws Exception {
        Path uploadsRoot = Path.of("uploads");
        Map<String, String> uploadsBefore = snapshot(uploadsRoot);
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path canonical = GeneratedResumePdfFixture.writeCanonicalDocx(
                pdf, tempDir.resolve("canonical-child.docx"));
        Path parentPdf = tempDir.resolve("alexmorgan-parent.pdf");
        Files.copy(pdf, parentPdf);

        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        CountingWriter writer = new CountingWriter(parser, tempDir.resolve("writer-uploads"));
        ScriptedBasicGenerator generator = new ScriptedBasicGenerator();
        V3BasicTailorService basic = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi());

        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V3");
        JobServiceImpl jobService = new JobServiceImpl(
                jobMapper,
                userMapper,
                resumeMapper,
                tailorProposalMapper,
                aiResumeService,
                parser,
                writer,
                new ObjectMapper(),
                greenhouseJobClient,
                pdfExportService,
                StoragePathResolver.forRoot(tempDir.resolve("job-uploads")),
                null,
                null,
                null,
                JobSearchProperties.defaults(),
                new TailorEngineRouter(new TailorEngineProperties(env)),
                basic,
                resumeAnalysisInputFactory
        );

        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription(V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        when(jobMapper.findById(10L)).thenReturn(job);

        Resume selected = resume(8L, "pdf", parentPdf);
        Resume child = resume(9L, "docx", canonical);
        when(resumeMapper.findById(8L)).thenReturn(selected);
        when(resumeAnalysisInputFactory.resolve(selected, 1L)).thenReturn(new ResumeAnalysisInput(
                selected, selected, child, List.of(), Map.of(), null, 8L));
        when(pdfExportService.exportPdf(any())).thenAnswer(invocation -> {
            Path docx = invocation.getArgument(0);
            Path pdfOut = docx.resolveSibling(docx.getFileName().toString().replace(".docx", ".pdf"));
            Files.writeString(pdfOut, "%PDF-1.4\n");
            return pdfOut;
        });

        assertEquals(0, writer.calls.get());
        TailoredResumeResponse response = jobService.tailorResume(EMAIL, 10L, 8L, TailorMode.CONSERVATIVE);

        assertEquals("V3", response.getEngineVersion());
        assertEquals("BASIC", response.getProductMode());
        assertEquals(TailoredResumeResponse.STATUS_APPLIED, response.getStatus());
        assertNull(response.getProposalId());
        assertTrue(response.getDownloadUrl() != null && response.getDownloadUrl().contains("/tailored/"));
        assertTrue(response.getPdfDownloadUrl() != null && response.getPdfDownloadUrl().endsWith(".pdf"));
        assertEquals(1, writer.calls.get());
        assertNotNull(writer.last);
        assertTrue(Files.isRegularFile(writer.last));
        Path generatedPdf = writer.last.resolveSibling(
                writer.last.getFileName().toString().replace(".docx", ".pdf"));
        assertTrue(Files.isRegularFile(generatedPdf));
        verify(tailorProposalMapper, never()).insert(any());
        verify(aiResumeService, never()).generateConstrainedTailorCandidates(any(), any(), any(), any());

        // The website's Resume Rewrite no longer uses this endpoint: Free has its own
        // /resume-rewrites/free call and never touches job matching. The endpoint itself
        // stays for the mobile app's CONSERVATIVE tailoring.
        String page = Files.readString(Path.of("frontend/src/pages/TailorPage.tsx"), StandardCharsets.UTF_8);
        assertTrue(page.contains("getResumeStructure"));
        assertTrue(page.contains("canonicalizeResume"));
        assertTrue(page.contains("createFreeRewrite"));
        assertFalse(page.contains("tailorResume("));
        assertFalse(page.contains("analyzeTailorFit"));
        String rewriteApi = Files.readString(Path.of("frontend/src/api/resumeRewrite.ts"), StandardCharsets.UTF_8);
        assertTrue(rewriteApi.contains("endpoints.resumeRewriteFree"));
        String jobSource = Files.readString(
                Path.of("src/main/java/aijob/service/impl/JobServiceImpl.java"), StandardCharsets.UTF_8);
        assertTrue(jobSource.contains("v3BasicTailorService.run(nodes, jobDescription)"));
        assertTrue(jobSource.contains("response.setProductMode(\"BASIC\")"));
        assertTrue(jobSource.contains("AIJOB_TAILOR_ENGINE")
                || Files.readString(Path.of("src/main/java/aijob/config/TailorEngineProperties.java"))
                .contains("AIJOB_TAILOR_ENGINE"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("callGraph", List.of(
                "mobile CONSERVATIVE tailor (website Resume Rewrite uses /resume-rewrites instead)",
                "PDF: getResumeStructure then canonicalizeResume",
                "POST /jobs/{jobId}/tailor?resumeId=&mode=CONSERVATIVE",
                "AIJOB_TAILOR_ENGINE=V3",
                "JobServiceImpl.tailorResumeV3Basic",
                "v3BasicTailorService.run",
                "ResumeDocxTailorService.createTailoredDocx once",
                "PdfExportService.exportPdf",
                "STATUS_APPLIED + DOCX/PDF download URLs"
        ));
        report.put("engineVersion", response.getEngineVersion());
        report.put("productMode", response.getProductMode());
        report.put("status", response.getStatus());
        report.put("proposalId", response.getProposalId());
        report.put("writerCalls", writer.calls.get());
        report.put("downloadUrl", response.getDownloadUrl());
        report.put("pdfDownloadUrl", response.getPdfDownloadUrl());
        report.put("docxExists", Files.isRegularFile(writer.last));
        report.put("pdfExists", Files.isRegularFile(generatedPdf));
        report.put("noProposalInsert", true);
        report.put("noOpportunityOrQuestions", true);
        report.put("noContinueOrConfirm", true);
        report.put("openaiCalls", 0);
        report.put("rewrittenEntryCount", response.getRewrittenEntryCount());
        report.put("changeCount", response.getChanges() == null ? 0 : response.getChanges().size());
        Path out = Path.of("docs", "v3-3d-live", "quick-optimize.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);

        assertEquals(uploadsBefore, snapshot(uploadsRoot), "real uploads must stay frozen");
        List<ResumeDocumentNode> rewritten = parser.parseDocx(writer.last);
        assertFalse(rewritten.isEmpty());
        boolean headingTouched = false;
        for (TailorChange change : response.getChanges() == null ? List.<TailorChange>of() : response.getChanges()) {
            String text = change.getOriginalText() == null ? "" : change.getOriginalText().toLowerCase(Locale.ROOT);
            if ((text.contains("prime deals") && !text.contains("java"))
                    || (text.contains("smartpc") && !text.contains("java"))) {
                headingTouched = true;
            }
        }
        assertFalse(headingTouched);
    }

    private static Resume resume(Long id, String type, Path path) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(1L);
        resume.setFileType(type);
        resume.setFilePath(path.toString());
        return resume;
    }

    private static Map<String, String> snapshot(Path uploads) throws Exception {
        Map<String, String> hashes = new java.util.TreeMap<>();
        if (!Files.isDirectory(uploads)) {
            return hashes;
        }
        try (var stream = Files.walk(uploads)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'), sha256(file));
                } catch (Exception ex) {
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'), "LOCKED");
                }
            });
        }
        return hashes;
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static final class CountingWriter implements ResumeDocxTailorService {
        private final AtomicInteger calls = new AtomicInteger();
        private final ResumeDocxTailorServiceImpl inner;
        private Path last;

        private CountingWriter(ResumeDocumentServiceImpl parser, Path uploadsRoot) {
            this.inner = new ResumeDocxTailorServiceImpl(parser, StoragePathResolver.forRoot(uploadsRoot));
        }

        @Override
        public Path createTailoredDocx(Path originalDocx, List<TailorChange> changes, Long userId, Long jobId) {
            calls.incrementAndGet();
            last = inner.createTailoredDocx(originalDocx, changes, userId, jobId);
            return last;
        }

        @Override
        public Path createTailoredDocx(Path originalDocx, List<TailorChange> changes, Path outputPath) {
            return inner.createTailoredDocx(originalDocx, changes, outputPath);
        }
    }

    private static final class ScriptedBasicGenerator implements V3EntryRewriteGenerator {
        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            if ("PROJECT:prime-deals".equals(request.canonicalEntryKey())) {
                List<String> bullets = new ArrayList<>();
                for (V3WritableNode node : request.writableNodes()) {
                    bullets.add(node.originalText()
                            .replace("onto AWS", "to AWS")
                            .replace("managements", "operations")
                            .replace("Successfully ", "")
                            .replace("successfully ", ""));
                }
                return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
            }
            if ("PROJECT:smartpc-builder".equals(request.canonicalEntryKey())) {
                List<String> bullets = new ArrayList<>();
                for (V3WritableNode node : request.writableNodes()) {
                    bullets.add(node.originalText()
                            .replace("within a given budget", "within a specified budget")
                            .replace("CPU, GPU & RAM", "CPU, GPU, and RAM"));
                }
                return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        }
    }
}
