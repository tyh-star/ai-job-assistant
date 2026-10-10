package aijob.rewrite;

import aijob.entity.Job;
import aijob.entity.TailorProposal;
import aijob.exception.JobNotFoundException;
import aijob.exception.TailorProposalConflictException;
import aijob.mapper.JobMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.rewrite.dto.ProRewriteRequests;
import aijob.rewrite.dto.ProRewriteSessionResponse;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProResumeRewriteServiceTest {

    @TempDir
    Path tempDir;

    private final Map<Long, TailorProposal> rows = new HashMap<>();
    private RewriteTestSupport.ScriptedAi ai;
    private ProResumeRewriteService service;
    private Path uploads;

    private static final String REQUIREMENTS = """
            {"requiredSkills":["React","Java","Kubernetes"],"preferredSkills":["GraphQL"],
             "responsibilities":["Build internal dashboards"],"keywords":["CI/CD"],"experienceLevel":"Mid"}""";

    private static final String ASSESSMENT = """
            {"assessments":[
              {"requirementId":"R1","status":"VERIFIED","evidenceNodeIds":["p-4"],"note":"React in dashboards"},
              {"requirementId":"R2","status":"VERIFIED","evidenceNodeIds":["p-404"],"note":"cited a node that does not exist"},
              {"requirementId":"R3","status":"UNSUPPORTED","evidenceNodeIds":[],"note":""},
              {"requirementId":"R4","status":"INFERRED","evidenceNodeIds":["p-4"],"note":"API for dashboards?"},
              {"requirementId":"R5","status":"VERIFIED","evidenceNodeIds":["p-4"],"note":""},
              {"requirementId":"R6","status":"INFERRED","evidenceNodeIds":["p-5"],"note":"deploy process"}],
             "questions":[
              {"requirementId":"R1","question":"Did you use React?","reason":"already verified"},
              {"requirementId":"R3","question":"Have you used Kubernetes?","reason":"required"},
              {"requirementId":"R3","question":"Kubernetes again?","reason":"duplicate"},
              {"requirementId":"R4","question":"Did the dashboards use a GraphQL API you built?","reason":"preferred"},
              {"requirementId":"R6","question":"Did you automate the deploy with CI/CD?","reason":"keyword"}]}""";

    @BeforeEach
    void setUp() throws Exception {
        Path docx = RewriteTestSupport.docx(tempDir);
        RewriteResumeLoader loader = mock(RewriteResumeLoader.class);
        var loaded = RewriteTestSupport.loaded(docx, "简历.docx");
        when(loader.load("alex@example.com", 12L)).thenReturn(loaded);
        when(loader.user("alex@example.com")).thenReturn(loaded.user());

        JobMapper jobMapper = mock(JobMapper.class);
        Job job = new Job();
        job.setId(30L);
        job.setUserId(7L);
        job.setTitle("Frontend Engineer");
        job.setCompany("Example Co");
        job.setDescription("We need React, Java and Kubernetes. GraphQL is a plus. CI/CD experience.");
        when(jobMapper.findById(30L)).thenReturn(job);

        TailorProposalMapper mapper = mock(TailorProposalMapper.class);
        when(mapper.insert(any())).thenAnswer(inv -> {
            TailorProposal row = inv.getArgument(0);
            row.setId((long) rows.size() + 100);
            rows.put(row.getId(), row);
            return 1;
        });
        when(mapper.findById(anyLong())).thenAnswer(inv -> rows.get((Long) inv.getArgument(0)));
        when(mapper.updateRewriteSession(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    TailorProposal row = rows.get((Long) inv.getArgument(0));
                    if (row == null || !row.getStatus().equals(inv.getArgument(2))) {
                        return 0;
                    }
                    row.setStatus(inv.getArgument(3));
                    row.setChangesJson(inv.getArgument(4));
                    row.setGeneratedFileName(inv.getArgument(5));
                    row.setGeneratedFilePath(inv.getArgument(6));
                    return 1;
                });

        ai = new RewriteTestSupport.ScriptedAi();
        uploads = tempDir.resolve("uploads");
        var storage = RewriteTestSupport.storage(uploads);
        service = new ProResumeRewriteService(loader, jobMapper, mapper, ai, RewriteTestSupport.properties("test-key"),
                new ResumeDocxTailorServiceImpl(new ResumeDocumentServiceImpl(), storage), storage,
                RewriteTestSupport.MAPPER);
    }

    private ProRewriteSessionResponse start() {
        ai.then(REQUIREMENTS).then(ASSESSMENT);
        return service.start("alex@example.com", new ProRewriteRequests.Start(12L, 30L));
    }

    @Test
    void requiresATargetJob() {
        assertThrows(JobNotFoundException.class,
                () -> service.start("alex@example.com", new ProRewriteRequests.Start(12L, null)));
        assertTrue(ai.requests.isEmpty());
    }

    @Test
    void analyzesWithTheProModelAndLabelsEvidence() {
        ProRewriteSessionResponse session = start();

        assertEquals(ProResumeRewriteService.ANALYZED, session.status());
        assertTrue(ai.requests.stream().allMatch(r -> r.model().equals("openai/gpt-oss-120b")));
        assertEquals(List.of("job_requirements", "requirement_assessment"),
                ai.requests.stream().map(r -> r.schemaName()).toList(), "analysis and gap check are separate calls");
        Map<String, String> status = new HashMap<>();
        session.items().forEach(i -> status.put(i.text, i.status));
        assertEquals("VERIFIED", status.get("React"));
        assertEquals("VERIFIED", status.get("Java"), "a literal match in the resume is verified deterministically");
        assertEquals("UNSUPPORTED", status.get("Kubernetes"));
        assertEquals("INFERRED", status.get("GraphQL"));
        assertEquals(List.of("R3", "R4", "R6"), session.questions().stream().map(q -> q.requirementId).toList(),
                "no question for a verified item, no duplicates");
        assertNull(session.downloadUrl());
    }

    @Test
    void asksAtMostFiveQuestions() {
        StringBuilder skills = new StringBuilder();
        StringBuilder questions = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            skills.append(i > 1 ? "," : "").append("\"Tool").append(i).append("\"");
            questions.append(i > 1 ? "," : "").append("{\"requirementId\":\"R").append(i)
                    .append("\",\"question\":\"Used Tool").append(i).append("?\",\"reason\":\"r\"}");
        }
        ai.then("{\"requiredSkills\":[" + skills + "],\"preferredSkills\":[],\"responsibilities\":[],\"keywords\":[],\"experienceLevel\":\"\"}")
                .then("{\"assessments\":[],\"questions\":[" + questions + "]}");
        ProRewriteSessionResponse session = service.start("alex@example.com", new ProRewriteRequests.Start(12L, 30L));
        assertEquals(5, session.questions().size());
    }

    @Test
    void unsupportedContentNeverReachesTheFileAndNothingIsWrittenBeforeConfirmation() throws Exception {
        ProRewriteSessionResponse session = start();
        Long id = session.sessionId();

        session = service.answer("alex@example.com", id, new ProRewriteRequests.Answers(List.of(
                new ProRewriteRequests.Answer("Q2", true, "I built the GraphQL API the dashboards read from"),
                new ProRewriteRequests.Answer("Q1", false, null),
                new ProRewriteRequests.Answer("Q3", false, ""))));
        assertEquals(ProResumeRewriteService.ANSWERED, session.status());

        String good = "{\"nodeId\":\"p-4\",\"originalText\":\"x\",\"revisedText\":"
                + "\"Built internal dashboards with React, Java and a GraphQL API for 3 teams\","
                + "\"reason\":\"matches the role\",\"requirementIds\":[\"R1\",\"R4\"]}";
        String unsupported = "{\"nodeId\":\"p-5\",\"originalText\":\"x\",\"revisedText\":"
                + "\"Improved the Kubernetes deploy process, which reduced release time by 40%\","
                + "\"reason\":\"adds Kubernetes\",\"requirementIds\":[\"R3\"]}";
        ai.then("{\"suggestions\":[" + good + "," + unsupported + "]}")
                .then("{\"suggestions\":[" + good + "," + unsupported + "]}");
        session = service.preview("alex@example.com", id);

        assertEquals(ProResumeRewriteService.PREVIEWED, session.status());
        assertEquals(1, session.suggestions().size());
        assertEquals("USER_CONFIRMED", session.suggestions().get(0).evidenceStatus);
        assertEquals(1, session.rejectedSuggestionCount());
        assertFalse(Files.exists(uploads.resolve("rewrites")), "no file before the user confirms");

        // The final pass tries to sneak Kubernetes in; the fact check keeps the accepted text.
        ai.then("{\"finalTexts\":[{\"nodeId\":\"p-4\",\"finalText\":"
                + "\"Built internal dashboards with React, Java, GraphQL and Kubernetes for 3 teams\"}]}");
        session = service.confirm("alex@example.com", id, new ProRewriteRequests.Confirm(List.of("S1")));

        assertEquals(ProResumeRewriteService.COMPLETED, session.status());
        assertEquals("pro_rewrite_final", ai.requests.get(ai.requests.size() - 1).schemaName());
        assertTrue(ai.requests.stream().allMatch(r -> r.model().equals("openai/gpt-oss-120b")));
        assertEquals("简历_pro-rewrite.docx", session.downloadFileName());
        var download = service.download("alex@example.com", id);
        String text = RewriteTestSupport.docxText(download.path());
        assertTrue(text.contains("Built internal dashboards with React, Java and a GraphQL API for 3 teams"));
        assertFalse(text.contains("Kubernetes"));
    }

    @Test
    void confirmIsRefusedBeforeSuggestionsExist() {
        ProRewriteSessionResponse session = start();
        assertThrows(TailorProposalConflictException.class, () -> service.confirm("alex@example.com",
                session.sessionId(), new ProRewriteRequests.Confirm(List.of("S1"))));
        assertThrows(TailorProposalConflictException.class, () -> service.preview("alex@example.com", session.sessionId()),
                "questions must be answered (or skipped) before suggestions");
    }
}
