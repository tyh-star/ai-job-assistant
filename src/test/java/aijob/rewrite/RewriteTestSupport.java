package aijob.rewrite;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.rewrite.ai.RewriteAiClient;
import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.ai.RewriteAiProperties;
import aijob.rewrite.ai.RewriteAiRequest;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/** Shared fictional fixtures: a real DOCX, a scripted AI client, fixed properties. */
final class RewriteTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static final List<String> PARAGRAPHS = List.of(
            "Alex Morgan",
            "alex.morgan@example.com | +1 416 555 0199",
            "EXPERIENCE",
            "Software Developer | Example Corp | 2021 - 2023",
            "responsible for build of internal dashboards using React and Java for 3 teams",
            "worked on improving the deploy process which reduced release time by 40%",
            "EDUCATION",
            "Example University, Bachelor of Science, 2020");

    private RewriteTestSupport() {
    }

    static Path docx(Path dir) throws Exception {
        Path file = dir.resolve("source.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            for (String text : PARAGRAPHS) {
                document.createParagraph().createRun().setText(text);
            }
            document.write(out);
        }
        return file;
    }

    static RewriteResumeLoader.LoadedResume loaded(Path docx, String fileName) {
        User user = new User();
        user.setId(7L);
        user.setName("Alex Morgan");
        user.setEmail("alex@example.com");
        Resume resume = new Resume();
        resume.setId(12L);
        resume.setUserId(7L);
        resume.setFileName(fileName);
        resume.setFileType("docx");
        resume.setFilePath(docx.toString());
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        return new RewriteResumeLoader.LoadedResume(user, resume, docx, nodes, List.of("Alex Morgan"));
    }

    static RewriteAiProperties properties(String key) {
        return new RewriteAiProperties("https://api.groq.com/openai/v1", key,
                "openai/gpt-oss-20b", "openai/gpt-oss-120b", 30);
    }

    static StoragePathResolver storage(Path root) {
        return StoragePathResolver.forRoot(root);
    }

    static String docxText(Path file) throws Exception {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
            return document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
        }
    }

    /** Answers each call with the next scripted JSON (or throws a scripted failure) and records requests. */
    static final class ScriptedAi implements RewriteAiClient {
        final List<RewriteAiRequest> requests = new ArrayList<>();
        private final Deque<Object> script = new ArrayDeque<>();

        ScriptedAi then(String json) {
            script.add(json);
            return this;
        }

        ScriptedAi thenFail(RewriteAiException failure) {
            script.add(failure);
            return this;
        }

        @Override
        public JsonNode completeJson(RewriteAiRequest request) {
            requests.add(request);
            Object next = script.poll();
            if (next == null) {
                throw new AssertionError("unexpected AI call #" + requests.size() + " (" + request.schemaName() + ")");
            }
            if (next instanceof RewriteAiException failure) {
                throw failure;
            }
            try {
                return MAPPER.readTree((String) next);
            } catch (Exception e) {
                throw RewriteAiException.invalidOutput("test");
            }
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
