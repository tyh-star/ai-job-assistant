package aijob.rewrite;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.entity.Job;
import aijob.entity.TailorProposal;
import aijob.entity.User;
import aijob.exception.InvalidTailorProposalException;
import aijob.exception.JobNotFoundException;
import aijob.exception.ResumeTextNotAvailableException;
import aijob.exception.TailorProposalConflictException;
import aijob.exception.TailorProposalNotFoundException;
import aijob.exception.TailoredFileNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.TailorProposalMapper;
import aijob.rewrite.ai.RewriteAiClient;
import aijob.rewrite.ai.RewriteAiException;
import aijob.rewrite.ai.RewriteAiProperties;
import aijob.rewrite.ai.RewriteAiRequest;
import aijob.rewrite.dto.ProRewriteRequests;
import aijob.rewrite.dto.ProRewriteSessionResponse;
import aijob.service.ResumeDocxTailorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pro Resume Rewrite: a resume and a target job, in explicit steps with stored state.
 *
 * <pre>
 * start    ANALYZED   1 job requirements (120B, structured)  2 evidence status per requirement +
 *                     at most 5 questions (120B)  -- VERIFIED / INFERRED / UNSUPPORTED
 * answers  ANSWERED   3 the user's answers; a confirmed answer turns a requirement USER_CONFIRMED
 * preview  PREVIEWED  4 suggestions to review (120B), fact-checked; no file
 * confirm  COMPLETED  5 the user's accepted suggestions  6 final rewrite (120B) of those only,
 *                     fact-checked again, then the DOCX is written
 * </pre>
 *
 * UNSUPPORTED requirements and unconfirmed INFERRED ones are never usable: terms from
 * them are rejected by the fact guard at every step, so they cannot reach the file.
 */
@Service
public class ProResumeRewriteService {

    public static final String MODE = "PRO_REWRITE";
    public static final String ANALYZED = "ANALYZED";
    public static final String ANSWERED = "ANSWERED";
    public static final String PREVIEWED = "PREVIEWED";
    public static final String COMPLETED = "COMPLETED";

    static final int MAX_QUESTIONS = 5;
    private static final int TTL_HOURS = 24;
    private static final int MAX_NODES = 60;

    static final String REQUIREMENTS_PROMPT = """
            Extract the job's requirements into structured lists. Use short phrases taken from the \
            posting; never invent requirements. requiredSkills: must-have skills and tools. \
            preferredSkills: nice-to-have ones. responsibilities: main duties. keywords: other \
            important terms (domains, certifications, methods). experienceLevel: e.g. Entry, Mid, \
            Senior, or an empty string if the posting does not say. At most 10 items per list.
            """;

    static final String ASSESSMENT_PROMPT = """
            Compare each job requirement with the resume paragraphs (each has a nodeId).
            For every requirement id return exactly one status:
            - VERIFIED: the resume clearly shows it; list the nodeIds that show it.
            - INFERRED: plausible from related resume content but not stated; list the related \
            nodeIds; the candidate must confirm it.
            - UNSUPPORTED: nothing in the resume supports it.
            Then write at most 5 questions that would most improve the tailored resume, only about \
            INFERRED or important UNSUPPORTED requirements. Each question must be answerable by the \
            candidate in one or two sentences about their real experience. Never assume the answer. \
            Placeholders such as [[PII_1]] are personal details; ignore them.
            """;

    static final String SUGGESTIONS_PROMPT = """
            You tailor resume paragraphs to a job. Use only facts from the resume and from the \
            candidate's confirmed answers. You may emphasise, reorder and reword. Mention a skill, \
            tool, number or keyword only if it appears in the resume or in a confirmed answer. Never \
            use the forbidden terms. Keep every number, date, name, company, school, job title and \
            placeholder such as [[PII_1]] exactly. Write in the paragraph's language. Return only \
            paragraphs you improve; originalText must be the exact input text; requirementIds may \
            only contain ids from the usable list; reason is one short sentence.
            """;

    static final String FINAL_PROMPT = """
            These are rewrites the candidate has accepted. Polish them for consistent tense, verbs \
            and style across the resume. Do not add any new fact, skill, tool, number, metric, \
            responsibility or term, and do not remove any. Keep placeholders such as [[PII_1]] \
            exactly. Return one finalText for every nodeId given.
            """;

    private final RewriteResumeLoader loader;
    private final JobMapper jobMapper;
    private final TailorProposalMapper sessionMapper;
    private final RewriteAiClient aiClient;
    private final RewriteAiProperties properties;
    private final ResumeDocxTailorService docxService;
    private final StoragePathResolver storage;
    private final ObjectMapper objectMapper;

    public ProResumeRewriteService(RewriteResumeLoader loader, JobMapper jobMapper,
                                   TailorProposalMapper sessionMapper, RewriteAiClient aiClient,
                                   RewriteAiProperties properties, ResumeDocxTailorService docxService,
                                   StoragePathResolver storage, ObjectMapper objectMapper) {
        this.loader = loader;
        this.jobMapper = jobMapper;
        this.sessionMapper = sessionMapper;
        this.aiClient = aiClient;
        this.properties = properties;
        this.docxService = docxService;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- step 1-2

    public ProRewriteSessionResponse start(String email, ProRewriteRequests.Start request) {
        if (!aiClient.isConfigured()) {
            throw RewriteAiException.notConfigured();
        }
        RewriteResumeLoader.LoadedResume loaded = loader.load(email, request.resumeId());
        Job job = ownedJob(request.jobId(), loaded.user().getId());
        String description = job.getDescription();
        if (description == null || description.isBlank()) {
            throw new ResumeTextNotAvailableException("Job description is not available for matching");
        }
        RewritePii pii = RewritePii.forNames(loaded.knownNames());

        ProRewriteState state = new ProRewriteState();
        state.resumeId = loaded.resume().getId();
        state.jobId = job.getId();
        state.model = properties.proModel();
        state.requirements = analyzeRequirements(job);
        state.items = requirementItems(state.requirements);
        assessEvidence(state, loaded.nodes(), pii);

        TailorProposal session = new TailorProposal();
        session.setUserId(loaded.user().getId());
        session.setResumeId(state.resumeId);
        session.setJobId(state.jobId);
        session.setMode(MODE);
        session.setStatus(ANALYZED);
        session.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(TTL_HOURS));
        session.setChangesJson(write(state));
        session.setRejectedJson("[]");
        sessionMapper.insert(session);
        return toResponse(session, state);
    }

    private ProRewriteState.Requirements analyzeRequirements(Job job) {
        String input = "Job title: " + nullToEmpty(job.getTitle()) + "\nCompany: " + nullToEmpty(job.getCompany())
                + "\nJob description:\n" + job.getDescription();
        JsonNode json = callWithOneRetry(new RewriteAiRequest(properties.proModel(), REQUIREMENTS_PROMPT, input,
                "job_requirements", RewriteSchemas.jobRequirements(), 0.1, "medium", 3000));
        ProRewriteState.Requirements requirements = new ProRewriteState.Requirements();
        requirements.requiredSkills = limited(RewriteSchemas.texts(json.get("requiredSkills")), 10);
        requirements.preferredSkills = limited(RewriteSchemas.texts(json.get("preferredSkills")), 8);
        requirements.responsibilities = limited(RewriteSchemas.texts(json.get("responsibilities")), 8);
        requirements.keywords = limited(RewriteSchemas.texts(json.get("keywords")), 10);
        requirements.experienceLevel = json.path("experienceLevel").asText("").trim();
        return requirements;
    }

    static List<ProRewriteState.Item> requirementItems(ProRewriteState.Requirements requirements) {
        List<ProRewriteState.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addItems(items, seen, "REQUIRED_SKILL", requirements.requiredSkills);
        addItems(items, seen, "PREFERRED_SKILL", requirements.preferredSkills);
        addItems(items, seen, "RESPONSIBILITY", requirements.responsibilities);
        addItems(items, seen, "KEYWORD", requirements.keywords);
        return items;
    }

    private static void addItems(List<ProRewriteState.Item> items, Set<String> seen, String category, List<String> texts) {
        for (String text : texts) {
            if (seen.add(text.toLowerCase(Locale.ROOT))) {
                ProRewriteState.Item item = new ProRewriteState.Item();
                item.id = "R" + (items.size() + 1);
                item.category = category;
                item.text = text;
                item.status = ProRewriteState.UNSUPPORTED;
                items.add(item);
            }
        }
    }

    void assessEvidence(ProRewriteState state, List<ResumeDocumentNode> nodes, RewritePii pii) {
        if (state.items.isEmpty()) {
            return;
        }
        Map<String, String> nodeText = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            if (node.getText() != null && !node.getText().isBlank()) {
                nodeText.put(node.getNodeId(), pii.redact(node.getText()));
            }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requirements", state.items.stream().map(i -> Map.of("id", i.id, "category", i.category, "text", i.text)).toList());
        input.put("resume", nodeText.entrySet().stream().map(e -> Map.of("nodeId", e.getKey(), "text", e.getValue())).toList());
        JsonNode json = callWithOneRetry(new RewriteAiRequest(properties.proModel(), ASSESSMENT_PROMPT,
                "Input (JSON):\n" + write(input), "requirement_assessment", RewriteSchemas.assessment(), 0.1, "medium", 6000));

        Map<String, ProRewriteState.Item> byId = new HashMap<>();
        state.items.forEach(item -> byId.put(item.id, item));
        for (JsonNode assessment : json.path("assessments")) {
            ProRewriteState.Item item = byId.get(assessment.path("requirementId").asText(""));
            if (item == null) {
                continue;
            }
            List<String> cited = new ArrayList<>();
            for (String nodeId : RewriteSchemas.texts(assessment.get("evidenceNodeIds"))) {
                if (nodeText.containsKey(nodeId) && !cited.contains(nodeId)) {
                    cited.add(nodeId);
                }
            }
            String status = assessment.path("status").asText(ProRewriteState.UNSUPPORTED);
            // VERIFIED must point at real resume text; otherwise it still needs confirming.
            if (ProRewriteState.VERIFIED.equals(status) && cited.isEmpty()) {
                status = ProRewriteState.INFERRED;
            }
            if (!ProRewriteState.VERIFIED.equals(status) && !ProRewriteState.INFERRED.equals(status)) {
                status = ProRewriteState.UNSUPPORTED;
            }
            item.status = status;
            item.evidenceNodeIds = cited;
            item.note = assessment.path("note").asText("");
        }
        // A skill or keyword that literally appears in the resume is VERIFIED regardless of the model.
        for (ProRewriteState.Item item : state.items) {
            if (!item.termLike() || ProRewriteState.VERIFIED.equals(item.status)) {
                continue;
            }
            List<String> literal = new ArrayList<>();
            nodeText.forEach((nodeId, text) -> {
                if (containsWord(text.toLowerCase(Locale.ROOT), item.text.toLowerCase(Locale.ROOT))) {
                    literal.add(nodeId);
                }
            });
            if (!literal.isEmpty()) {
                item.status = ProRewriteState.VERIFIED;
                item.evidenceNodeIds = literal;
            }
        }

        Set<String> asked = new HashSet<>();
        for (JsonNode question : json.path("questions")) {
            if (state.questions.size() >= MAX_QUESTIONS) {
                break;
            }
            ProRewriteState.Item item = byId.get(question.path("requirementId").asText(""));
            String text = question.path("question").asText("").trim();
            if (item == null || text.isEmpty() || item.usable() || !asked.add(item.id)) {
                continue;
            }
            ProRewriteState.Question q = new ProRewriteState.Question();
            q.id = "Q" + (state.questions.size() + 1);
            q.requirementId = item.id;
            q.question = text;
            q.reason = question.path("reason").asText("");
            state.questions.add(q);
        }
    }

    // ---------------------------------------------------------------- step 3

    public ProRewriteSessionResponse answer(String email, Long sessionId, ProRewriteRequests.Answers request) {
        User user = loader.user(email);
        TailorProposal session = ownedSession(sessionId, user.getId());
        requireStatus(session, ANALYZED);
        ProRewriteState state = read(session);
        Map<String, ProRewriteState.Question> questions = new HashMap<>();
        state.questions.forEach(q -> questions.put(q.id, q));
        Map<String, ProRewriteState.Item> items = new HashMap<>();
        state.items.forEach(i -> items.put(i.id, i));
        for (ProRewriteRequests.Answer answer : request.answers()) {
            ProRewriteState.Question question = questions.get(answer.questionId());
            if (question == null) {
                throw new InvalidTailorProposalException("Unknown question: " + answer.questionId());
            }
            String detail = answer.detail() == null ? "" : answer.detail().trim();
            boolean confirmed = answer.hasExperience() && detail.length() >= 5;
            question.hasExperience = confirmed;
            question.detail = confirmed ? detail : null;
            ProRewriteState.Item item = items.get(question.requirementId);
            if (confirmed && item != null && !ProRewriteState.VERIFIED.equals(item.status)) {
                item.status = ProRewriteState.USER_CONFIRMED;
            }
        }
        return move(session, user.getId(), ANALYZED, ANSWERED, state, null, null);
    }

    // ---------------------------------------------------------------- step 4

    public ProRewriteSessionResponse preview(String email, Long sessionId) {
        User user = loader.user(email);
        TailorProposal session = ownedSession(sessionId, user.getId());
        ProRewriteState state = read(session);
        boolean unanswered = ANALYZED.equals(session.getStatus()) && !state.questions.isEmpty();
        if (unanswered || !(ANALYZED.equals(session.getStatus()) || ANSWERED.equals(session.getStatus()))) {
            throw new TailorProposalConflictException("Resume rewrite session is not ready for suggestions");
        }
        RewriteResumeLoader.LoadedResume loaded = loader.load(email, state.resumeId);
        RewritePii pii = RewritePii.forNames(loaded.knownNames());
        List<RewriteNodes.Editable> editable = RewriteNodes.editable(loaded.nodes(), pii);
        if (editable.size() > MAX_NODES) {
            editable = editable.subList(0, MAX_NODES);
        }
        Map<String, RewriteNodes.Editable> byId = new LinkedHashMap<>();
        editable.forEach(node -> byId.put(node.nodeId(), node));
        Evidence evidence = evidence(state, loaded.nodes(), pii);

        List<ProRewriteState.Suggestion> accepted = new ArrayList<>();
        int rejected = 0;
        if (!editable.isEmpty()) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("usableRequirements", state.items.stream().filter(ProRewriteState.Item::usable)
                    .map(i -> Map.of("id", i.id, "text", i.text, "status", i.status)).toList());
            input.put("forbiddenTerms", evidence.forbidden());
            input.put("confirmedAnswers", evidence.answers());
            input.put("paragraphs", editable.stream().map(n -> Map.of("nodeId", n.nodeId(), "text", n.redactedText())).toList());
            String prompt = "Input (JSON):\n" + write(input);
            RewriteAttempts.Outcome<ProRewriteState.Suggestion> outcome = RewriteAttempts.run(aiClient,
                    feedback -> new RewriteAiRequest(properties.proModel(), SUGGESTIONS_PROMPT,
                            feedback == null ? prompt : prompt + "\n\n" + feedback,
                            "pro_rewrite_suggestions", RewriteSchemas.suggestions(), 0.2, "medium", 8000),
                    new SuggestionStep(byId, state, evidence));
            accepted = outcome.accepted();
            rejected = outcome.rejected().size();
        }
        Map<String, ProRewriteState.Item> items = new HashMap<>();
        state.items.forEach(i -> items.put(i.id, i));
        state.suggestions = new ArrayList<>();
        for (ProRewriteState.Suggestion suggestion : accepted) {
            RewriteNodes.Editable node = byId.get(suggestion.nodeId);
            suggestion.id = "S" + (state.suggestions.size() + 1);
            suggestion.originalText = node.originalText();
            suggestion.revisedText = pii.restore(suggestion.revisedText);
            boolean usesAnswer = suggestion.requirementIds.stream()
                    .map(items::get).anyMatch(i -> i != null && ProRewriteState.USER_CONFIRMED.equals(i.status));
            suggestion.evidenceStatus = usesAnswer ? ProRewriteState.USER_CONFIRMED : ProRewriteState.VERIFIED;
            state.suggestions.add(suggestion);
        }
        state.rejectedSuggestionCount = rejected;
        return move(session, user.getId(), session.getStatus(), PREVIEWED, state, null, null);
    }

    // ---------------------------------------------------------------- step 5-6

    public ProRewriteSessionResponse confirm(String email, Long sessionId, ProRewriteRequests.Confirm request) {
        User user = loader.user(email);
        TailorProposal session = ownedSession(sessionId, user.getId());
        if (COMPLETED.equals(session.getStatus())) {
            return toResponse(session, read(session));
        }
        requireStatus(session, PREVIEWED);
        ProRewriteState state = read(session);
        Map<String, ProRewriteState.Suggestion> suggestions = new LinkedHashMap<>();
        state.suggestions.forEach(s -> suggestions.put(s.id, s));
        List<ProRewriteState.Suggestion> acceptedList = new ArrayList<>();
        for (String id : new LinkedHashSet<>(request.acceptedSuggestionIds())) {
            ProRewriteState.Suggestion suggestion = suggestions.get(id);
            if (suggestion == null) {
                throw new InvalidTailorProposalException("Unknown suggestion: " + id);
            }
            acceptedList.add(suggestion);
        }

        RewriteResumeLoader.LoadedResume loaded = loader.load(email, state.resumeId);
        RewritePii pii = RewritePii.forNames(loaded.knownNames());
        Evidence evidence = evidence(state, loaded.nodes(), pii);
        Map<String, String> currentText = new HashMap<>();
        loaded.nodes().forEach(node -> currentText.put(node.getNodeId(), node.getText()));
        for (ProRewriteState.Suggestion suggestion : acceptedList) {
            if (!suggestion.originalText.equals(currentText.get(suggestion.nodeId))) {
                throw new TailorProposalConflictException("The resume changed after these suggestions were made");
            }
        }

        Map<String, String> finalTexts = finalRewrite(acceptedList, pii, evidence);
        List<TailorChange> changes = new ArrayList<>();
        state.finalChanges = new ArrayList<>();
        for (ProRewriteState.Suggestion suggestion : acceptedList) {
            String finalText = finalTexts.getOrDefault(suggestion.nodeId, suggestion.revisedText);
            TailorChange change = new TailorChange();
            change.setNodeId(suggestion.nodeId);
            change.setOriginalText(suggestion.originalText);
            change.setNewText(finalText);
            change.setReason(suggestion.reason);
            change.setEvidenceType(ProRewriteState.USER_CONFIRMED.equals(suggestion.evidenceStatus)
                    ? TailorChange.EVIDENCE_USER_EDITED : TailorChange.EVIDENCE_VERIFIED);
            change.setApplied(true);
            changes.add(change);
            ProRewriteState.FinalChange applied = new ProRewriteState.FinalChange();
            applied.suggestionId = suggestion.id;
            applied.nodeId = suggestion.nodeId;
            applied.originalText = suggestion.originalText;
            applied.finalText = finalText;
            state.finalChanges.add(applied);
        }

        Path output = storage.rewriteDirectory(user.getId())
                .resolve("s" + session.getId() + "-" + UUID.randomUUID() + ".docx");
        Path written = docxService.createTailoredDocx(loaded.docxPath(), changes, output);
        state.downloadFileName = RewriteFiles.downloadName(loaded.resume(), "pro-rewrite");
        return move(session, user.getId(), PREVIEWED, COMPLETED, state,
                written.getFileName().toString(), written.toString());
    }

    /**
     * The final 120B pass over the accepted suggestions. Each result is fact-checked
     * against the original paragraph again; anything that fails keeps the accepted
     * (already checked) suggestion text. An unusable answer keeps all of them.
     */
    private Map<String, String> finalRewrite(List<ProRewriteState.Suggestion> accepted, RewritePii pii, Evidence evidence) {
        Map<String, String> result = new HashMap<>();
        if (accepted.isEmpty()) {
            return result;
        }
        Map<String, ProRewriteState.Suggestion> byNode = new LinkedHashMap<>();
        accepted.forEach(s -> byNode.put(s.nodeId, s));
        List<Map<String, String>> input = new ArrayList<>();
        for (ProRewriteState.Suggestion s : accepted) {
            input.add(Map.of("nodeId", s.nodeId, "acceptedText", pii.redact(s.revisedText)));
        }
        JsonNode json;
        try {
            json = callWithOneRetry(new RewriteAiRequest(properties.proModel(), FINAL_PROMPT,
                    "Accepted rewrites (JSON):\n" + write(Map.of("rewrites", input)),
                    "pro_rewrite_final", RewriteSchemas.finalTexts(), 0.1, "medium", 6000));
        } catch (RewriteAiException e) {
            if (RewriteAiException.INVALID_OUTPUT.equals(e.code())) {
                return result;
            }
            throw e;
        }
        for (JsonNode item : json.path("finalTexts")) {
            ProRewriteState.Suggestion suggestion = byNode.get(item.path("nodeId").asText(""));
            String finalText = item.path("finalText").asText("");
            if (suggestion == null || finalText.isBlank()) {
                continue;
            }
            List<String> violations = RewriteFactGuard.check(pii.redact(suggestion.originalText), finalText,
                    evidence.text(), evidence.forbidden());
            if (violations.isEmpty()) {
                result.put(suggestion.nodeId, pii.restore(finalText));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- read / download

    public ProRewriteSessionResponse get(String email, Long sessionId) {
        User user = loader.user(email);
        TailorProposal session = ownedSession(sessionId, user.getId());
        return toResponse(session, read(session));
    }

    public FreeResumeRewriteService.DownloadFile download(String email, Long sessionId) {
        User user = loader.user(email);
        TailorProposal session = ownedSession(sessionId, user.getId());
        if (!COMPLETED.equals(session.getStatus()) || session.getGeneratedFilePath() == null) {
            throw new TailoredFileNotFoundException("Rewritten file not found");
        }
        Path directory = storage.rewriteDirectory(user.getId()).toAbsolutePath().normalize();
        Path file = Path.of(session.getGeneratedFilePath()).toAbsolutePath().normalize();
        if (!directory.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new TailoredFileNotFoundException("Rewritten file not found");
        }
        return new FreeResumeRewriteService.DownloadFile(file, read(session).downloadFileName);
    }

    // ---------------------------------------------------------------- helpers

    record Evidence(String text, List<String> forbidden, List<Map<String, String>> answers) {
    }

    /**
     * What a new fact may come from: the whole resume plus the user's confirmed answers
     * (and the confirmed requirement itself). Forbidden: every term-like requirement that
     * is not usable (UNSUPPORTED, or INFERRED without a confirmation).
     */
    static Evidence evidence(ProRewriteState state, List<ResumeDocumentNode> nodes, RewritePii pii) {
        StringBuilder text = new StringBuilder(RewriteNodes.allText(nodes, pii));
        List<Map<String, String>> answers = new ArrayList<>();
        Map<String, ProRewriteState.Item> items = new HashMap<>();
        state.items.forEach(i -> items.put(i.id, i));
        for (ProRewriteState.Question q : state.questions) {
            if (Boolean.TRUE.equals(q.hasExperience) && q.detail != null) {
                ProRewriteState.Item item = items.get(q.requirementId);
                String detail = pii.redact(q.detail);
                text.append(detail).append('\n');
                if (item != null) {
                    text.append(item.text).append('\n');
                }
                answers.add(Map.of("requirementId", q.requirementId, "answer", detail));
            }
        }
        List<String> forbidden = new ArrayList<>();
        for (ProRewriteState.Item item : state.items) {
            if (!item.usable() && item.termLike()) {
                forbidden.add(item.text);
            }
        }
        return new Evidence(text.toString(), forbidden, answers);
    }

    private record SuggestionStep(Map<String, RewriteNodes.Editable> byId, ProRewriteState state, Evidence evidence)
            implements RewriteAttempts.Step<ProRewriteState.Suggestion> {

        @Override
        public List<ProRewriteState.Suggestion> parse(JsonNode json) {
            JsonNode array = json.get("suggestions");
            if (array == null || !array.isArray()) {
                throw RewriteAiException.invalidOutput("missing suggestions array");
            }
            List<ProRewriteState.Suggestion> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : array) {
                String nodeId = item.path("nodeId").asText("");
                RewriteNodes.Editable node = byId.get(nodeId);
                String revised = item.path("revisedText").asText("");
                if ((node != null && revised.trim().equals(node.redactedText().trim())) || !seen.add(nodeId)) {
                    continue;
                }
                ProRewriteState.Suggestion suggestion = new ProRewriteState.Suggestion();
                suggestion.nodeId = nodeId;
                suggestion.revisedText = revised;
                suggestion.reason = item.path("reason").asText("");
                suggestion.requirementIds = RewriteSchemas.texts(item.get("requirementIds"));
                parsed.add(suggestion);
            }
            return parsed;
        }

        @Override
        public List<String> check(ProRewriteState.Suggestion suggestion) {
            RewriteNodes.Editable node = byId.get(suggestion.nodeId);
            if (node == null) {
                return List.of("unknown nodeId");
            }
            List<String> violations = new ArrayList<>(RewriteFactGuard.check(node.redactedText(),
                    suggestion.revisedText, evidence.text(), evidence.forbidden()));
            Map<String, ProRewriteState.Item> items = new HashMap<>();
            state.items.forEach(i -> items.put(i.id, i));
            for (String id : suggestion.requirementIds) {
                ProRewriteState.Item item = items.get(id);
                if (item == null || !item.usable()) {
                    violations.add("relies on a requirement without evidence: " + id);
                }
            }
            return violations;
        }

        @Override
        public String label(ProRewriteState.Suggestion suggestion) {
            return suggestion.nodeId;
        }
    }

    private JsonNode callWithOneRetry(RewriteAiRequest request) {
        try {
            return aiClient.completeJson(request);
        } catch (RewriteAiException e) {
            if (!RewriteAiException.INVALID_OUTPUT.equals(e.code())) {
                throw e;
            }
            return aiClient.completeJson(new RewriteAiRequest(request.model(), request.systemPrompt(),
                    request.userPrompt() + "\n\nYour previous answer was not valid JSON for the schema. Return only the JSON object.",
                    request.schemaName(), request.schema(), request.temperature(), request.reasoningEffort(),
                    request.maxOutputTokens()));
        }
    }

    private Job ownedJob(Long jobId, Long userId) {
        Job job = jobId == null ? null : jobMapper.findById(jobId);
        if (job == null || !userId.equals(job.getUserId())) {
            throw new JobNotFoundException("Job not found");
        }
        return job;
    }

    private TailorProposal ownedSession(Long sessionId, Long userId) {
        TailorProposal session = sessionId == null ? null : sessionMapper.findById(sessionId);
        if (session == null || !userId.equals(session.getUserId()) || !MODE.equals(session.getMode())) {
            throw new TailorProposalNotFoundException("Resume rewrite session not found");
        }
        return session;
    }

    private static void requireStatus(TailorProposal session, String expected) {
        if (!COMPLETED.equals(session.getStatus()) && isExpired(session)) {
            throw new TailorProposalConflictException("Resume rewrite session expired");
        }
        if (!expected.equals(session.getStatus())) {
            throw new TailorProposalConflictException(
                    "Resume rewrite session is " + session.getStatus() + ", expected " + expected);
        }
    }

    private static boolean isExpired(TailorProposal session) {
        return session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    private ProRewriteSessionResponse move(TailorProposal session, Long userId, String from, String to,
                                           ProRewriteState state, String fileName, String filePath) {
        if (!COMPLETED.equals(from) && isExpired(session)) {
            throw new TailorProposalConflictException("Resume rewrite session expired");
        }
        String json = write(state);
        int updated = sessionMapper.updateRewriteSession(session.getId(), userId, from, to, json, fileName, filePath);
        if (updated == 0) {
            throw new TailorProposalConflictException("Resume rewrite session was changed by another request");
        }
        session.setStatus(to);
        session.setChangesJson(json);
        session.setGeneratedFileName(fileName);
        session.setGeneratedFilePath(filePath);
        return toResponse(session, state);
    }

    private ProRewriteSessionResponse toResponse(TailorProposal session, ProRewriteState state) {
        boolean done = COMPLETED.equals(session.getStatus()) && session.getGeneratedFileName() != null;
        return new ProRewriteSessionResponse(
                session.getId(), session.getStatus(), state.resumeId, state.jobId, state.model,
                session.getExpiresAt(), state.requirements, state.items, state.questions, state.suggestions,
                state.rejectedSuggestionCount, state.finalChanges,
                done ? "/api/v1/resume-rewrites/pro/sessions/" + session.getId() + "/file" : null,
                done ? state.downloadFileName : null);
    }

    private ProRewriteState read(TailorProposal session) {
        try {
            return objectMapper.readValue(session.getChangesJson(), ProRewriteState.class);
        } catch (JsonProcessingException e) {
            throw new TailorProposalNotFoundException("Resume rewrite session not found");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> limited(List<String> values, int max) {
        return values.size() <= max ? values : new ArrayList<>(values.subList(0, max));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsWord(String haystackLower, String needleLower) {
        if (needleLower.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(needleLower)
                + "(?![\\p{L}\\p{N}])").matcher(haystackLower).find();
    }
}
