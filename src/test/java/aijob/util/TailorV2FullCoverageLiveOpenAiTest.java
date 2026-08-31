package aijob.util;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.OpenAiResumeServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorV2FullCoverageLiveOpenAiTest {

    private static final String EMAIL = "live@example.com";
    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private ResumeDocumentService resumeDocumentService;
    @Mock private ResumeDocxTailorService resumeDocxTailorService;
    @Mock private GreenhouseJobClient greenhouseJobClient;
    @Mock private PdfExportService pdfExportService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    @EnabledIfEnvironmentVariable(named = "AIJOB_T2C_LIVE", matches = "true")
    void threeLiveCoverageRunsScanEveryEligibleNode() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.contains("placeholder") && !apiKey.contains("sk-place"),
                "OPENAI_API_KEY is a placeholder");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");
        Map<String, String> uploadsBefore = snapshotUploadsHashes();

        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path docx = tempDir.resolve("yaohang-canonical.docx");
        YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, docx);
        List<aijob.dto.ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        OpenAiResumeServiceImpl ai = new OpenAiResumeServiceImpl(
                OpenAIOkHttpClient.builder().apiKey(apiKey).build(),
                model
        );
        JobServiceImpl jobService = new JobServiceImpl(
                jobMapper, userMapper, resumeMapper, tailorProposalMapper, ai,
                resumeDocumentService, resumeDocxTailorService, objectMapper, greenhouseJobClient,
                pdfExportService, StoragePathResolver.forRoot(tempDir.resolve("uploads")));
        stubOwned(docx);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(42L);
            return 1;
        });
        TailorProposal stored = new TailorProposal();
        stored.setId(42L);
        stored.setUserId(1L);
        stored.setJobId(10L);
        stored.setResumeId(3L);
        stored.setStatus(TailorProposal.STATUS_PENDING);
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.updateConfirmingContents(anyLong(), any(), any())).thenAnswer(invocation -> {
            stored.setChangesJson(invocation.getArgument(1));
            stored.setRejectedJson(invocation.getArgument(2));
            return 1;
        });
        when(tailorProposalMapper.restorePending(42L)).thenReturn(1);

        for (int run = 1; run <= 3; run++) {
            TailoredResumeResponse current = jobService.createTailorProposal(EMAIL, 10L, 3L);
            stored.setChangesJson(objectMapper.writeValueAsString(toState(current)));
            int pages = 0;
            while (Boolean.TRUE.equals(current.getHasMoreSuggestions()) && pages < 20) {
                current = jobService.continueTailorProposal(EMAIL, 10L, 42L);
                pages++;
            }
            printRun(run, current, pages);
            assertEquals(15, size(current.getEligibleNodeIds()), current.getEligibleNodeIds().toString());
            assertEquals(15, size(current.getExploredNodeIds()), current.getExploredNodeIds().toString());
            assertEquals(new LinkedHashSet<>(current.getEligibleNodeIds()),
                    new LinkedHashSet<>(current.getExploredNodeIds()));
            assertEquals(TailorProposalState.COVERAGE_COMPLETE, current.getCoverageStatus());
            assertFalse(Boolean.TRUE.equals(current.getHasMoreSuggestions()));
            if (current.getChanges() != null) {
                for (TailorChange change : current.getChanges()) {
                    String original = change.getOriginalText() == null ? "" : change.getOriginalText();
                    String rewritten = change.getNewText() == null ? "" : change.getNewText();
                    assertFalse(hasWord(original, "application") && !hasWord(original, "applications")
                                    && hasWord(rewritten, "applications"),
                            "FACT_CARDINALITY_CHANGED must not enter proposal: " + rewritten);
                    assertFalse(hasWord(original, "managements") && hasWord(rewritten, "managements"),
                            "OPPORTUNITY_NOT_RESOLVED managements must not enter proposal: " + rewritten);
                }
            }
            assertTrue(current.getEligibleNodeIds().contains("p-16"));
            assertTrue(current.getEligibleNodeIds().contains("p-17"));
            assertTrue(current.getEligibleNodeIds().contains("p-21"));
            assertTrue(current.getEligibleNodeIds().contains("p-23"));
            assertTrue(current.getEligibleNodeIds().contains("p-24"));
            assertTrue(current.getExploredNodeIds().containsAll(List.of("p-16", "p-17", "p-21", "p-23", "p-24")));
            verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        }
        assertEquals(uploadsBefore, snapshotUploadsHashes());
    }

    private static void printRun(int run, TailoredResumeResponse current, int continuePages) {
        System.out.println("=== Full coverage live run " + run + " ===");
        System.out.println("eligibleNodeCount=" + size(current.getEligibleNodeIds()));
        System.out.println("exploredNodeCount=" + size(current.getExploredNodeIds()));
        System.out.println("coverageStatus=" + current.getCoverageStatus());
        System.out.println("suggestionCount=" + size(current.getChanges()));
        System.out.println("noValuableChangeCount=" + size(current.getNoChangeNodeIds()));
        System.out.println("continuePages=" + continuePages);
        Map<String, Integer> rejects = new LinkedHashMap<>();
        if (current.getRejectedChanges() != null) {
            for (aijob.dto.RejectedTailorChange rejected : current.getRejectedChanges()) {
                if (rejected == null || rejected.getReasonCode() == null) {
                    continue;
                }
                rejects.merge(rejected.getReasonCode(), 1, Integer::sum);
            }
        }
        System.out.println("rejectReasons=" + rejects);
        int suggestionCount = size(current.getChanges());
        System.out.println("=== FINAL SUGGESTIONS (" + suggestionCount + ") ===");
        if (current.getChanges() != null) {
            int index = 1;
            for (TailorChange change : current.getChanges()) {
                System.out.println("--- SUGGESTION " + index + "/" + suggestionCount + " ---");
                System.out.println("nodeId=" + change.getNodeId());
                System.out.println("originalText=" + change.getOriginalText());
                System.out.println("newText=" + change.getNewText());
                System.out.println("opportunityType=" + change.getOpportunityType());
                System.out.println("judgeVerdict=" + change.getJudgeVerdict());
                System.out.println("judgeConfidence=" + change.getJudgeConfidence());
                System.out.println("hardGate=admitted");
                index++;
            }
        }
    }

    private static boolean hasWord(String text, String word) {
        if (text == null || word == null) {
            return false;
        }
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static TailorProposalState toState(TailoredResumeResponse response) {
        TailorProposalState state = TailorProposalState.fromChanges(
                response.getChanges(), response.getExploredNodeIds());
        state.setHasMoreSuggestions(response.getHasMoreSuggestions());
        state.setEligibleNodeIds(response.getEligibleNodeIds());
        state.setRemainingNodeIds(response.getRemainingNodeIds());
        state.setNoChangeNodeIds(response.getNoChangeNodeIds());
        state.setCoverageStatus(response.getCoverageStatus());
        return state;
    }

    private void stubOwned(Path resumeFile) {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription(JD);
        when(jobMapper.findById(10L)).thenReturn(job);
        Resume resume = new Resume();
        resume.setId(3L);
        resume.setUserId(1L);
        resume.setFileType("docx");
        resume.setFilePath(resumeFile.toString());
        when(resumeMapper.findById(3L)).thenReturn(resume);
    }

    private static Map<String, String> snapshotUploadsHashes() throws Exception {
        Path uploads = Path.of("C:\\ai job\\ai job\\uploads");
        TreeMap<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(uploads)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.walk(uploads)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'), sha256(file));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        return hashes;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }
}
