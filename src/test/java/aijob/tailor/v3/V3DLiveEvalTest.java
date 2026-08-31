package aijob.tailor.v3;

import aijob.client.GreenhouseJobClient;
import aijob.config.JobSearchProperties;
import aijob.config.StoragePathResolver;
import aijob.config.TailorEngineProperties;
import aijob.dto.AnswerEnhancedQuestionRequest;
import aijob.dto.AnswerStatus;
import aijob.dto.ConfirmTailorProposalRequest;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.dto.TailorChange;
import aijob.dto.TailoredResumeResponse;
import aijob.dto.V3EntryReview;
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
import aijob.service.ResumeDocxTailorService;
import aijob.service.ResumeEvidenceService;
import aijob.service.impl.JobServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.YaohangCanonicalDocxFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * V3-3D live ENHANCED eval. Two OpenAI cases max. Harness only.
 * Enable with {@code AIJOB_V3_3D_LIVE=true}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V3DLiveEvalTest {

    private static final String EMAIL = "v3d-live@example.com";
    private static final String CONFIRMED_USED_TEXT =
            "After deployment, updated application dependencies weekly and fixed authentication defects in the AWS environment.";
    private static final String MODEL = "gpt-4o";

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
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "AIJOB_V3_3D_LIVE", matches = "true")
    void caseAThenCaseBWriteReportsWithoutTouchingUploads() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.toLowerCase(Locale.ROOT).contains("placeholder"), "placeholder key");
        assumeTrue(MODEL.equals(System.getenv().getOrDefault("OPENAI_MODEL", MODEL)), "frozen model is gpt-4o");

        Path reportDir = Path.of("docs", "v3-3d-live");
        Files.createDirectories(reportDir);
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.1-live")));
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.2-smoke")));
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.3-live-9")));
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.4-live-9")));
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-3c-verify")));

        Path uploadsRoot = Path.of("uploads").toAbsolutePath().normalize();
        Map<String, String> uploadsBefore = snapshotUploads(uploadsRoot);
        Map<String, String> sourceBefore = snapshotV3Sources();
        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();

        Map<String, Object> caseA;
        Path caseAReport = reportDir.resolve("case-a").resolve("technical.json");
        if (Files.isRegularFile(caseAReport)) {
            caseA = new ObjectMapper().readValue(caseAReport.toFile(), Map.class);
            caseA.put("reusedExistingReport", true);
        } else {
            caseA = runCaseA(client, reportDir, uploadsRoot, uploadsBefore);
        }
        Map<String, Object> caseB = runCaseB(client, reportDir, uploadsRoot, uploadsBefore);

        Map<String, String> uploadsAfter = snapshotUploads(uploadsRoot);
        Map<String, String> sourceAfter = snapshotV3Sources();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordedAtUtc", Instant.now().toString());
        summary.put("model", MODEL);
        summary.put("basicPromptVersion", V3GenerationTelemetry.PROMPT_VERSION);
        summary.put("enhancedPromptVersion", V3GenerationTelemetry.ENHANCED_PROMPT_VERSION);
        summary.put("caseA", caseA.get("verdict"));
        summary.put("caseB", caseB.get("verdict"));
        summary.put("caseAFailures", caseA.get("failures"));
        summary.put("caseBFailures", caseB.get("failures"));
        summary.put("sourceShaUnchanged", sourceBefore.equals(sourceAfter));
        summary.put("uploadsUnchanged", uploadsBefore.equals(uploadsAfter));
        summary.put("uploadsCompare", compareUploads(uploadsBefore, uploadsAfter));
        boolean pass = Boolean.TRUE.equals(caseA.get("pass"))
                && Boolean.TRUE.equals(caseB.get("pass"))
                && sourceBefore.equals(sourceAfter)
                && uploadsBefore.equals(uploadsAfter);
        summary.put("technicalPass", pass);
        writeJson(reportDir.resolve("live-summary.json"), summary);

        assertEquals(sourceBefore, sourceAfter, "V3 production sources must stay frozen");
        assertEquals(uploadsBefore, uploadsAfter, "real uploads must stay frozen");
        assertEquals(true, caseA.get("pass"), String.valueOf(caseA.get("failures")));
        assertEquals(true, caseB.get("pass"), String.valueOf(caseB.get("failures")));
    }

    private Map<String, Object> runCaseA(
            com.openai.client.OpenAIClient client,
            Path reportDir,
            Path uploadsRoot,
            Map<String, String> uploadsBefore) throws Exception {
        Path caseDir = reportDir.resolve("case-a");
        Files.createDirectories(caseDir);
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, tempDir.resolve("case-a.docx"));
        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        List<ResumeDocumentNode> original = parser.parseDocx(canonical);
        CountingWriter writer = new CountingWriter(parser, tempDir.resolve("writer-a"));
        MemoryEvidence evidence = new MemoryEvidence(3L);
        RecordingEnhancedGenerator generator = new RecordingEnhancedGenerator(client);
        RecordingReviewer reviewer = new RecordingReviewer(client);
        TracingEnhancedService enhanced = new TracingEnhancedService(generator, reviewer);
        JobServiceImpl jobService = jobService(parser, writer, evidence, enhanced, 10L, 3L, canonical, "docx");
        stubPdfExport();

        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("case", "A");
        report.put("label", "Yaohang CONFIRMED_NOT_USED maintain");
        report.put("resume", "real yaohang-tang.pdf + canonical DOCX child");
        report.put("jd", "YAOHANG_JAVA_JD / JD_INTERN");
        report.put("productMode", "ENHANCED");

        int filesBefore = countGenerated(writer.root);
        assertEquals(0, writer.calls.get());
        V3OpportunityMap map = runWithRetry("case-a-scan", () ->
                jobService.scanEnhancedOpportunity(EMAIL, 10L, 3L, List.of()));
        report.put("opportunityMap", opportunityView(map));
        report.put("questions", questionView(map));

        List<Map<String, String>> answers = new ArrayList<>();
        for (V3EnhancedQuestion question : map.questions()) {
            AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
            request.setQuestionId(question.id());
            if ("maintain".equals(question.topic())) {
                request.setAnswerStatus(AnswerStatus.CONFIRMED_NOT_USED);
                jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);
                answers.add(Map.of(
                        "id", question.id(),
                        "topic", question.topic(),
                        "entryTitle", question.entryTitle(),
                        "promptZh", question.promptZh(),
                        "answerStatus", AnswerStatus.CONFIRMED_NOT_USED,
                        "answerText", ""));
            } else {
                request.setAnswerStatus(V3EnhancedAnswerBinder.SKIPPED);
                jobService.answerEnhancedQuestion(EMAIL, 10L, 3L, request);
                answers.add(Map.of(
                        "id", question.id(),
                        "topic", question.topic(),
                        "entryTitle", question.entryTitle(),
                        "promptZh", question.promptZh(),
                        "answerStatus", "SKIPPED",
                        "answerText", ""));
            }
        }
        report.put("answers", answers);
        report.put("writerBeforeProposal", writer.calls.get());
        report.put("generatedFilesBeforeProposal", countGenerated(writer.root));
        if (writer.calls.get() != 0) {
            failures.add("proposal 前 Writer=" + writer.calls.get());
        }
        if (countGenerated(writer.root) != filesBefore) {
            failures.add("proposal 前 generated files changed");
        }

        AtomicReference<TailorProposal> stored = bindProposalMapper();
        TailoredResumeResponse proposal = runWithRetry("case-a-proposal", () ->
                jobService.createTailorProposal(EMAIL, 10L, 3L, "ENHANCED"));
        report.put("proposalStatus", proposal.getStatus());
        report.put("proposalId", proposal.getProposalId());
        report.put("hasSuggestions", proposal.isHasSuggestions());
        report.put("entryReviews", proposal.getEntryReviews());
        report.put("writerAfterProposal", writer.calls.get());
        report.put("generatedFilesAfterProposal", countGenerated(writer.root));
        report.put("generateCalls", generator.generateCalls);
        report.put("repairCalls", generator.repairCalls);
        report.put("reviewerCalls", reviewer.calls);
        report.put("generatorTelemetry", generator.calls);
        report.put("reviewerTelemetry", reviewer.records);
        if (!TailorProposal.STATUS_PENDING.equals(proposal.getStatus())) {
            failures.add("status=" + proposal.getStatus());
        }
        if (writer.calls.get() != 0) {
            failures.add("proposal 后 Writer still " + writer.calls.get());
        }

        V3EnhancedRunResult result = enhanced.last;
        report.put("traces", tracesView(result == null ? List.of() : result.traces()));
        String aiText = suggestedText(proposal);
        if (containsMaintain(aiText)) {
            failures.add("USER_REJECTED maintain entered AI suggestion");
        }
        String invented = inventedTerms(aiText, originalText(original));
        if (!invented.isBlank()) {
            failures.add("invented terms in suggestion: " + invented);
        }
        if (builtApiUpgrade(original, aiText)) {
            failures.add("built APIs upgrade");
        }

        ClaimLedger ledger = ClaimLedger.from(original);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        report.put("evidenceFlow", evidenceFlow(evidence, original, List.of()));
        Map<String, Object> confirmPayload = new LinkedHashMap<>();
        boolean empty = proposal.getEntryReviews() == null || proposal.getEntryReviews().isEmpty();
        report.put("emptyProposalAllowed", empty);
        Path generated = null;
        List<ResumeDocumentNode> rewritten = original;
        if (empty) {
            report.put("confirmInvoked", false);
            report.put("writerAfterConfirm", writer.calls.get());
            report.put("verdictNote", "no qualifying suggestion; confirm not invoked; Writer remains 0");
        } else {
            List<String> selected = new ArrayList<>();
            for (V3EntryReview review : proposal.getEntryReviews()) {
                selected.add(review.getCanonicalEntryKey());
            }
            confirmPayload.put("selectedEntryKeys", selected);
            confirmPayload.put("editedEntries", List.of());
            confirmPayload.put("selectedNodeIds", "omitted");
            ConfirmTailorProposalRequest confirm = new ConfirmTailorProposalRequest();
            confirm.setSelectedEntryKeys(selected);
            when(tailorProposalMapper.findById(anyLong())).thenAnswer(inv -> stored.get());
            when(tailorProposalMapper.claimPending(anyLong(), eq(1L), eq(10L))).thenReturn(1);
            when(tailorProposalMapper.markConfirmed(anyLong(), any(), any())).thenReturn(1);
            TailoredResumeResponse confirmed = jobService.confirmTailorProposal(EMAIL, 10L, 42L, confirm);
            generated = writer.last;
            rewritten = parser.parseDocx(generated);
            report.put("confirmInvoked", true);
            report.put("confirmStatus", confirmed.getStatus());
            report.put("writerAfterConfirm", writer.calls.get());
            report.put("docxExists", generated != null && Files.isRegularFile(generated));
            Path pdfOut = generated == null ? null : generated.resolveSibling(
                    generated.getFileName().toString().replace(".docx", ".pdf"));
            report.put("pdfExists", pdfOut != null && Files.isRegularFile(pdfOut));
            if (writer.calls.get() != 1) {
                failures.add("confirm 后 Writer=" + writer.calls.get());
            }
            if (generated == null || !Files.isRegularFile(generated) || pdfOut == null || !Files.isRegularFile(pdfOut)) {
                failures.add("DOCX/PDF missing after confirm");
            }
            failures.addAll(structureFailures(original, rewritten, "A"));
            String finalText = originalText(rewritten);
            if (containsMaintain(finalText)) {
                failures.add("maintain entered final file");
            }
            String finalInvented = inventedTerms(finalText, originalText(original));
            if (!finalInvented.isBlank()) {
                failures.add("invented terms in final file: " + finalInvented);
            }
            String smart = entryText(index, rewritten, "PROJECT:smartpc-builder");
            if (containsAny(smart, "spring boot", "rest", "aws")) {
                failures.add("SmartPC leaked Spring Boot/REST/AWS");
            }
            failures.addAll(crossEntryFailures(index, rewritten, "A"));
            failures.addAll(partialEntryFailures(proposal, writer.lastWritten));
            report.put("parsedFinalEntry", parsedEntries(index, rewritten));
        }
        report.put("confirmPayload", confirmPayload);
        report.put("failures", failures);
        boolean pass = failures.isEmpty();
        report.put("pass", pass);
        report.put("verdict", pass ? (empty ? "PASS_EMPTY_PROPOSAL" : "PASS") : "FAIL");
        writeJson(caseDir.resolve("technical.json"), report);
        writeHumanMarkdown(caseDir, "A", "Yaohang CONFIRMED_NOT_USED", proposal, rewritten, original);
        writeJson(reportDir.resolve("case-a-opportunity.json"), opportunityView(map));
        writeJson(reportDir.resolve("case-a-questions.json"), questionView(map));
        writeJson(reportDir.resolve("case-a-entryReviews.json"),
                proposal.getEntryReviews() == null ? List.of() : proposal.getEntryReviews());
        writeJson(reportDir.resolve("case-a-confirm-payload.json"), confirmPayload);
        assertEquals(uploadsBefore, snapshotUploads(uploadsRoot), "uploads changed during Case A");
        return report;
    }

    private Map<String, Object> runCaseB(
            com.openai.client.OpenAIClient client,
            Path reportDir,
            Path uploadsRoot,
            Map<String, String> uploadsBefore) throws Exception {
        Path caseDir = reportDir.resolve("case-b");
        Files.createDirectories(caseDir);
        Path synthetic = writeSyntheticDocx(tempDir.resolve("SYNTHETIC-EVALUATION-FIXTURE.docx"));
        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        List<ResumeDocumentNode> original = parser.parseDocx(synthetic);
        ClaimLedger ledger = ClaimLedger.from(original);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        String syntheticKey = index.entryByKey().keySet().stream()
                .filter(key -> key.toLowerCase(Locale.ROOT).contains("cloud")
                        || key.toLowerCase(Locale.ROOT).contains("inventory")
                        || key.toLowerCase(Locale.ROOT).contains("synthetic"))
                .findFirst()
                .orElse(index.entryByKey().keySet().stream()
                        .filter(key -> key.startsWith("PROJECT:"))
                        .findFirst()
                        .orElse(""));
        String otherKey = index.entryByKey().keySet().stream()
                .filter(key -> key.startsWith("PROJECT:") && !key.equals(syntheticKey))
                .findFirst()
                .orElse("");

        CountingWriter writer = new CountingWriter(parser, tempDir.resolve("writer-b"));
        MemoryEvidence evidence = new MemoryEvidence(77L);
        RecordingEnhancedGenerator generator = new RecordingEnhancedGenerator(client);
        RecordingReviewer reviewer = new RecordingReviewer(client);
        TracingEnhancedService enhanced = new TracingEnhancedService(generator, reviewer);
        JobServiceImpl jobService = jobService(parser, writer, evidence, enhanced, 11L, 77L, synthetic, "docx");
        stubPdfExport();

        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("case", "B");
        report.put("label", "SYNTHETIC EVALUATION FIXTURE CONFIRMED_USED");
        report.put("syntheticKey", syntheticKey);
        report.put("otherKey", otherKey);
        report.put("confirmedText", CONFIRMED_USED_TEXT);
        report.put("note", "Synthetic facts were never written to Yaohang resume or production DB.");

        V3OpportunityMap map = jobService.scanEnhancedOpportunity(EMAIL, 11L, 77L, List.of());
        report.put("opportunityMap", opportunityView(map));
        report.put("questions", questionView(map));
        V3EnhancedQuestion maintain = map.questions().stream()
                .filter(question -> "maintain".equals(question.topic())
                        && syntheticKey.equals(question.canonicalEntryKey()))
                .findFirst()
                .orElse(map.questions().stream()
                        .filter(question -> "maintain".equals(question.topic()))
                        .findFirst()
                        .orElse(null));
        List<Map<String, String>> answers = new ArrayList<>();
        for (V3EnhancedQuestion question : map.questions()) {
            AnswerEnhancedQuestionRequest request = new AnswerEnhancedQuestionRequest();
            request.setQuestionId(question.id());
            if (maintain != null && question.id().equals(maintain.id())) {
                request.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
                request.setAnswerText(CONFIRMED_USED_TEXT);
                jobService.answerEnhancedQuestion(EMAIL, 11L, 77L, request);
                answers.add(Map.of(
                        "id", question.id(),
                        "topic", question.topic(),
                        "canonicalEntryKey", question.canonicalEntryKey(),
                        "answerStatus", AnswerStatus.CONFIRMED_USED,
                        "answerText", CONFIRMED_USED_TEXT));
            } else {
                request.setAnswerStatus(V3EnhancedAnswerBinder.SKIPPED);
                jobService.answerEnhancedQuestion(EMAIL, 11L, 77L, request);
                answers.add(Map.of(
                        "id", question.id(),
                        "topic", question.topic(),
                        "canonicalEntryKey", question.canonicalEntryKey(),
                        "answerStatus", "SKIPPED",
                        "answerText", ""));
            }
        }
        report.put("answers", answers);
        if (evidence.items.size() != 1) {
            failures.add("evidence rows=" + evidence.items.size() + " expected 1");
        }
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(original), evidence.asUserEvidence());
        List<V3Fact> confirmed = new ArrayList<>();
        for (V3EntryFactGraph graph : graphs.entries()) {
            confirmed.addAll(graph.factsOf(V3FactKind.USER_CONFIRMED));
        }
        long surfaceHits = confirmed.stream()
                .filter(fact -> fact.surface().toLowerCase(Locale.ROOT).contains("weekly")
                        || fact.surface().toLowerCase(Locale.ROOT).contains("authentication"))
                .count();
        report.put("userConfirmedFactCount", confirmed.size());
        report.put("userConfirmedSurfaces", confirmed.stream().map(V3Fact::surface).toList());
        if (surfaceHits != 1) {
            failures.add("USER_CONFIRMED matching fact count=" + surfaceHits);
        }
        for (V3Fact fact : confirmed) {
            if (!syntheticKey.equals(fact.canonicalEntryKey())) {
                failures.add("USER_CONFIRMED bound to " + fact.canonicalEntryKey());
            }
        }
        report.put("evidenceFlow", evidenceFlow(evidence, original, confirmed));
        report.put("writerBeforeProposal", writer.calls.get());
        if (writer.calls.get() != 0) {
            failures.add("proposal 前 Writer=" + writer.calls.get());
        }

        AtomicReference<TailorProposal> stored = bindProposalMapper();
        TailoredResumeResponse proposal = runWithRetry("case-b-proposal", () ->
                jobService.createTailorProposal(EMAIL, 11L, 77L, "ENHANCED"));
        report.put("proposalStatus", proposal.getStatus());
        report.put("entryReviews", proposal.getEntryReviews());
        report.put("traces", tracesView(enhanced.last == null ? List.of() : enhanced.last.traces()));
        report.put("generateCalls", generator.generateCalls);
        report.put("repairCalls", generator.repairCalls);
        report.put("reviewerCalls", reviewer.calls);
        report.put("generatorTelemetry", generator.calls);
        report.put("whyBetter", proposal.getEntryReviews() == null ? List.of()
                : proposal.getEntryReviews().stream().map(V3EntryReview::getWhyBetter).toList());

        String aiText = suggestedText(proposal);
        boolean usedFact = containsAny(aiText, "weekly", "dependencies", "authentication");
        if (!usedFact) {
            failures.add("AI did not naturally use the USER_CONFIRMED maintenance fact");
        }
        if (expandedUnconfirmed(aiText)) {
            failures.add("synthetic confirmed fact was expanded");
        }
        boolean whyMentions = proposal.getEntryReviews() != null && proposal.getEntryReviews().stream()
                .anyMatch(review -> review.getWhyBetter() != null && (
                        review.getWhyBetter().toLowerCase(Locale.ROOT).contains("confirmed")
                                || review.getWhyBetter().toLowerCase(Locale.ROOT).contains("maintenance")
                                || review.getWhyBetter().toLowerCase(Locale.ROOT).contains(CONFIRMED_USED_TEXT.toLowerCase(Locale.ROOT).substring(0, 24))));
        if (!whyMentions) {
            failures.add("whyBetter did not cite user-confirmed maintenance");
        }
        if (!otherKey.isBlank()) {
            String otherProposed = proposal.getEntryReviews() == null ? ""
                    : proposal.getEntryReviews().stream()
                    .filter(review -> otherKey.equals(review.getCanonicalEntryKey()))
                    .map(V3DLiveEvalTest::reviewText)
                    .reduce("", (a, b) -> a + " " + b);
            if (containsAny(otherProposed, "weekly", "authentication defects", "updated application dependencies")) {
                failures.add("USER_CONFIRMED fact crossed to " + otherKey);
            }
        }

        Map<String, Object> confirmPayload = new LinkedHashMap<>();
        Path generated = null;
        List<ResumeDocumentNode> rewritten = original;
        boolean empty = proposal.getEntryReviews() == null || proposal.getEntryReviews().isEmpty();
        if (empty) {
            failures.add("Case B produced no qualifying suggestion, so confirmed fact could not be applied");
            confirmPayload.put("confirmInvoked", false);
        } else {
            List<String> selected = proposal.getEntryReviews().stream()
                    .map(V3EntryReview::getCanonicalEntryKey)
                    .toList();
            confirmPayload.put("selectedEntryKeys", selected);
            ConfirmTailorProposalRequest confirm = new ConfirmTailorProposalRequest();
            confirm.setSelectedEntryKeys(selected);
            when(tailorProposalMapper.findById(anyLong())).thenAnswer(inv -> stored.get());
            when(tailorProposalMapper.claimPending(anyLong(), eq(1L), eq(11L))).thenReturn(1);
            when(tailorProposalMapper.markConfirmed(anyLong(), any(), any())).thenReturn(1);
            jobService.confirmTailorProposal(EMAIL, 11L, 42L, confirm);
            generated = writer.last;
            rewritten = parser.parseDocx(generated);
            if (writer.calls.get() != 1) {
                failures.add("confirm 后 Writer=" + writer.calls.get());
            }
            Path pdfOut = generated.resolveSibling(generated.getFileName().toString().replace(".docx", ".pdf"));
            report.put("docxExists", Files.isRegularFile(generated));
            report.put("pdfExists", Files.isRegularFile(pdfOut));
            String finalText = originalText(rewritten);
            if (!containsAny(finalText, "weekly", "dependencies", "authentication")) {
                failures.add("reparsed DOCX missing confirmed fact");
            }
            if (expandedUnconfirmed(finalText)) {
                failures.add("final file expanded unconfirmed results");
            }
            failures.addAll(structureFailures(original, rewritten, "B"));
            failures.addAll(partialEntryFailures(proposal, writer.lastWritten));
            report.put("parsedFinalEntry", parsedEntries(index, rewritten));
        }
        report.put("confirmPayload", confirmPayload);
        report.put("writerAfterConfirm", writer.calls.get());
        report.put("failures", failures);
        boolean pass = failures.isEmpty();
        report.put("pass", pass);
        report.put("verdict", pass ? "PASS" : "FAIL");
        writeJson(caseDir.resolve("technical.json"), report);
        writeHumanMarkdown(caseDir, "B", "SYNTHETIC CONFIRMED_USED", proposal, rewritten, original);
        writeJson(reportDir.resolve("case-b-opportunity.json"), opportunityView(map));
        writeJson(reportDir.resolve("case-b-questions.json"), questionView(map));
        writeJson(reportDir.resolve("case-b-entryReviews.json"),
                proposal.getEntryReviews() == null ? List.of() : proposal.getEntryReviews());
        writeJson(reportDir.resolve("case-b-confirm-payload.json"), confirmPayload);
        assertEquals(uploadsBefore, snapshotUploads(uploadsRoot), "uploads changed during Case B");
        return report;
    }

    private JobServiceImpl jobService(
            ResumeDocumentServiceImpl parser,
            CountingWriter writer,
            MemoryEvidence evidence,
            TracingEnhancedService enhanced,
            long jobId,
            long resumeId,
            Path docx,
            String type) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "V3");
        JobServiceImpl service = new JobServiceImpl(
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
                StoragePathResolver.forRoot(tempDir.resolve("job-uploads-" + resumeId)),
                null,
                null,
                null,
                JobSearchProperties.defaults(),
                new TailorEngineRouter(new TailorEngineProperties(env)),
                null,
                resumeAnalysisInputFactory,
                new V3EnhancedOpportunityService(),
                enhanced,
                evidence
        );
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
        Job job = new Job();
        job.setId(jobId);
        job.setUserId(1L);
        job.setDescription(V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        when(jobMapper.findById(jobId)).thenReturn(job);
        Resume resume = new Resume();
        resume.setId(resumeId);
        resume.setUserId(1L);
        resume.setFileType(type);
        resume.setFilePath(docx.toString());
        when(resumeMapper.findById(resumeId)).thenReturn(resume);
        when(resumeAnalysisInputFactory.resolve(resume, 1L)).thenReturn(new ResumeAnalysisInput(
                resume, resume, null, List.of(), Map.of(), null, resumeId));
        return service;
    }

    private void stubPdfExport() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            Path docx = invocation.getArgument(0);
            if (docx == null) {
                return null;
            }
            Path pdf = docx.resolveSibling(docx.getFileName().toString().replace(".docx", ".pdf"));
            Files.writeString(pdf, "%PDF-1.4\n");
            return pdf;
        }).when(pdfExportService).exportPdf(any());
    }

    private AtomicReference<TailorProposal> bindProposalMapper() {
        AtomicReference<TailorProposal> stored = new AtomicReference<>();
        when(tailorProposalMapper.insert(any())).thenAnswer(invocation -> {
            TailorProposal proposal = invocation.getArgument(0);
            proposal.setId(42L);
            if (proposal.getExpiresAt() == null) {
                proposal.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(12));
            }
            stored.set(proposal);
            return 1;
        });
        when(tailorProposalMapper.findById(42L)).thenAnswer(inv -> stored.get());
        return stored;
    }

    private static <T> T runWithRetry(String phase, Attempt<T> attempt) throws Exception {
        try {
            return attempt.run();
        } catch (RuntimeException error) {
            if (!retryable(error)) {
                throw error;
            }
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("phase", phase);
            retry.put("atUtc", Instant.now().toString());
            retry.put("exception", error.getClass().getSimpleName());
            String message = String.valueOf(error.getMessage());
            retry.put("reason", message.length() > 240 ? message.substring(0, 240) : message);
            Files.writeString(
                    Path.of("docs", "v3-3d-live", phase + "-retry.json"),
                    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(retry),
                    StandardCharsets.UTF_8);
            return attempt.run();
        }
    }

    private static boolean retryable(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getClass().getName()).append(' ').append(current.getMessage()).append(' ');
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        return lower.contains("429")
                || lower.contains("502")
                || lower.contains("503")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("rate limit")
                || lower.contains("too many requests");
    }

    private static Path writeSyntheticDocx(Path path) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String line : List.of(
                    "SYNTHETIC EVALUATION FIXTURE",
                    "synthetic.eval@example.com",
                    "https://github.com/synthetic-evaluation-fixture",
                    "RELEVANT PROJECTS",
                    "Cloud Inventory Service | Personal Project | Jan 2024 - Present",
                    "Built a Java Spring Boot application.",
                    "Deployed the application to AWS.",
                    "Dataset Cleaner | Personal Project | 2023",
                    "Wrote a Python script to clean CSV files.",
                    "EDUCATION",
                    "York University, BSc Computer Science, 2021 - 2025")) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(line);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }
        return path;
    }

    private static Map<String, Object> opportunityView(V3OpportunityMap map) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("writerCalls", map.writerCalls());
        view.put("openaiCalls", map.openaiCalls());
        view.put("wroteJobOrResume", map.wroteJobOrResume());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (V3OpportunityEntry entry : map.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryTitle", entry.entryTitle());
            row.put("entryType", entry.entryType());
            row.put("priority", entry.priority() == null ? "" : entry.priority().name());
            row.put("coveredRequirements", entry.coveredRequirements());
            row.put("partiallyCoveredRequirements", entry.partiallyCoveredRequirements());
            row.put("canStrengthenWithExistingFacts", entry.canStrengthenWithExistingFacts());
            row.put("missingFactsThatCanBeConfirmed", entry.missingFactsThatCanBeConfirmed());
            row.put("cannotInvent", entry.cannotInvent());
            row.put("canonicalEntryKey", entry.canonicalEntryKey());
            entries.add(row);
        }
        view.put("entries", entries);
        return view;
    }

    private static List<Map<String, Object>> questionView(V3OpportunityMap map) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (V3EnhancedQuestion question : map.questions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", question.id());
            row.put("entryTitle", question.entryTitle());
            row.put("topic", question.topic());
            row.put("promptEn", question.promptEn());
            row.put("promptZh", question.promptZh());
            row.put("skipAllowed", question.skipAllowed());
            row.put("canonicalEntryKey", question.canonicalEntryKey());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> tracesView(List<V3BasicRunResult.V3EntryTrace> traces) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (traces == null) {
            return rows;
        }
        for (V3BasicRunResult.V3EntryTrace trace : traces) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("canonicalEntryKey", trace.canonicalEntryKey());
            row.put("chosenVariant", trace.chosenVariant() == null ? "" : trace.chosenVariant().name());
            row.put("noChangeReason", trace.noChangeReason());
            row.put("validatorCode", trace.validatorCode());
            row.put("qualityImproved", trace.qualityImproved());
            row.put("languageAccepted", trace.languageAccepted());
            row.put("originalBullets", trace.originalBullets());
            row.put("clarityBullets", trace.clarityBullets());
            row.put("targetedBullets", trace.targetedBullets());
            row.put("hybrid1Bullets", trace.hybrid1Bullets());
            row.put("hybrid2Bullets", trace.hybrid2Bullets());
            row.put("finalBullets", trace.finalBullets());
            row.put("promptVersion", trace.promptVersion());
            row.put("model", trace.model());
            row.put("latencyMs", trace.latencyMs());
            row.put("promptTokens", trace.promptTokens());
            row.put("completionTokens", trace.completionTokens());
            row.put("repairReason", trace.repairReason());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> evidenceFlow(
            MemoryEvidence evidence, List<ResumeDocumentNode> nodes, List<V3Fact> confirmed) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("fixtureEvidenceRows", evidence.items.size());
        flow.put("items", evidence.items.stream().map(item -> Map.of(
                "id", String.valueOf(item.getId()),
                "entryKey", String.valueOf(item.getEntryKey()),
                "answerStatus", String.valueOf(item.getAnswerStatus()),
                "topic", String.valueOf(item.getTopic()),
                "action", String.valueOf(item.getAction())
        )).toList());
        flow.put("userConfirmedFacts", confirmed.stream().map(V3Fact::surface).toList());
        flow.put("rejectedStatuses", evidence.items.stream()
                .filter(item -> AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus()))
                .map(ResumeEvidenceItemResponse::getEntryKey)
                .toList());
        flow.put("nodeCount", nodes.size());
        return flow;
    }

    private static String suggestedText(TailoredResumeResponse proposal) {
        StringBuilder text = new StringBuilder();
        if (proposal.getEntryReviews() != null) {
            for (V3EntryReview review : proposal.getEntryReviews()) {
                text.append(' ').append(reviewText(review));
            }
        }
        if (proposal.getChanges() != null) {
            for (TailorChange change : proposal.getChanges()) {
                text.append(' ').append(change.getNewText());
            }
        }
        return text.toString();
    }

    private static String reviewText(V3EntryReview review) {
        StringBuilder text = new StringBuilder();
        if (review.getProposedNodes() != null) {
            for (var node : review.getProposedNodes()) {
                text.append(' ').append(node.getText());
            }
        }
        return text.toString();
    }

    private static String originalText(List<ResumeDocumentNode> nodes) {
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : nodes) {
            text.append(' ').append(node.getText());
        }
        return text.toString();
    }

    private static boolean containsMaintain(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("maintain") || lower.contains("maintained") || lower.contains("maintenance");
    }

    private static String inventedTerms(String text, String original) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String orig = original == null ? "" : original.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String term : List.of("mysql", "unit test", "unit tests", "docker", "kubernetes", "k8s", "agile",
                "production", "throughput", "latency", "qps")) {
            if (lower.contains(term) && !orig.contains(term)) {
                hits.add(term);
            }
        }
        return String.join(",", hits);
    }

    private static boolean builtApiUpgrade(List<ResumeDocumentNode> original, String aiText) {
        String orig = originalText(original).toLowerCase(Locale.ROOT);
        String ai = aiText == null ? "" : aiText.toLowerCase(Locale.ROOT);
        boolean origBuilt = orig.contains("built") && orig.contains("api");
        boolean aiBuilt = ai.contains("built") && (ai.contains("apis") || ai.contains("api"));
        return !origBuilt && aiBuilt && ai.contains("built apis");
    }

    private static boolean expandedUnconfirmed(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("monitor")
                || lower.contains("zero downtime")
                || lower.contains("zero-downtime")
                || lower.contains("high availability")
                || lower.contains("highly available")
                || lower.contains("99.9")
                || lower.contains("performance optimization")
                || lower.contains("never failed")
                || lower.contains("zero incident");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> structureFailures(
            List<ResumeDocumentNode> original, List<ResumeDocumentNode> rewritten, String caseId) {
        List<String> failures = new ArrayList<>();
        if (original.size() != rewritten.size()) {
            failures.add(caseId + " node count " + original.size() + " -> " + rewritten.size());
        }
        int n = Math.min(original.size(), rewritten.size());
        for (int i = 0; i < n; i++) {
            if (!original.get(i).getNodeId().equals(rewritten.get(i).getNodeId())) {
                failures.add(caseId + " node order changed at " + i);
                break;
            }
        }
        Map<String, String> before = byId(original);
        Map<String, String> after = byId(rewritten);
        for (ResumeDocumentNode node : original) {
            String text = node.getText() == null ? "" : node.getText();
            String lower = text.toLowerCase(Locale.ROOT);
            boolean protectedNode = lower.startsWith("http")
                    || lower.contains("education")
                    || lower.contains("@")
                    || (lower.contains("prime deals") && !lower.contains("java"))
                    || (lower.contains("smartpc") && !lower.contains("java"))
                    || (lower.contains("cloud inventory") && !lower.contains("java"))
                    || (lower.contains("york university"))
                    || looksLikeDateOnly(text);
            if (protectedNode && !text.equals(after.getOrDefault(node.getNodeId(), ""))) {
                failures.add(caseId + " protected node changed: " + node.getNodeId());
            }
        }
        return failures;
    }

    private static boolean looksLikeDateOnly(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(19|20)\\d{2}\\b.*")
                && !lower.contains("java")
                && !lower.contains("deploy")
                && text.length() < 80
                && (lower.contains("present") || lower.contains("jan") || lower.contains("-"));
    }

    private static List<String> crossEntryFailures(
            CanonicalEntryKeyResolver.Index index, List<ResumeDocumentNode> rewritten, String caseId) {
        List<String> failures = new ArrayList<>();
        String smart = entryText(index, rewritten, "PROJECT:smartpc-builder");
        String prime = entryText(index, rewritten, "PROJECT:prime-deals");
        if (!smart.isBlank() && containsAny(smart, "shopping cart", "prime deals")) {
            failures.add(caseId + " cross-entry facts into SmartPC");
        }
        if (!prime.isBlank() && containsAny(prime, "cpu", "gpu", "budget")) {
            failures.add(caseId + " cross-entry facts into Prime");
        }
        return failures;
    }

    private static List<String> partialEntryFailures(TailoredResumeResponse proposal, List<TailorChange> written) {
        List<String> failures = new ArrayList<>();
        if (proposal.getEntryReviews() == null || written == null) {
            return failures;
        }
        for (V3EntryReview review : proposal.getEntryReviews()) {
            if (review.getProposedNodes() == null || review.getProposedNodes().isEmpty()) {
                continue;
            }
            List<String> proposedIds = review.getProposedNodes().stream().map(node -> node.getNodeId()).toList();
            List<String> writtenIds = written.stream()
                    .filter(change -> proposedIds.contains(change.getNodeId()))
                    .map(TailorChange::getNodeId)
                    .toList();
            if (!writtenIds.isEmpty() && writtenIds.size() != proposedIds.size()) {
                failures.add("partial entry nodes for " + review.getCanonicalEntryKey()
                        + " written=" + writtenIds.size() + " proposed=" + proposedIds.size());
            }
        }
        return failures;
    }

    private static Map<String, Object> parsedEntries(
            CanonicalEntryKeyResolver.Index index, List<ResumeDocumentNode> nodes) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : index.entryByKey().keySet()) {
            map.put(key, entryText(index, nodes, key));
        }
        return map;
    }

    private static String entryText(
            CanonicalEntryKeyResolver.Index index, List<ResumeDocumentNode> nodes, String key) {
        if (key == null || key.isBlank() || !index.containsKey(key)) {
            return "";
        }
        Map<String, String> byId = byId(nodes);
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : index.entry(key).nodes()) {
            text.append(' ').append(byId.getOrDefault(node.getNodeId(), node.getText()));
        }
        return text.toString();
    }

    private static Map<String, String> byId(List<ResumeDocumentNode> nodes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            map.put(node.getNodeId(), node.getText());
        }
        return map;
    }

    private static void writeHumanMarkdown(
            Path caseDir,
            String caseId,
            String title,
            TailoredResumeResponse proposal,
            List<ResumeDocumentNode> rewritten,
            List<ResumeDocumentNode> original) throws Exception {
        StringBuilder vs = new StringBuilder();
        vs.append("# Case ").append(caseId).append(" original vs proposed\n\n");
        vs.append("**").append(title).append("**\n\n");
        if (proposal.getEntryReviews() == null || proposal.getEntryReviews().isEmpty()) {
            vs.append("No qualifying proposed entries.\n");
        } else {
            for (V3EntryReview review : proposal.getEntryReviews()) {
                vs.append("## ").append(review.getEntryTitle()).append('\n');
                vs.append("- whyBetter: ").append(review.getWhyBetter()).append("\n\n");
                vs.append("### Original\n\n");
                if (review.getOriginalNodes() != null) {
                    for (var node : review.getOriginalNodes()) {
                        vs.append("- ").append(node.getText()).append('\n');
                    }
                }
                vs.append("\n### Proposed\n\n");
                if (review.getProposedNodes() != null) {
                    for (var node : review.getProposedNodes()) {
                        vs.append("- ").append(node.getText()).append('\n');
                    }
                }
                vs.append('\n');
            }
        }
        Files.writeString(caseDir.resolve("original-vs-proposed.md"), vs.toString(), StandardCharsets.UTF_8);

        StringBuilder finals = new StringBuilder();
        finals.append("# Case ").append(caseId).append(" final output\n\n");
        finals.append("**").append(title).append("**\n\n");
        for (ResumeDocumentNode node : rewritten) {
            finals.append("- ").append(node.getText()).append('\n');
        }
        finals.append("\n## Human review (leave blank; Cursor does not fill)\n\n");
        finals.append("| 项 | 结论 |\n| --- | --- |\n");
        finals.append("| 是否愿意直接接受 | |\n");
        finals.append("| 是否只需小改 | |\n");
        finals.append("| 是否拒绝 | |\n");
        finals.append("| 自然度 1–5 | |\n");
        finals.append("| JD 相关性 1–5 | |\n");
        finals.append("| 事实准确 PASS/FAIL | |\n");
        finals.append("| 评语 | |\n");
        Files.writeString(caseDir.resolve("final-output.md"), finals.toString(), StandardCharsets.UTF_8);
    }

    private static int countGenerated(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".docx") || name.endsWith(".pdf");
                    })
                    .count();
        }
    }

    private static Map<String, String> snapshotUploads(Path uploads) throws Exception {
        TreeMap<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(uploads)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.walk(uploads)) {
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

    private static Map<String, String> snapshotV3Sources() throws Exception {
        Path root = Path.of("src", "main", "java", "aijob", "tailor", "v3");
        TreeMap<String, String> hashes = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(file -> {
                try {
                    hashes.put(root.relativize(file).toString().replace('\\', '/'), sha256(file));
                } catch (Exception ex) {
                    hashes.put(root.relativize(file).toString().replace('\\', '/'), "ERROR");
                }
            });
        }
        return hashes;
    }

    private static Map<String, Object> compareUploads(Map<String, String> before, Map<String, String> after) {
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String path : after.keySet()) {
            if (!before.containsKey(path)) {
                added.add(path);
            } else if (!before.get(path).equals(after.get(path))) {
                changed.add(path);
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                deleted.add(path);
            }
        }
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("addedCount", added.size());
        compare.put("deletedCount", deleted.size());
        compare.put("changedCount", changed.size());
        compare.put("added", added);
        compare.put("deleted", deleted);
        compare.put("changed", changed);
        return compare;
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), value);
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run() throws Exception;
    }

    private static final class TracingEnhancedService extends V3EnhancedTailorService {
        private V3EnhancedRunResult last;

        private TracingEnhancedService(
                V3EnhancedEntryRewriteGenerator generator, V3ResumeLanguageReviewer reviewer) {
            super(generator, reviewer);
        }

        @Override
        public V3EnhancedRunResult run(
                List<ResumeDocumentNode> nodes,
                String jobDescription,
                List<V3UserEvidence> evidence,
                V3OpportunityMap opportunityMap) {
            last = super.run(nodes, jobDescription, evidence, opportunityMap);
            return last;
        }
    }

    private static final class CountingWriter implements ResumeDocxTailorService {
        private final AtomicInteger calls = new AtomicInteger();
        private final ResumeDocxTailorServiceImpl inner;
        private final Path root;
        private Path last;
        private List<TailorChange> lastWritten = List.of();

        private CountingWriter(ResumeDocumentServiceImpl parser, Path uploadsRoot) {
            this.root = uploadsRoot;
            this.inner = new ResumeDocxTailorServiceImpl(parser, StoragePathResolver.forRoot(uploadsRoot));
        }

        @Override
        public Path createTailoredDocx(Path originalDocx, List<TailorChange> changes, Long userId, Long jobId) {
            calls.incrementAndGet();
            lastWritten = changes == null ? List.of() : List.copyOf(changes);
            last = inner.createTailoredDocx(originalDocx, changes, userId, jobId);
            return last;
        }
    }

    private static final class MemoryEvidence implements ResumeEvidenceService {
        private final List<ResumeEvidenceItemResponse> items = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(1);
        private final Long resumeId;

        private MemoryEvidence(Long resumeId) {
            this.resumeId = resumeId;
        }

        @Override
        public List<ResumeEvidenceItemResponse> list(String email, Long resumeId) {
            return List.copyOf(items);
        }

        @Override
        public SaveResult save(String email, Long resumeId, SaveResumeEvidenceRequest request) {
            for (ResumeEvidenceItemResponse item : items) {
                if (String.valueOf(item.getTopic()).equals(String.valueOf(request.getTopic()))
                        && String.valueOf(item.getEntryKey()).equals(String.valueOf(request.getEntryKey()))) {
                    item.setAnswerStatus(request.getAnswerStatus());
                    item.setAction(request.getAction());
                    item.setResult(request.getResult());
                    item.setMetric(request.getMetric());
                    item.setContext(request.getContext());
                    return new SaveResult(item, false);
                }
            }
            ResumeEvidenceItemResponse created = new ResumeEvidenceItemResponse();
            created.setId(nextId.getAndIncrement());
            created.setResumeId(this.resumeId);
            created.setTopic(request.getTopic());
            created.setEntryKey(request.getEntryKey());
            created.setAnswerStatus(request.getAnswerStatus());
            created.setAction(request.getAction());
            created.setResult(request.getResult());
            created.setMetric(request.getMetric());
            created.setContext(request.getContext());
            items.add(created);
            return new SaveResult(created, true);
        }

        @Override
        public void delete(String email, Long resumeId, Long evidenceId) {
            items.removeIf(item -> evidenceId.equals(item.getId()));
        }

        private List<V3UserEvidence> asUserEvidence() {
            List<V3UserEvidence> out = new ArrayList<>();
            for (ResumeEvidenceItemResponse item : items) {
                out.add(new V3UserEvidence(
                        item.getId(),
                        item.getEntryKey(),
                        item.getAnswerStatus(),
                        item.getAction() == null ? "" : item.getAction(),
                        item.getResult() == null ? "" : item.getResult(),
                        item.getMetric() == null ? "" : item.getMetric(),
                        item.getContext() == null ? "" : item.getContext(),
                        item.getTopic() == null ? "" : item.getTopic()));
            }
            return out;
        }
    }

    private static final class RecordingEnhancedGenerator implements V3EnhancedEntryRewriteGenerator {
        private final OpenAiV3EnhancedEntryRewriteGenerator inner;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private int generateCalls;
        private int repairCalls;

        private RecordingEnhancedGenerator(com.openai.client.OpenAIClient client) {
            this.inner = new OpenAiV3EnhancedEntryRewriteGenerator(client, MODEL);
        }

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            if (request.repair()) {
                repairCalls++;
            } else {
                generateCalls++;
            }
            V3EntryRewriteResult result = inner.generate(request);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("canonicalEntryKey", request.canonicalEntryKey());
            row.put("repair", request.repair());
            row.put("productMode", request.productMode() == null ? "" : request.productMode().name());
            row.put("promptVersion", result.telemetry().promptVersion());
            row.put("model", result.telemetry().model());
            row.put("latencyMs", result.telemetry().latencyMs());
            row.put("promptTokens", result.telemetry().promptTokens());
            row.put("completionTokens", result.telemetry().completionTokens());
            row.put("clarityDecision", result.clarityFirst().decision().name());
            row.put("targetedDecision", result.jdTargeted().decision().name());
            calls.add(row);
            return result;
        }
    }

    private static final class RecordingReviewer implements V3ResumeLanguageReviewer {
        private final OpenAiV3ResumeLanguageReviewer inner;
        private final List<Map<String, Object>> records = new ArrayList<>();
        private int calls;

        private RecordingReviewer(com.openai.client.OpenAIClient client) {
            this.inner = new OpenAiV3ResumeLanguageReviewer(client, MODEL);
        }

        @Override
        public List<Choice> review(String jobDescription, List<EntryComparison> entries) {
            calls++;
            long started = System.nanoTime();
            List<Choice> choices = inner.review(jobDescription, entries);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("latencyMs", (System.nanoTime() - started) / 1_000_000L);
            row.put("entryCount", entries == null ? 0 : entries.size());
            row.put("choices", choices.stream().map(choice -> Map.of(
                    "canonicalEntryKey", choice.canonicalEntryKey(),
                    "selection", choice.selection() == null ? "" : choice.selection().name(),
                    "reason", choice.reason() == null ? "" : choice.reason()
            )).toList());
            records.add(row);
            return choices;
        }
    }
}
