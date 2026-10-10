package aijob.rewrite;

import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.dto.FreeRewriteResponse;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreeResumeRewriteServiceTest {

    @TempDir
    Path tempDir;

    private RewriteResumeLoader loader;
    private RewriteTestSupport.ScriptedAi ai;
    private FreeResumeRewriteService service;

    @BeforeEach
    void setUp() throws Exception {
        Path docx = RewriteTestSupport.docx(tempDir);
        loader = mock(RewriteResumeLoader.class);
        when(loader.load("alex@example.com", 12L)).thenReturn(RewriteTestSupport.loaded(docx, "简历.pdf"));
        ai = new RewriteTestSupport.ScriptedAi();
        var storage = RewriteTestSupport.storage(tempDir.resolve("uploads"));
        service = new FreeResumeRewriteService(loader, ai, RewriteTestSupport.properties("test-key"),
                new ResumeDocxTailorServiceImpl(new ResumeDocumentServiceImpl(), storage), storage,
                RewriteTestSupport.MAPPER);
    }

    private static String change(String nodeId, String original, String revised) {
        return "{\"nodeId\":\"" + nodeId + "\",\"originalText\":\"" + original + "\",\"revisedText\":\"" + revised
                + "\",\"reason\":\"clearer wording\"}";
    }

    @Test
    void usesTheFreeModelAndSendsNoJobAndNoPersonalDetails() {
        ai.then("{\"changes\":[]}");
        service.rewrite("alex@example.com", 12L);

        assertEquals(1, ai.requests.size());
        var request = ai.requests.get(0);
        assertEquals("openai/gpt-oss-20b", request.model());
        assertEquals("free_resume_rewrite", request.schemaName());
        assertFalse(request.userPrompt().toLowerCase().contains("job"), "free rewrite must not mention a job");
        assertFalse(request.userPrompt().contains("alex.morgan@example.com"));
        assertFalse(request.userPrompt().contains("Alex Morgan"));
        assertTrue(request.temperature() <= 0.3);
    }

    @Test
    void hasNoJobMatchingOrJobParsingDependency() {
        Constructor<?> constructor = FreeResumeRewriteService.class.getConstructors()[0];
        String types = Arrays.toString(constructor.getParameterTypes());
        for (String forbidden : new String[]{"JobService", "TailorAnalysisService", "JobRequirementExtractor", "JobMapper"}) {
            assertFalse(types.contains(forbidden), "free rewrite must not depend on " + forbidden);
        }
    }

    @Test
    void writesAcceptedChangesIntoTheDocxAndOffersAChineseFileName() throws Exception {
        ai.then("{\"changes\":[" + change("p-4",
                "responsible for build of internal dashboards using React and Java for 3 teams",
                "Built internal dashboards with React and Java for 3 teams") + "]}");

        FreeRewriteResponse response = service.rewrite("alex@example.com", 12L);

        assertEquals(1, response.changeCount());
        assertEquals("简历_rewrite.docx", response.downloadFileName());
        assertTrue(response.downloadUrl().startsWith("/api/v1/resume-rewrites/free/files/r12-"));
        String fileName = response.downloadUrl().substring(response.downloadUrl().lastIndexOf('/') + 1);
        when(loader.user("alex@example.com")).thenReturn(RewriteTestSupport.loaded(tempDir.resolve("source.docx"), "简历.pdf").user());
        when(loader.ownedResume(12L, 7L)).thenReturn(RewriteTestSupport.loaded(tempDir.resolve("source.docx"), "简历.pdf").resume());
        var download = service.download("alex@example.com", fileName);
        assertEquals("简历_rewrite.docx", download.downloadName());
        String text = RewriteTestSupport.docxText(download.path());
        assertTrue(text.contains("Built internal dashboards with React and Java for 3 teams"));
        assertTrue(text.contains("alex.morgan@example.com"), "personal details stay in the file");
    }

    @Test
    void neverWritesAChangeThatAltersProtectedFacts() throws Exception {
        String bad = change("p-5",
                "worked on improving the deploy process which reduced release time by 40%",
                "Led the Kubernetes migration at Acme that cut release time by 60%");
        ai.then("{\"changes\":[" + bad + "]}").then("{\"changes\":[" + bad + "]}");

        FreeRewriteResponse response = service.rewrite("alex@example.com", 12L);

        assertEquals(2, ai.requests.size(), "one automatic retry with the violations as feedback");
        assertTrue(ai.requests.get(1).userPrompt().contains("broke the rules"));
        assertEquals(0, response.changeCount());
        assertEquals(1, response.rejectedCount());
        assertTrue(response.unchanged());
        Path file = Files.list(tempDir.resolve("uploads").resolve("rewrites").resolve("7")).findFirst().orElseThrow();
        assertFalse(RewriteTestSupport.docxText(file).contains("Kubernetes"));
    }

    @Test
    void retriesOnceOnInvalidJsonThenSucceeds() {
        ai.thenFail(RewriteAiException.invalidOutput("bad")).then("{\"changes\":[]}");
        FreeRewriteResponse response = service.rewrite("alex@example.com", 12L);
        assertEquals(2, ai.requests.size());
        assertTrue(response.unchanged());
    }

    @Test
    void failsSafelyAfterTwoInvalidAnswersWithoutWritingAFile() {
        ai.thenFail(RewriteAiException.invalidOutput("bad")).thenFail(RewriteAiException.invalidOutput("bad"));
        RewriteAiException error = assertThrows(RewriteAiException.class, () -> service.rewrite("alex@example.com", 12L));
        assertEquals(RewriteAiException.INVALID_OUTPUT, error.code());
        assertEquals(2, ai.requests.size());
        assertFalse(Files.exists(tempDir.resolve("uploads").resolve("rewrites")));
    }

    @Test
    void rateLimitIsNotRetried() {
        ai.thenFail(RewriteAiException.rateLimited());
        RewriteAiException error = assertThrows(RewriteAiException.class, () -> service.rewrite("alex@example.com", 12L));
        assertEquals(RewriteAiException.RATE_LIMITED, error.code());
        assertEquals(1, ai.requests.size());
    }

    @Test
    void unknownNodeIdsAreDropped() {
        String ghost = change("p-99", "x", "Invented paragraph about leadership");
        ai.then("{\"changes\":[" + ghost + "]}").then("{\"changes\":[" + ghost + "]}");
        FreeRewriteResponse response = service.rewrite("alex@example.com", 12L);
        assertEquals(0, response.changeCount());
    }
}
