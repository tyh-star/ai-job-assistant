package aijob.util;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.entity.Job;
import aijob.entity.Resume;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.mapper.JobMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.mapper.UserMapper;
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorV2T2CAlexMorganTest {

    private static final String EMAIL = "user@example.com";
    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

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

    private JobServiceImpl jobService;
    private Path resumeFile;
    private List<ResumeDocumentNode> nodes;
    private ClaimLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        resumeFile = tempDir.resolve("resume.docx");
        Path pdf = GeneratedResumePdfFixture.pdf();
        GeneratedResumePdfFixture.writeCanonicalDocx(pdf, resumeFile);
        nodes = new ResumeDocumentServiceImpl().parseDocx(resumeFile);
        ledger = ClaimLedger.from(nodes);
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
                StoragePathResolver.forRoot(tempDir.resolve("uploads"))
        );
        lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> ConservativeLanguageJudge.passthroughRanking(invocation.getArgument(3)));
    }

    @Test
    void t2c_splitHomesInsteadOfMutualEnrichmentCycle() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> candidatesFor((ResumeDocumentNode) invocation.getArgument(0), spring, rest, true));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        TailorChange springChange = proposal.getChanges().stream()
                .filter(change -> spring.getNodeId().equals(change.getNodeId()))
                .findFirst()
                .orElse(null);
        TailorChange restChange = proposal.getChanges().stream()
                .filter(change -> rest.getNodeId().equals(change.getNodeId()))
                .findFirst()
                .orElse(null);
        assertTrue(restChange != null, texts(proposal));
        assertTrue(coversRest(restChange.getNewText()), texts(proposal));
        assertFalse(restChange.getNewText().toLowerCase(Locale.ROOT).contains("managements"), texts(proposal));
        if (springChange != null) {
            assertFalse(coversRest(springChange.getNewText()), texts(proposal));
            assertFalse(springChange.getNewText().toLowerCase(Locale.ROOT).contains("applications"), texts(proposal));
            String springLower = springChange.getNewText().toLowerCase(Locale.ROOT);
            assertTrue(springLower.contains("spring") && springLower.contains("mvc"), texts(proposal));
            boolean springSupportsRest = springChange.getEvidenceNodeIds() != null
                    && springChange.getEvidenceNodeIds().contains(rest.getNodeId())
                    && coversRest(springChange.getNewText());
            boolean restSupportsSpring = restChange.getEvidenceNodeIds() != null
                    && restChange.getEvidenceNodeIds().contains(spring.getNodeId())
                    && springLower.contains("rest");
            assertFalse(springSupportsRest && restSupportsSpring, texts(proposal));
        }
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                spring.getNodeId().equals(change.getNodeId())
                        && coversRest(change.getNewText())), texts(proposal));
        assertTrue(proposal.getChanges().size() >= 1 && proposal.getChanges().size() <= 4, texts(proposal));
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                change.getNewText() != null
                        && change.getNewText().toLowerCase(Locale.ROOT).contains("based on user needs")),
                texts(proposal));
    }

    @Test
    void t2c_docsAndCommsDoNotCrowdOutCoreTech() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode documentation = requireNode("well-documented");
        ResumeDocumentNode communication = requireNode("Communicated effectively");
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> candidatesFor((ResumeDocumentNode) invocation.getArgument(0), spring, rest, true));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);
        String joined = texts(proposal).toLowerCase(Locale.ROOT);

        assertTrue(proposal.getEligibleNodeIds().contains(maven.getNodeId()));
        assertTrue(proposal.getEligibleNodeIds().contains(documentation.getNodeId()));
        assertTrue(proposal.getEligibleNodeIds().contains(communication.getNodeId())
                || proposal.getExploredNodeIds() != null);
        assertTrue(joined.contains("spring") || joined.contains("rest") || joined.contains("documented"), texts(proposal));
        assertTrue(proposal.getChanges().stream().anyMatch(change ->
                spring.getNodeId().equals(change.getNodeId())
                        || rest.getNodeId().equals(change.getNodeId())
                        || maven.getNodeId().equals(change.getNodeId())
                        || documentation.getNodeId().equals(change.getNodeId())), texts(proposal));
        assertTrue(proposal.getRemainingNodeIds().contains(maven.getNodeId())
                || proposal.getExploredNodeIds().contains(maven.getNodeId())
                || proposal.getChanges().stream().anyMatch(change -> maven.getNodeId().equals(change.getNodeId())),
                texts(proposal));
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                maven.getNodeId().equals(change.getNodeId())
                        && change.getNewText() != null
                        && (change.getNewText().toLowerCase(Locale.ROOT).contains("cpu")
                        || change.getNewText().toLowerCase(Locale.ROOT).contains("oop"))),
                texts(proposal));
    }

    @Test
    void t2c_partialAlgorithmDoesNotConsumeMavenHome() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> candidatesFor((ResumeDocumentNode) invocation.getArgument(0), spring, rest, true));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);
        String joined = texts(proposal).toLowerCase(Locale.ROOT);

        assertTrue(proposal.getEligibleNodeIds().contains(maven.getNodeId()));
        assertTrue(proposal.getRemainingNodeIds().contains(maven.getNodeId())
                || proposal.getExploredNodeIds().contains(maven.getNodeId())
                || proposal.getChanges().stream().anyMatch(change -> maven.getNodeId().equals(change.getNodeId())),
                texts(proposal));
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                maven.getNodeId().equals(change.getNodeId())
                        && change.getNewText() != null
                        && (change.getNewText().toLowerCase(Locale.ROOT).contains("cpu")
                        || change.getNewText().toLowerCase(Locale.ROOT).contains("oop"))),
                texts(proposal));
        proposal.getChanges().stream()
                .filter(change -> algorithm.getNodeId().equals(change.getNodeId()))
                .forEach(change -> {
                    String lower = change.getNewText() == null ? "" : change.getNewText().toLowerCase(Locale.ROOT);
                    assertFalse(lower.contains("designed"), texts(proposal));
                    assertFalse(lower.contains("oop"), texts(proposal));
                });
        assertTrue(proposal.getChanges().size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE, texts(proposal));
        List<String> originals = proposal.getChanges().stream().map(TailorChange::getOriginalText).toList();
        List<String> rewritten = proposal.getChanges().stream().map(TailorChange::getNewText).toList();
        String union = String.join(" ", rewritten);
        List<String> missing = new java.util.ArrayList<>();
        for (String original : originals) {
            for (ConservativeMaterialFacts.Fact fact : ConservativeMaterialFacts.distinctive(
                    ConservativeMaterialFacts.extract(original))) {
                if (!ConservativeMaterialFacts.covers(fact, union)) {
                    missing.add(fact.key());
                }
            }
        }
        assertEquals(1.0, ConservativeMaterialFacts.batchRetention(originals, rewritten), 0.001, missing.toString());
    }

    @Test
    void t2c_noPassingCandidatesStillPendingEmptyChanges() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if (node == null) {
                        return candidates();
                    }
                    return candidates(
                            ConservativeCandidateHardGateTest.candidate(
                                    node.getNodeId(),
                                    "Directed the creation of " + node.getText(),
                                    List.of()),
                            ConservativeCandidateHardGateTest.candidate(
                                    node.getNodeId(),
                                    "Created an application using Docker and Kubernetes.",
                                    List.of()),
                            ConservativeCandidateHardGateTest.candidate(
                                    node.getNodeId(),
                                    "Led the team to invent 99.9% uptime metrics.",
                                    List.of())
                    );
                });

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(TailorProposal.STATUS_PENDING, proposal.getStatus());
        assertTrue(proposal.getChanges().isEmpty());
        assertEquals(0, proposal.getAppliedChangeCount());
        assertFalse(proposal.isHasSuggestions());
        assertTrue(proposal.isUnchanged());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void t2c_pendingResponseHasSuggestionsWithoutWritingFiles() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> candidatesFor((ResumeDocumentNode) invocation.getArgument(0), spring, rest, true));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(TailorProposal.STATUS_PENDING, proposal.getStatus());
        assertEquals(0, proposal.getAppliedChangeCount());
        assertTrue(proposal.isUnchanged());
        assertTrue(proposal.isHasSuggestions());
        assertFalse(proposal.getChanges().isEmpty());
        for (TailorChange change : proposal.getChanges()) {
            assertEquals(Boolean.FALSE, change.getApplied());
        }
        assertNull(proposal.getGeneratedFileName());
        assertNull(proposal.getDownloadUrl());
        assertNull(proposal.getPdfDownloadUrl());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(pdfExportService, never()).exportPdf(any());
    }

    @Test
    void t2c_proposeDoesNotChangeTailoredFileCount() throws Exception {
        Path realUploads = Path.of("C:\\ai job\\ai job\\uploads");
        Set<String> before = snapshotFiles(realUploads);
        Path tailored = tempDir.resolve("uploads").resolve("tailored");
        int localBefore = countFiles(tailored);
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> candidatesFor((ResumeDocumentNode) invocation.getArgument(0), spring, rest, true));

        jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(localBefore, countFiles(tailored));
        assertEquals(before, snapshotFiles(realUploads));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    private ConservativeRewriteCandidatesResponse candidatesFor(
            ResumeDocumentNode node,
            ResumeDocumentNode spring,
            ResumeDocumentNode rest,
            boolean includeGold) {

        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        String text = node.getText() == null ? "" : node.getText();
        String lower = text.toLowerCase(Locale.ROOT);
        if (spring.getNodeId().equals(node.getNodeId())) {
            return candidates(
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            ConservativeCandidateHardGateTest.P16_HOME,
                            List.of(rest.getNodeId())),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Designed and implemented a Java Spring Boot application using the MVC architecture.",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Directed the creation of a Java Spring Boot application using the MVC pattern.",
                            List.of())
            );
        }
        if (rest.getNodeId().equals(node.getNodeId())) {
            return candidates(
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            ConservativeCandidateHardGateTest.P17_HOME,
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Integrated RESTful APIs for authentication, product updates, and shopping cart workflows.",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Integrated RESTful APIs for authentication, product updates, and shopping cart workflows based on user needs.",
                            List.of())
            );
        }
        if (maven.getNodeId().equals(node.getNodeId())) {
            return candidates(
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            ConservativeCandidateHardGateTest.P21_HOME,
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Developed a Java Maven application helping users select budget-aligned components.",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Created a project that helps users select the best components.",
                            List.of())
            );
        }
        if (algorithm.getNodeId().equals(node.getNodeId())) {
            ResumeDocumentNode oop = requireNode("Object-Oriented Programming");
            return candidates(
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Implemented an algorithm that dynamically suggests CPU, GPU, and RAM components using object-oriented design.",
                            List.of(oop.getNodeId())),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Directed the creation of an algorithm for CPU suggestions.",
                            List.of())
            );
        }
        if (lower.contains("document") || lower.contains("communicat") || lower.contains("summary")) {
            return candidates(
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Communicated documentation quality to ensure quality across the team.",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            text + " to ensure quality",
                            List.of()),
                    ConservativeCandidateHardGateTest.candidate(
                            node.getNodeId(),
                            "Directed the creation of documentation.",
                            List.of())
            );
        }
        if (!includeGold) {
            return candidates(ConservativeCandidateHardGateTest.candidate(
                    node.getNodeId(), "Directed the creation of the work.", List.of()));
        }
        return candidates(
                ConservativeCandidateHardGateTest.candidate(node.getNodeId(), text + " using best practices.", List.of()),
                ConservativeCandidateHardGateTest.candidate(node.getNodeId(), "Directed the creation of the work.", List.of()),
                ConservativeCandidateHardGateTest.candidate(node.getNodeId(), "Created an application.", List.of())
        );
    }

    private void stubOwnedResources() {
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

    private void stubInsertProposal(long id) {
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(id);
            return 1;
        });
    }

    private ConservativeRewriteCandidatesResponse candidates(ConservativeRewriteCandidate... values) {
        ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
        response.setCandidates(List.of(values));
        return response;
    }

    private ResumeDocumentNode requireNode(String... needles) {
        for (ResumeDocumentNode node : nodes) {
            String text = node.getText() == null ? "" : node.getText();
            boolean all = true;
            for (String needle : needles) {
                if (!text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return node;
            }
        }
        throw new AssertionError("Missing node " + String.join(" + ", needles));
    }

    private static boolean coversRest(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("rest")
                && (lower.contains("auth") || lower.contains("authentication"))
                && (lower.contains("product") || lower.contains("cart"));
    }

    private static String texts(TailoredResumeResponse proposal) {
        return proposal.getChanges().stream()
                .map(change -> change.getNodeId() + "=" + change.getNewText())
                .collect(Collectors.joining("\n"));
    }

    private static int countFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var walk = Files.walk(directory)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static Set<String> snapshotFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }
}
