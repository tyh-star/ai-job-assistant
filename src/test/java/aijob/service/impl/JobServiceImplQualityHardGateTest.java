package aijob.service.impl;

import aijob.client.GreenhouseJobClient;
import aijob.config.StoragePathResolver;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.UpliftType;
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
import aijob.util.ConservativeLanguageJudge;
import aijob.util.TailorV2T2BAlexMorganTest;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class JobServiceImplQualityHardGateTest {

    private static final String EMAIL = "user@example.com";
    private static final String ALEX_MORGAN_P23 =
            "●   Developed and integrated IP rate-limiting and black/white list management features to strengthen access control and mitigate potential attack traffic.";
    private static final String DIRECTED_P23 =
            "●   Directed and integrated IP rate-limiting and black/white list management features to strengthen access control and mitigate potential attack traffic.";

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

    @BeforeEach
    void setUp() throws Exception {
        resumeFile = tempDir.resolve("resume.docx");
        Files.writeString(resumeFile, "placeholder");
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
    void g7_crossEntryFactsNeverEnterProposal() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-15", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "●   Build REST APIs in Java."),
                new ResumeDocumentNode("p-17", "PARAGRAPH", "Data Analyst | Other Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-18", "PARAGRAPH",
                        "●   Deployed backend services on AWS for production workloads.")
        ));
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-16".equals(node.getNodeId())) {
                        ConservativeRewriteCandidate candidate = candidate("●   Built REST APIs in Java on AWS.");
                        candidate.setUsedFactNodeIds(List.of("p-16", "p-18"));
                        return candidates(candidate);
                    }
                    return candidates(candidate(node.getText()));
                });

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                "p-16".equals(change.getNodeId()) && change.getNewText() != null
                        && change.getNewText().toLowerCase().contains("aws")));
        assertTrue(proposal.getRejectedChanges().stream().anyMatch(item ->
                "p-16".equals(item.getNodeId())
                        && RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(item.getReasonCode())));
    }

    @Test
    void g8_sameEntryFactsAreNotCopiedOntoAnotherBullet() {
        stubOwnedResources();
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Need Java REST APIs and AWS production services.");
        when(jobMapper.findById(10L)).thenReturn(job);
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-15", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-16", "PARAGRAPH", "●   Build REST APIs in Java."),
                new ResumeDocumentNode("p-17", "PARAGRAPH",
                        "●   Deployed backend services on AWS for production workloads.")
        ));
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-16".equals(node.getNodeId())) {
                        ConservativeRewriteCandidate copied = candidate(
                                "●   Built REST APIs in Java and deployed backend services on AWS for production workloads.");
                        copied.setUsedFactNodeIds(List.of("p-16", "p-17"));
                        copied.setUsedSupportingNodeIds(List.of("p-17"));
                        return candidates(copied);
                    }
                    ConservativeRewriteCandidate candidate = candidate(
                            "●   Deployed backend services onto AWS for production workloads.");
                    candidate.setUsedFactNodeIds(List.of("p-17"));
                    return candidates(candidate);
                });

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                "p-16".equals(change.getNodeId())
                        && change.getNewText() != null
                        && change.getNewText().toLowerCase().contains("aws")));
        assertTrue(proposal.getRejectedChanges().stream().anyMatch(item ->
                "p-16".equals(item.getNodeId())), proposal.getRejectedChanges().toString());
        assertEquals(TailorProposal.STATUS_PENDING, proposal.getStatus());
        assertEquals(0, proposal.getAppliedChangeCount());
    }

    @Test
    void g9_grammarFixesOnDifferentNodesAreKept() {
        stubOwnedResources();
        stubInsertProposal(42L);
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
                    return candidates(candidate(
                            original.replace("managements", "operations")
                                    .replace(" updates and", " updates, and")));
                });

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        long grammar = proposal.getChanges().stream()
                .filter(change -> UpliftType.GRAMMAR_FIX.equals(change.getUpliftType()))
                .count();
        assertEquals(2, grammar);
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void g11_goodResumeAllowsNoOpProposal() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Designed and implemented a Java Spring Boot application using the MVC architecture.")
        ));
        lenient().when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(
                        "Directed a Java Spring Boot application using the MVC architecture.")));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(TailorProposal.STATUS_PENDING, proposal.getStatus());
        assertTrue(proposal.getChanges().isEmpty());
        assertEquals(0, proposal.getAppliedChangeCount());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        verify(pdfExportService, never()).exportPdf(any());
    }

    @Test
    void g12_developedToDirectedDoesNotEnterProposal() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", ALEX_MORGAN_P23)
        ));
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(DIRECTED_P23)));
        when(aiResumeService.generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull()))
                .thenReturn(candidates(candidate(DIRECTED_P23)));

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                change.getNewText() != null && change.getNewText().toLowerCase().contains("directed")));
        assertTrue(proposal.getRejectedChanges().stream()
                .anyMatch(item -> RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(item.getReasonCode())));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void t2b_allThreeCandidatesRejected_planIsNoOp() {
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-13", "PARAGRAPH", "SmartPC | 2023"),
                new ResumeDocumentNode("p-19", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget")
        ));
        ConservativeRewriteCandidatesResponse batch = candidates(
                candidate("Directed the creation of a Java Maven project assisting users in selecting the best components within budget limits."),
                candidate("Developed a project that helps users select optimal components within a given budget"),
                candidate("Led a Java Maven project that helps users select optimal components within a given budget")
        );
        lenient().when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(batch);
        lenient().when(aiResumeService.generateConstrainedTailorCandidates(
                any(), any(), any(), org.mockito.ArgumentMatchers.notNull()))
                .thenReturn(batch);

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        System.out.println("T2B all-three-fail suggestion count=" + proposal.getAppliedChangeCount()
                + " rejected=" + proposal.getRejectedChanges());
        assertTrue(proposal.getChanges().isEmpty());
        assertEquals(0, proposal.getAppliedChangeCount());
        assertTrue(proposal.getRejectedChanges().isEmpty()
                || proposal.getRejectedChanges().stream().anyMatch(item ->
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(item.getReasonCode())
                        || RejectedTailorChange.INFORMATION_LOSS.equals(item.getReasonCode())
                        || RejectedTailorChange.LOW_VALUE_REWRITE.equals(item.getReasonCode())));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void t2b_syntheticSuggestionCountWithMixedCandidates() {
        stubOwnedResources();
        Job job = new Job();
        job.setId(10L);
        job.setUserId(1L);
        job.setDescription("Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.");
        when(jobMapper.findById(10L)).thenReturn(job);
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(TailorV2T2BAlexMorganTest.alexMorganT2bNodes());
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    if ("p-16".equals(node.getNodeId())) {
                        return candidates(
                                candidate("Directed the creation of a Java Spring Boot application using the MVC pattern."),
                                candidate("Designed and implemented an application using the MVC architecture."),
                                candidate("Designed and implemented a Java Spring Boot application using the MVC architecture.")
                        );
                    }
                    return candidates(
                            candidate("Directed the creation of " + node.getText()),
                            candidate("Created work."),
                            candidate("Led the team to deliver the work.")
                    );
                });

        TailoredResumeResponse proposal = jobService.createTailorProposal(EMAIL, 10L, 3L);

        System.out.println("T2B current suggestion count=" + proposal.getChanges().size());
        System.out.println("T2B rejected=" + proposal.getRejectedChanges());
        assertFalse(proposal.getChanges().isEmpty());
        assertTrue(proposal.getChanges().stream().anyMatch(change -> "p-16".equals(change.getNodeId())));
        assertTrue(proposal.getChanges().stream().noneMatch(change ->
                change.getNewText() != null && change.getNewText().toLowerCase().contains("directed")));
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void hardGate_doesNotTouchRealUploads() throws Exception {
        Path realUploads = Path.of("C:\\ai job\\ai job\\uploads");
        Set<String> before = snapshotFiles(realUploads);
        // The working directory's uploads is only the real one when tests run from the
        // project root, so it gets its own before-snapshot.
        Path workingDirUploads = Path.of("uploads").toAbsolutePath().normalize();
        Set<String> workingDirBefore = snapshotFiles(workingDirUploads);
        stubOwnedResources();
        stubInsertProposal(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-23", "PARAGRAPH", ALEX_MORGAN_P23)
        ));
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), isNull()))
                .thenReturn(candidates(candidate(DIRECTED_P23)));

        jobService.createTailorProposal(EMAIL, 10L, 3L);

        assertEquals(before, snapshotFiles(realUploads));
        assertEquals(workingDirBefore, snapshotFiles(workingDirUploads));
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

    private ConservativeRewriteCandidate candidate(String newText) {
        ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
        candidate.setNewText(newText);
        candidate.setReason("Rewritten");
        return candidate;
    }

    private static Set<String> snapshotFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }
    }
}
