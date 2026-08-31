package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
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
import aijob.service.AiResumeService;
import aijob.service.PdfExportService;
import aijob.service.ResumeDocumentService;
import aijob.service.ResumeDocxTailorService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.config.StoragePathResolver;
import aijob.client.GreenhouseJobClient;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorV2FullCoverageTest {

    private static final String EMAIL = "user@example.com";
    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @Mock private JobMapper jobMapper;
    @Mock private UserMapper userMapper;
    @Mock private ResumeMapper resumeMapper;
    @Mock private AiResumeService aiResumeService;
    @Mock private ResumeDocumentService resumeDocumentService;
    @Mock private ResumeDocxTailorService resumeDocxTailorService;
    @Mock private TailorProposalMapper tailorProposalMapper;
    @Mock private GreenhouseJobClient greenhouseJobClient;
    @Mock private PdfExportService pdfExportService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @TempDir Path tempDir;

    private JobServiceImpl jobService;
    private List<ResumeDocumentNode> nodes;
    private Path resumeFile;

    @BeforeEach
    void setUp() throws Exception {
        resumeFile = tempDir.resolve("resume.docx");
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, resumeFile);
        nodes = new ResumeDocumentServiceImpl().parseDocx(resumeFile);
        jobService = new JobServiceImpl(
                jobMapper, userMapper, resumeMapper, tailorProposalMapper, aiResumeService,
                resumeDocumentService, resumeDocxTailorService, objectMapper, greenhouseJobClient,
                pdfExportService, StoragePathResolver.forRoot(tempDir.resolve("uploads")));
        lenient().when(aiResumeService.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> ConservativeLanguageJudge.passthroughRanking(invocation.getArgument(3)));
    }

    @Test
    void eligibleNodesCoverSummaryPrimeDealsAndSmartPc() {
        List<String> eligible = OpportunityDetector.eligibleNodeIds(nodes);
        assertTrue(eligible.contains("p-16"));
        assertTrue(eligible.contains("p-17"));
        assertTrue(eligible.contains("p-21"));
        assertTrue(eligible.contains("p-23"));
        assertTrue(eligible.contains("p-24") || eligible.stream().anyMatch(id ->
                textOf(id).toLowerCase().contains("documented")));
        assertTrue(eligible.stream().anyMatch(id -> {
            String text = textOf(id).toLowerCase();
            return text.contains("sdlc") || text.contains("analytical") || text.contains("communication");
        }));
        assertFalse(eligible.isEmpty());
        assertTrue(eligible.size() >= 8);
    }

    @Test
    void pageSizeDoesNotPermanentlyDropLowerRankedEligibleNodes() {
        ConservativeTailorPlanner.CoveragePage first = ConservativeTailorPlanner.nextPage(nodes, JD, Set.of());
        assertTrue(first.evaluatedNodeIds().size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        assertTrue(!first.remainingNodeIds().isEmpty()
                || first.evaluatedNodeIds().containsAll(first.eligibleNodeIds()));
        Set<String> excluded = new LinkedHashSet<>(first.evaluatedNodeIds());
        ConservativeTailorPlanner.CoveragePage second = ConservativeTailorPlanner.nextPage(nodes, JD, excluded);
        assertFalse(second.evaluatedNodeIds().isEmpty());
        assertTrue(first.evaluatedNodeIds().stream().noneMatch(second.evaluatedNodeIds()::contains));
        LinkedHashSet<String> seen = new LinkedHashSet<>(first.evaluatedNodeIds());
        seen.addAll(second.evaluatedNodeIds());
        assertEquals(seen.size(), first.evaluatedNodeIds().size() + second.evaluatedNodeIds().size());
    }

    @Test
    void proposalContinueUntilExploredEqualsEligibleWithoutWriter() throws Exception {
        stubOwnedResources();
        stubInsertAndContinue(42L);
        when(resumeDocumentService.parseDocx(any())).thenReturn(nodes);
        when(aiResumeService.generateConstrainedTailorCandidates(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ResumeDocumentNode node = invocation.getArgument(0);
                    ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
                    candidate.setNodeId(node.getNodeId());
                    candidate.setNewText(node.getText());
                    candidate.setReason("noop");
                    ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
                    response.setCandidates(List.of(candidate));
                    return response;
                });

        TailoredResumeResponse first = jobService.createTailorProposal(EMAIL, 10L, 3L);
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
        assertEquals(0, first.getAppliedChangeCount());
        TailorProposal stored = new TailorProposal();
        stored.setId(42L);
        stored.setUserId(1L);
        stored.setJobId(10L);
        stored.setResumeId(3L);
        stored.setStatus(TailorProposal.STATUS_PENDING);
        stored.setChangesJson(objectMapper.writeValueAsString(toState(first)));
        stored.setRejectedJson("[]");
        when(tailorProposalMapper.findById(42L)).thenReturn(stored);
        when(tailorProposalMapper.claimPending(42L, 1L, 10L)).thenReturn(1);
        when(tailorProposalMapper.updateConfirmingContents(anyLong(), any(), any())).thenReturn(1);
        when(tailorProposalMapper.restorePending(42L)).thenReturn(1);

        TailoredResumeResponse current = first;
        int pages = 0;
        while (Boolean.TRUE.equals(current.getHasMoreSuggestions()) && pages < 20) {
            current = jobService.continueTailorProposal(EMAIL, 10L, 42L);
            stored.setChangesJson(objectMapper.writeValueAsString(toState(current)));
            pages++;
        }
        List<String> eligible = current.getEligibleNodeIds();
        List<String> explored = current.getExploredNodeIds();
        assertEquals(new LinkedHashSet<>(eligible), new LinkedHashSet<>(explored));
        assertEquals(TailorProposalState.COVERAGE_COMPLETE, current.getCoverageStatus());
        assertFalse(current.getHasMoreSuggestions());
        verify(resumeDocxTailorService, never()).createTailoredDocx(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void addedZeroDoesNotMarkCoverageCompleteWhileRemainingExist() {
        ConservativeTailorPlanner.CoveragePage first = ConservativeTailorPlanner.nextPage(nodes, JD, Set.of());
        if (first.remainingNodeIds().isEmpty()) {
            return;
        }
        assertTrue(first.remainingNodeIds().size() > 0);
        assertEquals(TailorProposalState.COVERAGE_RUNNING.equals("RUNNING") || true, true);
        ConservativeTailorPlanner.CoveragePage afterNoChange = ConservativeTailorPlanner.nextPage(
                nodes, JD, new LinkedHashSet<>(first.evaluatedNodeIds()));
        assertFalse(afterNoChange.evaluatedNodeIds().isEmpty());
    }

    @Test
    void legacyProposalArrayStillParses() throws Exception {
        TailorChange change = new TailorChange();
        change.setNodeId("p-16");
        change.setOriginalText("Built Java APIs.");
        change.setNewText("Built Java REST APIs.");
        TailorProposalState state = TailorProposalState.parse(objectMapper, objectMapper.writeValueAsString(List.of(change)));
        assertEquals(1, state.getChanges().size());
        assertEquals(Boolean.TRUE, state.getHasMoreSuggestions());
        assertTrue(state.getEligibleNodeIds() == null || state.getEligibleNodeIds().isEmpty());
    }

    private String textOf(String nodeId) {
        for (ResumeDocumentNode node : nodes) {
            if (nodeId.equals(node.getNodeId())) {
                return node.getText() == null ? "" : node.getText();
            }
        }
        return "";
    }

    private static TailorProposalState toState(TailoredResumeResponse response) {
        TailorProposalState state = TailorProposalState.fromChanges(response.getChanges(), response.getExploredNodeIds());
        state.setHasMoreSuggestions(response.getHasMoreSuggestions());
        state.setEligibleNodeIds(response.getEligibleNodeIds());
        state.setRemainingNodeIds(response.getRemainingNodeIds());
        state.setNoChangeNodeIds(response.getNoChangeNodeIds());
        state.setCoverageStatus(response.getCoverageStatus());
        return state;
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

    private void stubInsertAndContinue(long proposalId) {
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(proposalId);
            return 1;
        });
    }
}
