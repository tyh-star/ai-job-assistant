package aijob.rewrite;

import aijob.config.StoragePathResolver;
import aijob.dto.TailorChange;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.TailoredFileNotFoundException;
import aijob.rewrite.ai.RewriteAiClient;
import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.ai.RewriteAiProperties;
import aijob.rewrite.ai.RewriteAiRequest;
import aijob.rewrite.dto.FreeRewriteResponse;
import aijob.rewrite.dto.RewriteChangeView;
import aijob.service.ResumeDocxTailorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free Resume Rewrite: grammar, wording and clarity only, on the resume alone. No job,
 * no job matching or parsing, no keywords. Flow: parse DOCX nodes -> redact personal
 * details -> one 20B structured call -> check node ids and protected facts (one retry)
 * -> restore details -> write the DOCX -> download.
 */
@Service
public class FreeResumeRewriteService {

    static final String SYSTEM_PROMPT = """
            You are a careful resume editor. Improve only grammar, wording, clarity, action verbs, \
            sentence structure and consistency of the given resume paragraphs.
            Hard rules:
            - Never add skills, tools, employers, job titles, responsibilities, numbers, metrics, \
            dates or achievements that are not already in that paragraph.
            - Keep every number, date, name, company, school, job title, product and technology \
            exactly as written.
            - Keep every placeholder such as [[PII_1]] exactly as written.
            - Write in the same language as the paragraph. Keep bullets concise.
            - Return only paragraphs you actually improved; skip paragraphs that are already good.
            - originalText must be the exact input text for that nodeId; reason is one short sentence.
            """;

    private static final int MAX_NODES = 60;
    private static final Pattern FILE_NAME = Pattern.compile("r(\\d+)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.docx");

    private final RewriteResumeLoader loader;
    private final RewriteAiClient aiClient;
    private final RewriteAiProperties properties;
    private final ResumeDocxTailorService docxService;
    private final StoragePathResolver storage;
    private final ObjectMapper objectMapper;

    public FreeResumeRewriteService(RewriteResumeLoader loader, RewriteAiClient aiClient,
                                    RewriteAiProperties properties, ResumeDocxTailorService docxService,
                                    StoragePathResolver storage, ObjectMapper objectMapper) {
        this.loader = loader;
        this.aiClient = aiClient;
        this.properties = properties;
        this.docxService = docxService;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public FreeRewriteResponse rewrite(String email, Long resumeId) {
        if (!aiClient.isConfigured()) {
            throw RewriteAiException.notConfigured();
        }
        RewriteResumeLoader.LoadedResume loaded = loader.load(email, resumeId);
        RewritePii pii = RewritePii.forNames(loaded.knownNames());
        List<RewriteNodes.Editable> editable = RewriteNodes.editable(loaded.nodes(), pii);
        if (editable.size() > MAX_NODES) {
            editable = editable.subList(0, MAX_NODES);
        }
        Map<String, RewriteNodes.Editable> byId = new LinkedHashMap<>();
        editable.forEach(node -> byId.put(node.nodeId(), node));

        List<Candidate> accepted = List.of();
        int rejected = 0;
        if (!editable.isEmpty()) {
            String input = userPrompt(editable);
            RewriteAttempts.Outcome<Candidate> outcome = RewriteAttempts.run(aiClient,
                    feedback -> new RewriteAiRequest(properties.freeModel(), SYSTEM_PROMPT,
                            feedback == null ? input : input + "\n\n" + feedback,
                            "free_resume_rewrite", RewriteSchemas.freeChanges(), 0.2, "low", 6000),
                    new FreeStep(byId));
            accepted = outcome.accepted();
            rejected = outcome.rejected().size();
        }

        List<TailorChange> changes = new ArrayList<>();
        List<RewriteChangeView> views = new ArrayList<>();
        for (Candidate candidate : accepted) {
            RewriteNodes.Editable node = byId.get(candidate.nodeId());
            String restored = pii.restore(candidate.revisedText());
            TailorChange change = new TailorChange();
            change.setNodeId(node.nodeId());
            change.setOriginalText(node.originalText());
            change.setNewText(restored);
            change.setReason(candidate.reason());
            change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
            change.setApplied(true);
            changes.add(change);
            views.add(new RewriteChangeView(node.nodeId(), node.originalText(), restored, candidate.reason()));
        }

        Path output = storage.rewriteDirectory(loaded.user().getId())
                .resolve("r" + loaded.resume().getId() + "-" + UUID.randomUUID() + ".docx");
        Path written = docxService.createTailoredDocx(loaded.docxPath(), changes, output);
        String fileName = written.getFileName().toString();
        return new FreeRewriteResponse(
                loaded.resume().getId(),
                properties.freeModel(),
                views,
                views.size(),
                rejected,
                views.isEmpty(),
                "/api/v1/resume-rewrites/free/files/" + fileName,
                RewriteFiles.downloadName(loaded.resume(), "rewrite"));
    }

    /** A generated free rewrite owned by this user, and the readable name to save it under. */
    public record DownloadFile(Path path, String downloadName) {
    }

    public DownloadFile download(String email, String fileName) {
        User user = loader.user(email);
        Matcher matcher = FILE_NAME.matcher(fileName == null ? "" : fileName);
        if (!matcher.matches()) {
            throw new TailoredFileNotFoundException("Rewritten file not found");
        }
        Path directory = storage.rewriteDirectory(user.getId()).toAbsolutePath().normalize();
        Path file = directory.resolve(fileName).normalize();
        if (!directory.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new TailoredFileNotFoundException("Rewritten file not found");
        }
        Resume resume;
        try {
            resume = loader.ownedResume(Long.valueOf(matcher.group(1)), user.getId());
        } catch (ResumeNotFoundException e) {
            resume = null;
        }
        return new DownloadFile(file, RewriteFiles.downloadName(resume, "rewrite"));
    }

    private String userPrompt(List<RewriteNodes.Editable> editable) {
        List<Map<String, String>> nodes = new ArrayList<>();
        for (RewriteNodes.Editable node : editable) {
            nodes.add(Map.of("nodeId", node.nodeId(), "text", node.redactedText()));
        }
        try {
            return "Resume paragraphs (JSON):\n" + objectMapper.writeValueAsString(Map.of("nodes", nodes));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    record Candidate(String nodeId, String revisedText, String reason) {
    }

    private record FreeStep(Map<String, RewriteNodes.Editable> byId) implements RewriteAttempts.Step<Candidate> {

        @Override
        public List<Candidate> parse(JsonNode json) {
            JsonNode changes = json.get("changes");
            if (changes == null || !changes.isArray()) {
                throw RewriteAiException.invalidOutput("missing changes array");
            }
            List<Candidate> parsed = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonNode change : changes) {
                String nodeId = change.path("nodeId").asText("");
                String revised = change.path("revisedText").asText("");
                RewriteNodes.Editable node = byId.get(nodeId);
                // Unchanged text is not a change; a repeated node keeps the first answer.
                if (node != null && revised.trim().equals(node.redactedText().trim())) {
                    continue;
                }
                if (!seen.add(nodeId)) {
                    continue;
                }
                parsed.add(new Candidate(nodeId, revised, change.path("reason").asText("")));
            }
            return parsed;
        }

        @Override
        public List<String> check(Candidate candidate) {
            RewriteNodes.Editable node = byId.get(candidate.nodeId());
            if (node == null) {
                return List.of("unknown nodeId");
            }
            // Free: nothing may come from outside the paragraph itself.
            return RewriteFactGuard.check(node.redactedText(), candidate.revisedText(), "", List.of());
        }

        @Override
        public String label(Candidate candidate) {
            return candidate.nodeId();
        }
    }
}
