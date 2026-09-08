package aijob.tailor.v3;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.ClaimLedger;
import aijob.util.GeneratedResumePdfFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Frozen V3-2.3 nine-round live quality eval. Writes only to
 * {@code docs/v3-2.3-live-9}. Enable with {@code AIJOB_V3_23_LIVE9=true}.
 *
 * <p>Harness only. Production prompts, validators, materiality, hybrid selector,
 * scorer, reviewer, thresholds, model, and fixtures stay frozen.
 */
class V3BasicLive9EvalV323Test {

    private static final String PROMPT_VERSION = V3GenerationTelemetry.PROMPT_VERSION;
    private static final String EXPECTED_BRANCH = "product-reset-v1";
    private static final String MODEL_DEFAULT = "gpt-4o";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "AIJOB_V3_23_LIVE9", matches = "true")
    void nineInterleavedRunsWriteReportsWithoutTouchingUploads() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.toLowerCase(Locale.ROOT).contains("placeholder"), "placeholder key");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", MODEL_DEFAULT);
        assumeTrue(MODEL_DEFAULT.equals(model), "frozen model is gpt-4o");
        assumeTrue("v3-2.3-materiality-hybrid".equals(PROMPT_VERSION), "frozen promptVersion");

        Path reportDir = Path.of("docs", "v3-2.3-live-9");
        Files.createDirectories(reportDir);
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.1-live")), "must not replace v3-2.1-live");
        assumeTrue(Files.exists(Path.of("docs", "v3-2.3-smoke", "run.json")), "must not replace first smoke");
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.3-smoke", "verification-2")),
                "must not replace verification-2");

        Path uploadsRoot = Path.of("uploads").toAbsolutePath().normalize();
        Map<String, String> uploadsBefore = snapshotUploads(uploadsRoot);
        Map<String, String> sourceBefore = snapshotV3Sources();
        Path pdf = GeneratedResumePdfFixture.pdf();

        List<JdSpec> jds = List.of(
                new JdSpec("JD1", "CURRENT_JD", "Junior Java Backend Developer",
                        "TailorAnalysisServiceImplTest.CURRENT_JD",
                        V3BasicLiveQualityAcceptanceTest.JD_BACKEND),
                new JdSpec("JD2", "ALEX_MORGAN_JAVA_JD", "Junior Java Backend Developer (intern-complete)",
                        "TailorAnalysisTaxonomyS1Test.ALEX_MORGAN_JAVA_JD",
                        V3BasicLiveQualityAcceptanceTest.JD_INTERN),
                new JdSpec("JD3", "S1A_JD", "Junior Java Backend Developer (S1A AWS/testing)",
                        "TailorAnalysisS1ATest.S1A_JD",
                        V3BasicLiveQualityAcceptanceTest.JD_CLOUD)
        );

        Map<String, Object> freezeBefore = freezeSnapshot(
                "before", model, uploadsBefore, sourceBefore, pdf, jds);
        writeJson(reportDir.resolve("freeze-before.json"), freezeBefore);
        String branch = String.valueOf(freezeBefore.get("branch"));
        assumeTrue(EXPECTED_BRANCH.equals(branch), "branch must be product-reset-v1, was " + branch);

        Path canonical = GeneratedResumePdfFixture.writeCanonicalDocx(pdf, tempDir.resolve("alexmorgan.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();

        List<Map<String, Object>> allRuns = new ArrayList<>();
        Map<String, Integer> hard = zeroHard();
        Map<String, Integer> rejectReasons = new LinkedHashMap<>();
        Map<String, Integer> wins = zeroWins();
        int nonOriginalEntries = 0;
        int repairEntries = 0;
        int fallbackEntries = 0;
        int writerTotal = 0;
        int generateTotal = 0;
        int repairTotal = 0;
        int reviewTotal = 0;
        long latencyTotal = 0;
        int tokenTotal = 0;
        int tokenKnown = 0;
        String abortReason = "";
        String abortAfter = "";

        List<RunPlan> plans = interleavedPlans(jds);
        for (RunPlan plan : plans) {
            if (!abortReason.isBlank()) {
                break;
            }
            Map<String, String> sourceNow = snapshotV3Sources();
            if (!sourceBefore.equals(sourceNow)) {
                abortReason = "V3 production source SHA changed mid-eval";
                abortAfter = plan.runId;
                break;
            }
            Map<String, String> uploadsNow = snapshotUploads(uploadsRoot);
            if (!uploadsBefore.equals(uploadsNow)) {
                abortReason = "real uploads changed mid-eval";
                abortAfter = plan.runId;
                writeJson(reportDir.resolve("uploads-mid-abort.json"), compareUploads(uploadsBefore, uploadsNow));
                break;
            }

            RecordingGenerator generator = new RecordingGenerator(
                    new OpenAiV3EntryRewriteGenerator(client, model));
            RecordingReviewer reviewer = new RecordingReviewer(
                    new OpenAiV3ResumeLanguageReviewer(client, model));
            V3BasicTailorService service = new V3BasicTailorService(generator, reviewer);

            long started = System.nanoTime();
            V3BasicRunResult result;
            List<Map<String, Object>> retries = new ArrayList<>();
            try {
                result = service.run(nodes, plan.jd.text);
            } catch (RuntimeException first) {
                if (!retryable(first)) {
                    throw first;
                }
                retries.add(retryRecord(first, "first-attempt"));
                try {
                    result = new V3BasicTailorService(
                            generator = new RecordingGenerator(new OpenAiV3EntryRewriteGenerator(client, model)),
                            reviewer = new RecordingReviewer(new OpenAiV3ResumeLanguageReviewer(client, model)))
                            .run(nodes, plan.jd.text);
                } catch (RuntimeException second) {
                    retries.add(retryRecord(second, "retry-failed"));
                    throw second;
                }
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;

            int writerCalls = 0;
            String writerName = "";
            if (!result.changes().isEmpty()) {
                Path writerRoot = tempDir.resolve("writer-" + plan.runId);
                Path writerOut = new ResumeDocxTailorServiceImpl(
                        new ResumeDocumentServiceImpl(),
                        StoragePathResolver.forRoot(writerRoot))
                        .createTailoredDocx(canonical, result.changes(), 1L, 10L);
                writerCalls = 1;
                writerName = writerOut.getFileName().toString();
                assertFalse(writerOut.toAbsolutePath().normalize().startsWith(uploadsRoot),
                        "Writer leaked into uploads");
            }
            Map<String, String> uploadsAfterRun = snapshotUploads(uploadsRoot);
            assertEquals(uploadsBefore, uploadsAfterRun, "real uploads must not change after " + plan.runId);

            Map<String, Object> run = new LinkedHashMap<>();
            run.put("runId", plan.runId);
            run.put("jdId", plan.jd.id);
            run.put("jdName", plan.jd.name);
            run.put("jdTitle", plan.jd.title);
            run.put("jdSource", plan.jd.source);
            run.put("jdTextSha256", plan.jd.textSha256);
            run.put("repeat", plan.repeat);
            run.put("model", model);
            run.put("promptVersion", PROMPT_VERSION);
            run.put("thresholds", thresholds());
            run.put("wallLatencyMs", elapsed);
            run.put("generateCalls", result.generateCalls());
            run.put("repairCalls", result.repairCalls());
            run.put("languageReviewCalls", result.languageReviewCalls());
            run.put("writerCalls", writerCalls);
            run.put("writerOutput", writerName);
            run.put("rewrittenEntryCount", result.rewrittenEntryCount());
            run.put("transientRetries", retries);

            List<Map<String, Object>> entryReports = new ArrayList<>();
            int nonOriginalThisRun = 0;
            boolean identityStop = false;
            for (V3BasicRunResult.V3EntryTrace trace : result.traces()) {
                V3EntryFactGraph graph = graphs.entry(trace.canonicalEntryKey());
                Map<String, Object> entry = entryReport(
                        graphs, graph, generator, reviewer, trace, plan.jd);
                accumulateHard(hard, entry);
                accumulateRejects(rejectReasons, entry);
                entryReports.add(entry);
                String chosen = String.valueOf(trace.chosenVariant());
                bump(wins, winBucket(chosen));
                if (!"ORIGINAL".equals(chosen) && !"REJECT_ALL_AI".equals(chosen)) {
                    nonOriginalEntries++;
                    nonOriginalThisRun++;
                }
                if (Boolean.TRUE.equals(entry.get("repairOccurred"))) {
                    repairEntries++;
                }
                if (Boolean.TRUE.equals(entry.get("fallbackTriggered"))) {
                    fallbackEntries++;
                }
                latencyTotal += trace.latencyMs();
                Integer tokens = (Integer) entry.get("generatePlusRepairTokens");
                if (tokens != null) {
                    tokenTotal += tokens;
                    tokenKnown++;
                }
                if (Integer.valueOf(1).equals(entry.get("finalIdentityMutation"))) {
                    identityStop = true;
                }
            }
            run.put("entries", entryReports);
            run.put("nonOriginalEntries", nonOriginalThisRun);
            run.put("hadNaturalCompleteNonOriginal", nonOriginalThisRun > 0);
            allRuns.add(run);
            generateTotal += result.generateCalls();
            repairTotal += result.repairCalls();
            reviewTotal += result.languageReviewCalls();
            writerTotal += writerCalls;
            mapper.writeValue(reportDir.resolve(plan.runId + ".json").toFile(), run);

            if (identityStop) {
                abortReason = "FINAL identityMutation detected";
                abortAfter = plan.runId;
            }
        }

        Map<String, String> uploadsAfter = snapshotUploads(uploadsRoot);
        Map<String, String> sourceAfter = snapshotV3Sources();
        Map<String, Object> freezeAfter = freezeSnapshot(
                "after", model, uploadsAfter, sourceAfter, pdf, jds);
        freezeAfter.put("abortReason", abortReason);
        freezeAfter.put("abortAfter", abortAfter);
        writeJson(reportDir.resolve("freeze-after.json"), freezeAfter);
        Map<String, Object> uploadsCompare = compareUploads(uploadsBefore, uploadsAfter);
        writeJson(reportDir.resolve("uploads-compare.json"), uploadsCompare);

        int completedRuns = allRuns.size();
        int eligibleEntries = allRuns.stream()
                .mapToInt(run -> listSize(run.get("entries")))
                .sum();
        int rewrittenEntries = countRewrittenEntries(allRuns);
        int rewrittenBullets = countRewrittenBullets(allRuns);
        int nonOriginalRounds = (int) allRuns.stream()
                .filter(run -> Boolean.TRUE.equals(run.get("hadNaturalCompleteNonOriginal")))
                .count();
        boolean sourceUnchanged = sourceBefore.equals(sourceAfter);
        boolean uploadsUnchanged = uploadsBefore.equals(uploadsAfter);
        boolean hardPass = hard.values().stream().allMatch(v -> v == null || v == 0)
                && Integer.valueOf(0).equals(uploadsCompare.get("added"))
                && Integer.valueOf(0).equals(uploadsCompare.get("deleted"))
                && Integer.valueOf(0).equals(uploadsCompare.get("changed"))
                && Integer.valueOf(0).equals(uploadsCompare.get("locked"));
        boolean objectivePass = hardPass
                && sourceUnchanged
                && abortReason.isBlank()
                && completedRuns == 9
                && nonOriginalRounds >= 8;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("completedAtUtc", Instant.now().toString());
        summary.put("promptVersion", PROMPT_VERSION);
        summary.put("model", model);
        summary.put("thresholds", thresholds());
        summary.put("runsRequested", 9);
        summary.put("runsCompleted", completedRuns);
        summary.put("interleavedOrder", plans.stream().map(p -> p.runId).toList());
        summary.put("abortReason", abortReason);
        summary.put("abortAfter", abortAfter);
        summary.put("generateCalls", generateTotal);
        summary.put("repairCalls", repairTotal);
        summary.put("languageReviewCalls", reviewTotal);
        summary.put("writerCalls", writerTotal);
        summary.put("sumTraceLatencyMs", latencyTotal);
        summary.put("sumGenerateRepairTokens", tokenTotal);
        summary.put("tokenKnownEntryCount", tokenKnown);
        summary.put("avgTraceLatencyMs", completedRuns == 0 ? 0 : latencyTotal / Math.max(1, eligibleEntries));
        summary.put("avgTokensPerRun", completedRuns == 0 ? 0 : tokenTotal / Math.max(1, completedRuns));
        summary.put("wins", wins);
        summary.put("nonOriginalEntries", nonOriginalEntries);
        summary.put("nonOriginalRounds", nonOriginalRounds);
        summary.put("repairEntries", repairEntries);
        summary.put("fallbackEntries", fallbackEntries);
        summary.put("repairRate", rate(repairEntries, eligibleEntries));
        summary.put("fallbackRate", rate(fallbackEntries, eligibleEntries));
        summary.put("eligibleEntries", eligibleEntries);
        summary.put("rewrittenEntries", rewrittenEntries);
        summary.put("rewrittenBullets", rewrittenBullets);
        summary.put("completeRuns", completedRuns);
        summary.put("hardMetricsFinal", hard);
        summary.put("rejectReasonDistribution", rejectReasons);
        summary.put("sourceShaUnchanged", sourceUnchanged);
        summary.put("uploadsUnchanged", uploadsUnchanged);
        summary.put("uploadsCompare", uploadsCompare);
        summary.put("objectivePass", objectivePass);
        summary.put("hardPass", hardPass);
        summary.put("humanAcceptRates", "NOT_FILLED_BY_CURSOR");
        summary.put("industryAverageClaim", false);
        summary.put("v2Freeze", "PENDING_POST_EVAL");
        writeJson(reportDir.resolve("summary.json"), summary);

        writeTechnicalMarkdown(reportDir.resolve("technical.md"), jds, allRuns, summary, hard);
        writeFinalsMarkdown(reportDir.resolve("finals.md"), allRuns);
        Map<String, Object> blindKey = writeBlindReview(reportDir, allRuns, jds);
        writeJson(reportDir.resolve("blind-key.json"), blindKey);
        writeHumanRates(reportDir.resolve("human-rates.md"), summary);
        writeRejectReasons(reportDir.resolve("reject-reasons.md"), rejectReasons, allRuns);
        writeCompletion(reportDir.resolve("COMPLETION.md"), freezeBefore, freezeAfter, summary, hard);

        assertEquals(sourceBefore, sourceAfter, "V3 production sources must stay frozen");
        assertEquals(uploadsBefore, uploadsAfter, "real uploads must stay frozen");
        assertEquals("", abortReason, "eval aborted: " + abortReason + " after " + abortAfter);
    }

    private static List<RunPlan> interleavedPlans(List<JdSpec> jds) {
        List<RunPlan> plans = new ArrayList<>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (JdSpec jd : jds) {
                plans.add(new RunPlan(jd.id + "-R" + repeat, jd, repeat));
            }
        }
        return plans;
    }

    private static Map<String, Object> entryReport(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            RecordingGenerator generator,
            RecordingReviewer reviewer,
            V3BasicRunResult.V3EntryTrace trace,
            JdSpec jd) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("canonicalEntryKey", trace.canonicalEntryKey());
        entry.put("originalFullText", fullText(trace.originalBullets()));
        entry.put("clarityFullText", fullText(trace.clarityBullets()));
        entry.put("targetedFullText", fullText(trace.targetedBullets()));
        entry.put("hybrid1FullText", fullText(trace.hybrid1Bullets()));
        entry.put("hybrid2FullText", fullText(trace.hybrid2Bullets()));
        entry.put("finalFullText", fullText(trace.finalBullets()));
        entry.put("originalBullets", trace.originalBullets());
        entry.put("clarityBullets", trace.clarityBullets());
        entry.put("targetedBullets", trace.targetedBullets());
        entry.put("hybrid1Bullets", trace.hybrid1Bullets());
        entry.put("hybrid2Bullets", trace.hybrid2Bullets());
        entry.put("finalBullets", trace.finalBullets());
        entry.put("hybrid1NodeSources", nodeSources(
                trace.originalBullets(), trace.clarityBullets(), trace.targetedBullets(), trace.hybrid1Bullets()));
        entry.put("hybrid2NodeSources", nodeSources(
                trace.originalBullets(), trace.clarityBullets(), trace.targetedBullets(), trace.hybrid2Bullets()));
        entry.put("finalNodeSources", trace.nodeSources());

        Map<String, Object> originalScore = scoreMap(trace.originalScore());
        Map<String, Object> clarityScore = scoreMap(trace.clarityScore());
        Map<String, Object> targetedScore = scoreMap(trace.targetedScore());
        Map<String, Object> hybrid1Score = scoreMap(trace.hybrid1Score());
        Map<String, Object> hybrid2Score = scoreMap(trace.hybrid2Score());
        Map<String, Object> finalScore = scoreForChoice(trace);
        entry.put("originalScore", originalScore);
        entry.put("clarityScore", clarityScore);
        entry.put("targetedScore", targetedScore);
        entry.put("hybrid1Score", hybrid1Score);
        entry.put("hybrid2Score", hybrid2Score);
        entry.put("finalScore", finalScore);

        List<Map<String, Object>> materiality = materialityRows(graph);
        entry.put("materiality", materiality);
        entry.put("protectedFacts", trace.protectedFactKeys());
        entry.put("optionalFacts", trace.optionalFactKeys());
        entry.put("extractionNoise", trace.noiseFactKeys());

        V3FactCoverage.Result originalCov = coverage(graph, trace.originalBullets(), trace.originalBullets());
        V3FactCoverage.Result clarityCov = coverage(graph, trace.originalBullets(), trace.clarityBullets());
        V3FactCoverage.Result targetedCov = coverage(graph, trace.originalBullets(), trace.targetedBullets());
        V3FactCoverage.Result hybrid1Cov = coverage(graph, trace.originalBullets(), trace.hybrid1Bullets());
        V3FactCoverage.Result hybrid2Cov = coverage(graph, trace.originalBullets(), trace.hybrid2Bullets());
        V3FactCoverage.Result finalCov = coverage(graph, trace.originalBullets(), trace.finalBullets());
        entry.put("originalCoverage", coverageMap(originalCov));
        entry.put("clarityCoverage", coverageMap(clarityCov));
        entry.put("targetedCoverage", coverageMap(targetedCov));
        entry.put("hybrid1Coverage", coverageMap(hybrid1Cov));
        entry.put("hybrid2Coverage", coverageMap(hybrid2Cov));
        entry.put("finalCoverage", coverageMap(finalCov));
        entry.put("omittedProtectedFacts", finalCov.omitted());
        entry.put("omittedOptionalFacts", finalCov.omittedOptional());
        entry.put("extractionNoiseOmitted", finalCov.extractionNoise());
        entry.put("introducedFacts", finalCov.introduced());
        entry.put("duplicatedFacts", finalCov.duplicate());
        entry.put("invalidFactKeys", invalidKeys(graph, finalCov.used()));

        V3ValidationDecision originalGate = validate(graphs, graph, trace.originalBullets());
        V3ValidationDecision clarityGate = validate(graphs, graph, trace.clarityBullets());
        V3ValidationDecision targetedGate = validate(graphs, graph, trace.targetedBullets());
        V3ValidationDecision hybrid1Gate = validate(graphs, graph, trace.hybrid1Bullets());
        V3ValidationDecision hybrid2Gate = validate(graphs, graph, trace.hybrid2Bullets());
        V3ValidationDecision finalGate = validate(graphs, graph, trace.finalBullets());
        entry.put("originalValidator", gateMap(originalGate));
        entry.put("clarityValidator", gateMap(clarityGate));
        entry.put("targetedValidator", gateMap(targetedGate));
        entry.put("hybrid1Validator", gateMap(hybrid1Gate));
        entry.put("hybrid2Validator", gateMap(hybrid2Gate));
        entry.put("finalValidator", gateMap(finalGate));
        entry.put("validatorCode", trace.validatorCode());
        entry.put("crossEntryFacts", crossEntryDetail(finalGate));

        RecordingGenerator.Call repairCall = generator.first(trace.canonicalEntryKey(), true);
        int repairCount = generator.count(trace.canonicalEntryKey(), true);
        entry.put("repairOccurred", repairCount > 0);
        entry.put("repairCount", repairCount);
        entry.put("repairInputReason", trace.repairReason());
        entry.put("repairCallPresent", repairCall != null);

        V3ResumeLanguageReviewer.Choice recorded = reviewer.choice(trace.canonicalEntryKey());
        String parsed = recorded == null ? "ABSENT" : String.valueOf(recorded.selection());
        String rawReason = recorded == null ? "no-reviewer-choice" : redact(recorded.reason());
        boolean fallback = "reviewer-unmatched-local-score".equals(trace.noChangeReason())
                || "reviewer-unavailable-local-score".equals(trace.noChangeReason())
                || "missing-choice-original".equals(rawReason);
        entry.put("reviewerRawDesensitizedChoice", Map.of(
                "canonicalEntryKey", recorded == null ? "" : recorded.canonicalEntryKey(),
                "selection", parsed,
                "reason", rawReason
        ));
        entry.put("reviewerParsedEnum", parsed);
        entry.put("fallbackTriggered", fallback);
        entry.put("finalChoice", String.valueOf(trace.chosenVariant()));
        entry.put("decision", String.valueOf(trace.decision()));
        entry.put("noChangeReason", trace.noChangeReason());
        entry.put("promptVersion", trace.promptVersion());
        entry.put("model", trace.model());
        entry.put("latencyMs", trace.latencyMs());
        TokenSum generateTokens = generator.tokens(trace.canonicalEntryKey(), false);
        TokenSum repairTokens = generator.tokens(trace.canonicalEntryKey(), true);
        entry.put("generatePromptTokens", generateTokens.prompt);
        entry.put("generateCompletionTokens", generateTokens.completion);
        entry.put("repairPromptTokens", repairTokens.prompt);
        entry.put("repairCompletionTokens", repairTokens.completion);
        entry.put("reviewerTokens", "unknown");
        Integer generatePlusRepair = sumKnown(generateTokens.total(), repairTokens.total());
        entry.put("generatePlusRepairTokens", generatePlusRepair);
        entry.put("promptTokens", trace.promptTokens() == null ? "unknown" : trace.promptTokens());
        entry.put("completionTokens", trace.completionTokens() == null ? "unknown" : trace.completionTokens());

        entry.put("candidates", List.of(
                candidate("ORIGINAL", trace.originalBullets(), originalScore, originalGate, originalCov, true),
                candidate("CLARITY_FIRST", trace.clarityBullets(), clarityScore, clarityGate, clarityCov,
                        Boolean.TRUE.equals(clarityScore.get("admitted")) || meetsAdmission(clarityScore, originalScore)),
                candidate("JD_TARGETED", trace.targetedBullets(), targetedScore, targetedGate, targetedCov,
                        Boolean.TRUE.equals(targetedScore.get("admitted")) || meetsAdmission(targetedScore, originalScore)),
                candidate("HYBRID_1", trace.hybrid1Bullets(), hybrid1Score, hybrid1Gate, hybrid1Cov,
                        Boolean.TRUE.equals(hybrid1Score.get("admitted")) || meetsAdmission(hybrid1Score, originalScore)),
                candidate("HYBRID_2", trace.hybrid2Bullets(), hybrid2Score, hybrid2Gate, hybrid2Cov,
                        Boolean.TRUE.equals(hybrid2Score.get("admitted")) || meetsAdmission(hybrid2Score, originalScore))
        ));
        entry.put("languageFlagsFinal", languageFlags("FINAL", trace.finalBullets(), trace.originalBullets()));
        entry.put("languageFlagsAllVariants", List.of(
                Map.of("variant", "CLARITY_FIRST", "flags", languageFlags("CLARITY_FIRST", trace.clarityBullets(), trace.originalBullets())),
                Map.of("variant", "JD_TARGETED", "flags", languageFlags("JD_TARGETED", trace.targetedBullets(), trace.originalBullets())),
                Map.of("variant", "HYBRID_1", "flags", languageFlags("HYBRID_1", trace.hybrid1Bullets(), trace.originalBullets())),
                Map.of("variant", "HYBRID_2", "flags", languageFlags("HYBRID_2", trace.hybrid2Bullets(), trace.originalBullets()))
        ));
        entry.put("jdKeyRequirements", jd.keyRequirements);
        boolean rewritten = !sameText(trace.originalBullets(), trace.finalBullets())
                && !"ORIGINAL".equals(String.valueOf(trace.chosenVariant()))
                && !"REJECT_ALL_AI".equals(String.valueOf(trace.chosenVariant()));
        entry.put("rewritten", rewritten);
        entry.put("finalLanguageRegression", Boolean.TRUE.equals(finalScore.get("languageRegression")));
        entry.put("finalIdentityMutation", V3ValidationDecision.IDENTITY_MUTATION.equals(finalGate.reasonCode()) ? 1 : 0);
        entry.put("finalRoleEscalation", V3ValidationDecision.ROLE_ESCALATION.equals(finalGate.reasonCode()) ? 1 : 0);
        return entry;
    }

    private static Map<String, Object> candidate(
            String name,
            List<String> bullets,
            Map<String, Object> score,
            V3ValidationDecision gate,
            V3FactCoverage.Result coverage,
            boolean admittedGuess) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("variant", name);
        map.put("fullText", fullText(bullets));
        map.put("bullets", bullets);
        map.put("score", score);
        map.put("validator", gateMap(gate));
        map.put("coverage", coverageMap(coverage));
        map.put("admittedHeuristic", admittedGuess && gate.accepted() && !bullets.isEmpty());
        map.put("rejected", !gate.accepted());
        map.put("rejectReason", gate.accepted() ? "" : gate.reasonCode());
        return map;
    }

    private static boolean meetsAdmission(Map<String, Object> variant, Map<String, Object> original) {
        if (variant == null || original == null || variant.isEmpty() || original.isEmpty()) {
            return false;
        }
        if (Boolean.TRUE.equals(variant.get("languageRegression"))) {
            return false;
        }
        int total = intVal(variant.get("total"));
        int clarity = intVal(variant.get("clarityNaturalness"));
        int fact = intVal(variant.get("factIntegrity"));
        int originalTotal = intVal(original.get("total"));
        return total >= 75 && clarity >= 70 && fact >= 90 && total >= originalTotal + 8;
    }

    private static void accumulateHard(Map<String, Integer> hard, Map<String, Object> entry) {
        @SuppressWarnings("unchecked")
        Map<String, Object> finalGate = (Map<String, Object>) entry.get("finalValidator");
        String code = finalGate == null ? "" : String.valueOf(finalGate.get("reasonCode"));
        if (V3ValidationDecision.INVENTED_SKILL.equals(code)) {
            bump(hard, "hallucination");
        }
        if (V3ValidationDecision.INVENTED_CREDENTIAL.equals(code)) {
            bump(hard, "inventedCredential");
            bump(hard, "hallucination");
        }
        if (V3ValidationDecision.UNCONFIRMED_METRIC.equals(code)) {
            bump(hard, "inventedMetric");
        }
        if (V3ValidationDecision.CROSS_ENTRY_FACT.equals(code)) {
            bump(hard, "crossEntry");
        }
        if (V3ValidationDecision.MISSING_MATERIAL_FACT.equals(code)) {
            bump(hard, "missingProtectedFact");
        }
        if (V3ValidationDecision.DUPLICATE_MATERIAL_FACT.equals(code)) {
            bump(hard, "duplicateMaterialFact");
        }
        if (V3ValidationDecision.IDENTITY_MUTATION.equals(code)) {
            bump(hard, "identityMutation");
        }
        if (V3ValidationDecision.ROLE_ESCALATION.equals(code)) {
            bump(hard, "roleEscalation");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> finalCov = (Map<String, Object>) entry.get("finalCoverage");
        if (finalCov != null) {
            if (nonEmpty(finalCov.get("introduced")) && !V3ValidationDecision.INVENTED_SKILL.equals(code)
                    && !V3ValidationDecision.INVENTED_CREDENTIAL.equals(code)) {
                bump(hard, "hallucination");
            }
            if (nonEmpty(finalCov.get("omittedProtected")) && !V3ValidationDecision.MISSING_MATERIAL_FACT.equals(code)) {
                bump(hard, "missingProtectedFact");
            }
            if (nonEmpty(finalCov.get("duplicate")) && !V3ValidationDecision.DUPLICATE_MATERIAL_FACT.equals(code)) {
                bump(hard, "duplicateMaterialFact");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> invalid = (List<String>) entry.get("invalidFactKeys");
        if (invalid != null && !invalid.isEmpty()) {
            hard.put("invalidFactKey", hard.getOrDefault("invalidFactKey", 0) + invalid.size());
        }
        if (Boolean.TRUE.equals(entry.get("finalLanguageRegression"))) {
            bump(hard, "languageRegression");
        }
    }

    private static void accumulateRejects(Map<String, Integer> reasons, Map<String, Object> entry) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) entry.get("candidates");
        String finalChoice = String.valueOf(entry.get("finalChoice"));
        if (candidates == null) {
            return;
        }
        for (Map<String, Object> candidate : candidates) {
            String variant = String.valueOf(candidate.get("variant"));
            if (variant.equals(finalChoice) || "ORIGINAL".equals(variant)) {
                continue;
            }
            String reason = String.valueOf(candidate.get("rejectReason"));
            if (reason == null || reason.isBlank()) {
                if (!Boolean.TRUE.equals(candidate.get("admittedHeuristic"))) {
                    bump(reasons, "NOT_ADMITTED");
                }
                continue;
            }
            bump(reasons, reason);
        }
    }

    private static Map<String, Object> freezeSnapshot(
            String phase,
            String model,
            Map<String, String> uploads,
            Map<String, String> sources,
            Path pdf,
            List<JdSpec> jds) throws Exception {
        Map<String, Object> freeze = new LinkedHashMap<>();
        freeze.put("phase", phase);
        freeze.put("recordedAtUtc", Instant.now().toString());
        freeze.put("head", git("rev-parse", "HEAD").strip());
        freeze.put("branch", git("rev-parse", "--abbrev-ref", "HEAD").strip());
        freeze.put("gitStatusShort", git("status", "--short"));
        freeze.put("promptVersion", PROMPT_VERSION);
        freeze.put("model", model);
        freeze.put("thresholds", thresholds());
        freeze.put("engine", "V3 BASIC");
        freeze.put("resume", "AlexMorgan PDF via GeneratedResumePdfFixture");
        freeze.put("pdfSha256", sha256(pdf));
        freeze.put("pdfPath", pdf.toString().replace('\\', '/'));
        Map<String, String> jdHashes = new LinkedHashMap<>();
        for (JdSpec jd : jds) {
            jdHashes.put(jd.name, jd.textSha256);
        }
        freeze.put("jdTextSha256", jdHashes);
        freeze.put("v3ProductionSourceSha256", sources);
        freeze.put("v3ProductionSourceCount", sources.size());
        freeze.put("uploadsSha256", uploads);
        freeze.put("uploadsCount", uploads.size());
        freeze.put("writerConstraint", "@TempDir only; real uploads forbidden");
        freeze.put("noCommit", true);
        freeze.put("noV33", true);
        freeze.put("noV2Change", true);
        return freeze;
    }

    private static Map<String, Object> thresholds() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", 75);
        map.put("clarity", 70);
        map.put("fact", 90);
        map.put("improvementMargin", 8);
        map.put("weights", Map.of(
                "fact", 25, "jd", 20, "uplift", 20, "clarity", 15, "specificity", 10, "concision", 10));
        return map;
    }

    private static Map<String, Integer> zeroHard() {
        Map<String, Integer> hard = new LinkedHashMap<>();
        hard.put("hallucination", 0);
        hard.put("crossEntry", 0);
        hard.put("missingProtectedFact", 0);
        hard.put("duplicateMaterialFact", 0);
        hard.put("invalidFactKey", 0);
        hard.put("languageRegression", 0);
        hard.put("identityMutation", 0);
        hard.put("roleEscalation", 0);
        hard.put("inventedCredential", 0);
        hard.put("inventedMetric", 0);
        return hard;
    }

    private static Map<String, Integer> zeroWins() {
        Map<String, Integer> wins = new LinkedHashMap<>();
        wins.put("ORIGINAL", 0);
        wins.put("CLARITY_FIRST", 0);
        wins.put("JD_TARGETED", 0);
        wins.put("HYBRID_1", 0);
        wins.put("HYBRID_2", 0);
        wins.put("REJECT_ALL_AI", 0);
        wins.put("OTHER", 0);
        return wins;
    }

    private static String winBucket(String chosen) {
        if (zeroWins().containsKey(chosen)) {
            return chosen;
        }
        return "OTHER";
    }

    private static V3FactCoverage.Result coverage(V3EntryFactGraph graph, List<String> original, List<String> rewritten) {
        if (graph == null || rewritten == null || rewritten.isEmpty()) {
            return V3FactCoverage.Result.empty();
        }
        return V3FactCoverage.compute(graph, original, rewritten);
    }

    private static V3ValidationDecision validate(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            List<String> bullets) {
        if (graph == null) {
            return V3ValidationDecision.reject(V3ValidationDecision.IDENTITY_MUTATION, "missing graph");
        }
        if (bullets == null || bullets.isEmpty()) {
            return V3ValidationDecision.reject("NOT_BUILT", "variant produced no bullets");
        }
        return V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                graph.canonicalEntryKey(),
                graph.originalHeading(),
                graph.originalDateText(),
                bullets));
    }

    private static Map<String, Object> gateMap(V3ValidationDecision decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accepted", decision.accepted());
        map.put("reasonCode", decision.reasonCode());
        map.put("detail", decision.detail());
        return map;
    }

    private static List<String> crossEntryDetail(V3ValidationDecision gate) {
        if (!V3ValidationDecision.CROSS_ENTRY_FACT.equals(gate.reasonCode())) {
            return List.of();
        }
        return gate.detail() == null || gate.detail().isBlank() ? List.of(gate.reasonCode()) : List.of(gate.detail());
    }

    private static Map<String, Object> coverageMap(V3FactCoverage.Result coverage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("used", coverage.used());
        map.put("retained", coverage.retained());
        map.put("moved", coverage.moved());
        map.put("omittedProtected", coverage.omitted());
        map.put("omittedOptional", coverage.omittedOptional());
        map.put("extractionNoise", coverage.extractionNoise());
        map.put("introduced", coverage.introduced());
        map.put("duplicate", coverage.duplicate());
        map.put("supportingSourceNodeIds", coverage.supportingSourceNodeIds());
        return map;
    }

    private static List<Map<String, Object>> materialityRows(V3EntryFactGraph graph) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (graph == null) {
            return rows;
        }
        for (V3Fact fact : graph.facts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factKey", fact.factKey());
            row.put("surface", fact.surface());
            row.put("kind", String.valueOf(fact.factType()));
            row.put("materiality", String.valueOf(fact.materiality()));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> invalidKeys(V3EntryFactGraph graph, List<String> used) {
        List<String> invalid = new ArrayList<>();
        if (graph == null || used == null) {
            return invalid;
        }
        for (String key : used) {
            if (key != null && !key.isBlank() && !graph.hasFactKey(key)) {
                invalid.add(key);
            }
        }
        return invalid;
    }

    private static Map<String, Object> scoreMap(V3QualityScore score) {
        if (score == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("factIntegrity", score.factIntegrity());
        map.put("jdRelevance", score.jdRelevance());
        map.put("materialUplift", score.materialUplift());
        map.put("clarityNaturalness", score.clarityNaturalness());
        map.put("specificity", score.specificity());
        map.put("concision", score.concision());
        map.put("total", score.total());
        map.put("languageRegression", score.languageRegression());
        map.put("admitted", score.admitted());
        map.put("meetsAdmission", false);
        map.put("notes", score.notes());
        return map;
    }

    private static Map<String, Object> scoreForChoice(V3BasicRunResult.V3EntryTrace trace) {
        String chosen = String.valueOf(trace.chosenVariant());
        return switch (chosen) {
            case "CLARITY_FIRST" -> scoreMap(trace.clarityScore());
            case "JD_TARGETED" -> scoreMap(trace.targetedScore());
            case "HYBRID_1" -> scoreMap(trace.hybrid1Score());
            case "HYBRID_2" -> scoreMap(trace.hybrid2Score());
            default -> scoreMap(trace.originalScore());
        };
    }

    private static List<Map<String, String>> languageFlags(String variant, List<String> bullets, List<String> original) {
        List<Map<String, String>> flags = new ArrayList<>();
        if (bullets == null || bullets.isEmpty()) {
            return flags;
        }
        String text = fullText(bullets);
        String orig = fullText(original);
        addFlag(flags, variant, "using_java_spring_boot", text,
                Pattern.compile("(?i)\\b(?:using|in|with)\\s+java\\s+spring(?:\\s+boot)?\\b"));
        addFlag(flags, variant, "executed_the_deployment", text,
                Pattern.compile("(?i)\\bexecuted the deployment\\b"));
        addFlag(flags, variant, "awkward_nominalization", text,
                Pattern.compile("(?i)\\b(?:executed|performed|carried out)\\s+the\\s+(?:deployment|integration|implementation|development|creation)\\b|\\bthe\\s+(?:development|implementation|deployment|integration)\\s+of\\b"));
        addFlag(flags, variant, "empty_efficiency_performance_architecture", text,
                Pattern.compile("(?i)\\b(?:enhanced efficiency|improved performance|structured architecture|optimized workflows|architecture value|increased efficiency)\\b"));
        addFlag(flags, variant, "keyword_stacking", text,
                Pattern.compile("(?i)\\bjava\\s+spring(?:\\s+boot)?(?:\\s+(?:maven|rest(?:ful)?|sql|mysql|aws|docker|kubernetes)){2,}"));
        addFlag(flags, variant, "unnatural_stack_join", text,
                Pattern.compile("(?i)\\bjava\\s+spring\\s+boot\\b|\\bonto\\s+aws\\b|\\butilizing\\b"));
        addFlag(flags, variant, "unnecessary_synonym_swap", text,
                Pattern.compile("(?i)\\b(?:utilizing|leveraged|facilitated|spearheaded)\\b"));
        addFlag(flags, variant, "unprofessional_or_unnatural", text,
                Pattern.compile("(?i)\\b(?:stuff|things|a lot of|very very|kinda|gonna)\\b"));
        if (hasTenseMix(bullets)) {
            flags.add(flag(variant, "tense_inconsistency", text));
        }
        if (hasRedundantDuplicate(bullets)) {
            flags.add(flag(variant, "redundant_repetition", text));
        }
        if (!orig.isBlank() && text.toLowerCase(Locale.ROOT).contains("utilizing")
                && orig.toLowerCase(Locale.ROOT).contains("using")
                && !orig.toLowerCase(Locale.ROOT).contains("utilizing")) {
            flags.add(flag(variant, "unnecessary_synonym_swap", "original used 'using'; variant uses 'utilizing'"));
        }
        return flags;
    }

    private static void addFlag(
            List<Map<String, String>> flags,
            String variant,
            String code,
            String text,
            Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            flags.add(flag(variant, code, matcher.group()));
        }
    }

    private static Map<String, String> flag(String variant, String code, String text) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("variant", variant);
        map.put("code", code);
        map.put("text", text);
        return map;
    }

    private static boolean hasTenseMix(List<String> bullets) {
        boolean past = false;
        boolean present = false;
        Pattern pastP = Pattern.compile("(?i)^\\s*(?:Led|Developed|Implemented|Built|Created|Designed|Deployed|Optimized)\\b");
        Pattern presentP = Pattern.compile("(?i)^\\s*(?:Lead|Develop|Implement|Build|Create|Design|Deploy|Optimize)s?\\b");
        for (String bullet : bullets) {
            if (pastP.matcher(bullet).find()) {
                past = true;
            }
            if (presentP.matcher(bullet).find()) {
                present = true;
            }
        }
        return past && present;
    }

    private static boolean hasRedundantDuplicate(List<String> bullets) {
        for (int i = 0; i < bullets.size(); i++) {
            String a = collapse(bullets.get(i));
            for (int j = i + 1; j < bullets.size(); j++) {
                String b = collapse(bullets.get(j));
                if (!a.isBlank() && a.equals(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String collapse(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static void writeTechnicalMarkdown(
            Path path,
            List<JdSpec> jds,
            List<Map<String, Object>> runs,
            Map<String, Object> summary,
            Map<String, Integer> hard) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 frozen nine-round technical report\n\n");
        md.append("Cursor did not fill human ACCEPT rates and does not claim industry average.\n\n");
        md.append("- promptVersion: `").append(summary.get("promptVersion")).append("`\n");
        md.append("- model: `").append(summary.get("model")).append("`\n");
        md.append("- runs completed: ").append(summary.get("runsCompleted")).append(" / 9\n");
        md.append("- generateCalls: ").append(summary.get("generateCalls")).append('\n');
        md.append("- repairCalls: ").append(summary.get("repairCalls")).append('\n');
        md.append("- languageReviewCalls: ").append(summary.get("languageReviewCalls")).append('\n');
        md.append("- writerCalls: ").append(summary.get("writerCalls")).append('\n');
        md.append("- repairRate: ").append(summary.get("repairRate")).append('\n');
        md.append("- fallbackRate: ").append(summary.get("fallbackRate")).append('\n');
        md.append("- wins: ").append(summary.get("wins")).append('\n');
        md.append("- nonOriginalEntries: ").append(summary.get("nonOriginalEntries")).append('\n');
        md.append("- nonOriginalRounds: ").append(summary.get("nonOriginalRounds")).append('\n');
        md.append("- FINAL hard metrics: ").append(hard).append("\n\n");
        md.append("## JD sources\n\n");
        for (JdSpec jd : jds) {
            md.append("### ").append(jd.id).append(" / ").append(jd.name).append('\n');
            md.append("- source: ").append(jd.source).append('\n');
            md.append("- sha256: `").append(jd.textSha256).append("`\n\n");
        }
        md.append("## Per-run\n\n");
        for (Map<String, Object> run : runs) {
            md.append("## ").append(run.get("runId")).append('\n');
            md.append("- generateCalls=").append(run.get("generateCalls"));
            md.append(" repairCalls=").append(run.get("repairCalls"));
            md.append(" reviewCalls=").append(run.get("languageReviewCalls"));
            md.append(" writerCalls=").append(run.get("writerCalls"));
            md.append(" wallMs=").append(run.get("wallLatencyMs")).append('\n');
            md.append("- retries: ").append(run.get("transientRetries")).append('\n');
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("\n### ").append(entry.get("canonicalEntryKey")).append('\n');
                md.append("- finalChoice: ").append(entry.get("finalChoice")).append('\n');
                md.append("- reviewerParsedEnum: ").append(entry.get("reviewerParsedEnum")).append('\n');
                md.append("- fallbackTriggered: ").append(entry.get("fallbackTriggered")).append('\n');
                md.append("- repairCount: ").append(entry.get("repairCount"));
                md.append(" reason: ").append(entry.get("repairInputReason")).append('\n');
                md.append("- finalValidator: ").append(entry.get("finalValidator")).append('\n');
                md.append("- hybrid1NodeSources: ").append(entry.get("hybrid1NodeSources")).append('\n');
                md.append("- hybrid2NodeSources: ").append(entry.get("hybrid2NodeSources")).append('\n');
                md.append("- languageFlagsFinal: ").append(entry.get("languageFlagsFinal")).append("\n\n");
                md.append("**ORIGINAL** ").append(entry.get("originalScore")).append("\n\n");
                md.append(entry.get("originalFullText")).append("\n\n");
                md.append("**CLARITY_FIRST** ").append(entry.get("clarityScore")).append("\n\n");
                md.append(entry.get("clarityFullText")).append("\n\n");
                md.append("**JD_TARGETED** ").append(entry.get("targetedScore")).append("\n\n");
                md.append(entry.get("targetedFullText")).append("\n\n");
                md.append("**HYBRID_1** ").append(entry.get("hybrid1Score")).append("\n\n");
                md.append(entry.get("hybrid1FullText")).append("\n\n");
                md.append("**HYBRID_2** ").append(entry.get("hybrid2Score")).append("\n\n");
                md.append(entry.get("hybrid2FullText")).append("\n\n");
                md.append("**FINAL** ").append(entry.get("finalScore")).append("\n\n");
                md.append(entry.get("finalFullText")).append("\n\n");
            }
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void writeFinalsMarkdown(Path path, List<Map<String, Object>> runs) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 nine-run FINAL text\n\n");
        for (Map<String, Object> run : runs) {
            md.append("## ").append(run.get("runId")).append(" — ").append(run.get("jdName")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("### ").append(entry.get("canonicalEntryKey")).append('\n');
                md.append("- FINAL choice: `").append(entry.get("finalChoice")).append("`\n\n");
                bullets(md, entry.get("finalBullets"));
            }
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> writeBlindReview(
            Path reportDir,
            List<Map<String, Object>> runs,
            List<JdSpec> jds) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 blind human review\n\n");
        md.append("Do not look at `blind-key.json` before scoring.\n");
        md.append("Candidate type, model scores, reviewer choice, and validator results are hidden.\n");
        md.append("Cursor did **not** fill ACCEPT_AS_IS / MINOR_EDIT / REJECT or the 1–5 ratings.\n\n");
        md.append("Entry-level and bullet-level tables are separate. Leave labels blank.\n\n");

        Map<String, JdSpec> jdById = new LinkedHashMap<>();
        for (JdSpec jd : jds) {
            jdById.put(jd.id, jd);
        }
        List<Map<String, Object>> keyItems = new ArrayList<>();
        List<Map<String, Object>> keyBullets = new ArrayList<>();
        int entryN = 1;
        int bulletN = 1;
        md.append("## Entry-level pairs\n\n");
        for (Map<String, Object> run : runs) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            JdSpec jd = jdById.get(String.valueOf(run.get("jdId")));
            for (Map<String, Object> entry : entries) {
                if (!Boolean.TRUE.equals(entry.get("rewritten"))) {
                    continue;
                }
                String itemId = String.format(Locale.ROOT, "E%02d", entryN++);
                boolean originalIsA = Math.floorMod((run.get("runId") + ":" + entry.get("canonicalEntryKey")).hashCode(), 2) == 0;
                String versionA = originalIsA ? fullText(entry.get("originalBullets")) : fullText(entry.get("finalBullets"));
                String versionB = originalIsA ? fullText(entry.get("finalBullets")) : fullText(entry.get("originalBullets"));
                md.append("### ").append(itemId).append('\n');
                md.append("JD group: ").append(run.get("jdId")).append(" (").append(run.get("jdName")).append(")\n\n");
                md.append("**JD key requirements**\n\n");
                bullets(md, jd == null ? List.of() : jd.keyRequirements);
                md.append("**Version A**\n\n");
                md.append(versionA).append("\n\n");
                md.append("**Version B**\n\n");
                md.append(versionB).append("\n\n");
                md.append("- ACCEPT_AS_IS:\n- MINOR_EDIT:\n- REJECT:\n");
                md.append("- 自然度 1–5:\n- JD 相关性 1–5:\n- 事实准确 PASS/FAIL:\n- 评语:\n\n");
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("itemId", itemId);
                key.put("runId", run.get("runId"));
                key.put("canonicalEntryKey", entry.get("canonicalEntryKey"));
                key.put("versionA", originalIsA ? "ORIGINAL" : "FINAL");
                key.put("versionB", originalIsA ? "FINAL" : "ORIGINAL");
                key.put("finalChoice", entry.get("finalChoice"));
                keyItems.add(key);

                @SuppressWarnings("unchecked")
                List<String> original = (List<String>) entry.get("originalBullets");
                @SuppressWarnings("unchecked")
                List<String> finals = (List<String>) entry.get("finalBullets");
                int n = Math.max(original == null ? 0 : original.size(), finals == null ? 0 : finals.size());
                for (int i = 0; i < n; i++) {
                    String ob = at(original, i);
                    String fb = at(finals, i);
                    if (ob.equals(fb)) {
                        continue;
                    }
                    String bulletId = String.format(Locale.ROOT, "B%02d", bulletN++);
                    boolean origA = Math.floorMod((itemId + ":" + i).hashCode(), 2) == 0;
                    md.append("#### Bullet ").append(bulletId).append(" (of ").append(itemId).append(")\n\n");
                    md.append("**Version A**\n\n- ").append(origA ? ob : fb).append("\n\n");
                    md.append("**Version B**\n\n- ").append(origA ? fb : ob).append("\n\n");
                    md.append("- ACCEPT_AS_IS:\n- MINOR_EDIT:\n- REJECT:\n");
                    md.append("- 自然度 1–5:\n- JD 相关性 1–5:\n- 事实准确 PASS/FAIL:\n- 评语:\n\n");
                    Map<String, Object> bkey = new LinkedHashMap<>();
                    bkey.put("itemId", bulletId);
                    bkey.put("parentEntryItemId", itemId);
                    bkey.put("runId", run.get("runId"));
                    bkey.put("canonicalEntryKey", entry.get("canonicalEntryKey"));
                    bkey.put("bulletIndex", i);
                    bkey.put("versionA", origA ? "ORIGINAL" : "FINAL");
                    bkey.put("versionB", origA ? "FINAL" : "ORIGINAL");
                    keyBullets.add(bkey);
                }
            }
        }
        if (entryN == 1) {
            md.append("_No rewritten entries in this eval. Blind pairs were not generated._\n");
        }
        Files.writeString(reportDir.resolve("blind-review.md"), md.toString(), StandardCharsets.UTF_8);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("note", "Hidden from human reviewers. Do not paste into blind-review.md.");
        key.put("entryItems", keyItems);
        key.put("bulletItems", keyBullets);
        return key;
    }

    private static void writeHumanRates(Path path, Map<String, Object> summary) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 human rates — blank until a person fills them\n\n");
        md.append("Cursor must not fill ACCEPT_AS_IS / MINOR_EDIT / REJECT or claim industry average.\n\n");
        md.append("## Denominators (from automatic eval)\n\n");
        md.append("| Denominator | Count |\n| --- | ---: |\n");
        md.append("| rewritten entry | ").append(summary.get("rewrittenEntries")).append(" |\n");
        md.append("| rewritten bullet | ").append(summary.get("rewrittenBullets")).append(" |\n");
        md.append("| eligible entry | ").append(summary.get("eligibleEntries")).append(" |\n");
        md.append("| complete runs | ").append(summary.get("completeRuns")).append(" |\n\n");
        md.append("## Entry-level (rewritten entries only)\n\n");
        md.append("- ACCEPT_AS_IS: __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- MINOR_EDIT: __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- REJECT: __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- ACCEPT_AS_IS + MINOR_EDIT: __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- 自然语言合格率 (自然度 ≥ 4): __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- JD 相关性合格率 (JD 相关性 ≥ 4): __ / ").append(summary.get("rewrittenEntries")).append('\n');
        md.append("- 事实硬错误: __\n\n");
        md.append("## Bullet-level (rewritten bullets only)\n\n");
        md.append("- ACCEPT_AS_IS: __ / ").append(summary.get("rewrittenBullets")).append('\n');
        md.append("- MINOR_EDIT: __ / ").append(summary.get("rewrittenBullets")).append('\n');
        md.append("- REJECT: __ / ").append(summary.get("rewrittenBullets")).append('\n');
        md.append("- 自然语言合格率: __ / ").append(summary.get("rewrittenBullets")).append('\n');
        md.append("- JD 相关性合格率: __ / ").append(summary.get("rewrittenBullets")).append('\n');
        md.append("- 事实硬错误: __\n\n");
        md.append("Provisional human targets (not scored by Cursor): ACCEPT_AS_IS ≥ 70%; ");
        md.append("ACCEPT_AS_IS + MINOR_EDIT ≥ 90%; natural language ≥ 90%; JD relevance ≥ 80%; fact hard errors = 0.\n");
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void writeRejectReasons(
            Path path,
            Map<String, Integer> reasons,
            List<Map<String, Object>> runs) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 rejected-candidate reason distribution\n\n");
        md.append("These counts are for **non-FINAL** AI candidates. FINAL=ORIGINAL does not delete them.\n\n");
        md.append("| reason | count |\n| --- | ---: |\n");
        if (reasons.isEmpty()) {
            md.append("| _(none)_ | 0 |\n");
        } else {
            for (Map.Entry<String, Integer> row : reasons.entrySet()) {
                md.append("| `").append(row.getKey()).append("` | ").append(row.getValue()).append(" |\n");
            }
        }
        md.append("\n## Per-run rejected variants\n\n");
        for (Map<String, Object> run : runs) {
            md.append("### ").append(run.get("runId")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("- ").append(entry.get("canonicalEntryKey"));
                md.append(" FINAL=").append(entry.get("finalChoice")).append('\n');
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) entry.get("candidates");
                if (candidates == null) {
                    continue;
                }
                for (Map<String, Object> candidate : candidates) {
                    if ("ORIGINAL".equals(candidate.get("variant"))) {
                        continue;
                    }
                    md.append("  - ").append(candidate.get("variant"));
                    md.append(" rejected=").append(candidate.get("rejected"));
                    md.append(" reason=").append(candidate.get("rejectReason"));
                    md.append(" admittedHeuristic=").append(candidate.get("admittedHeuristic")).append('\n');
                }
            }
            md.append('\n');
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void writeCompletion(
            Path path,
            Map<String, Object> freezeBefore,
            Map<String, Object> freezeAfter,
            Map<String, Object> summary,
            Map<String, Integer> hard) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 frozen nine-round eval — completion\n\n");
        md.append("Eval finished. **Stopped.** Production code was not changed by this harness. ");
        md.append("V3-3 was not started. V2 was not modified. This report does **not** claim industry-average quality.\n\n");
        md.append("Cursor did **not** fill human ACCEPT_AS_IS / MINOR_EDIT / REJECT.\n\n");
        md.append("## Freeze\n\n");
        md.append("| Item | Before | After |\n| --- | --- | --- |\n");
        md.append("| Branch | `").append(freezeBefore.get("branch")).append("` | `")
                .append(freezeAfter.get("branch")).append("` |\n");
        md.append("| HEAD | `").append(freezeBefore.get("head")).append("` | `")
                .append(freezeAfter.get("head")).append("` |\n");
        md.append("| promptVersion | `").append(freezeBefore.get("promptVersion")).append("` | same |\n");
        md.append("| model | `").append(freezeBefore.get("model")).append("` | same |\n");
        md.append("| source SHA match | see freeze-before.json | ")
                .append(summary.get("sourceShaUnchanged")).append(" |\n");
        md.append("| uploads unchanged | ").append(summary.get("uploadsUnchanged")).append(" | ")
                .append(summary.get("uploadsCompare")).append(" |\n\n");
        md.append("## Objective automatic result\n\n");
        md.append("- runs completed: ").append(summary.get("runsCompleted")).append(" / 9\n");
        md.append("- abortReason: ").append(empty(summary.get("abortReason"))).append('\n');
        md.append("- FINAL hard metrics: ").append(hard).append('\n');
        md.append("- nonOriginalRounds: ").append(summary.get("nonOriginalRounds")).append(" (need ≥ 8)\n");
        md.append("- objectivePass: ").append(summary.get("objectivePass")).append('\n');
        md.append("- hardPass: ").append(summary.get("hardPass")).append('\n');
        md.append("- V2 freeze: ").append(summary.get("v2Freeze")).append('\n');
        md.append("- industry average: not claimed; waiting for human blind review\n\n");
        md.append("SmartPC ORIGINAL is not an automatic failure unless a human finds the AI clearly better.\n\n");
        md.append("## Next\n\n");
        md.append("Post-eval offline `mvn test` and V2 freeze run after this file is written. No commit.\n");
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void bullets(StringBuilder md, Object value) {
        List<String> list = asStringList(value);
        for (String bullet : list) {
            md.append("- ").append(bullet).append('\n');
        }
        md.append('\n');
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> items) {
            List<String> out = new ArrayList<>();
            for (Object item : items) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }

    private static String fullText(Object value) {
        return String.join("\n", asStringList(value));
    }

    private static boolean sameText(List<String> a, List<String> b) {
        return fullText(a).equals(fullText(b));
    }

    private static String at(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index);
    }

    private static int countRewrittenEntries(List<Map<String, Object>> runs) {
        int n = 0;
        for (Map<String, Object> run : runs) {
            for (Map<String, Object> entry : entriesOf(run)) {
                if (Boolean.TRUE.equals(entry.get("rewritten"))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int countRewrittenBullets(List<Map<String, Object>> runs) {
        int n = 0;
        for (Map<String, Object> run : runs) {
            for (Map<String, Object> entry : entriesOf(run)) {
                if (!Boolean.TRUE.equals(entry.get("rewritten"))) {
                    continue;
                }
                List<String> original = asStringList(entry.get("originalBullets"));
                List<String> finals = asStringList(entry.get("finalBullets"));
                int size = Math.max(original.size(), finals.size());
                for (int i = 0; i < size; i++) {
                    if (!at(original, i).equals(at(finals, i))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> run) {
        Object value = run.get("entries");
        if (value instanceof List<?> items) {
            return (List<Map<String, Object>>) items;
        }
        return List.of();
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static double rate(int num, int den) {
        if (den <= 0) {
            return 0.0;
        }
        return Math.round(num * 1000.0 / den) / 1000.0;
    }

    private static boolean nonEmpty(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private static int intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Integer sumKnown(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
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
                || lower.contains("too many requests")
                || lower.contains("temporar");
    }

    private static Map<String, Object> retryRecord(Throwable error, String phase) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", phase);
        map.put("atUtc", Instant.now().toString());
        map.put("exception", error.getClass().getSimpleName());
        String message = String.valueOf(error.getMessage());
        map.put("reason", redact(message.length() > 240 ? message.substring(0, 240) : message));
        return map;
    }

    private static List<String> nodeSources(
            List<String> original,
            List<String> clarity,
            List<String> targeted,
            List<String> hybrid) {
        List<String> sources = new ArrayList<>();
        if (hybrid == null || hybrid.isEmpty()) {
            return sources;
        }
        for (int i = 0; i < hybrid.size(); i++) {
            String text = normalize(hybrid.get(i));
            if (text.equals(normalize(at(original, i)))) {
                sources.add("ORIGINAL");
            } else if (text.equals(normalize(at(clarity, i)))) {
                sources.add("CLARITY_FIRST");
            } else if (text.equals(normalize(at(targeted, i)))) {
                sources.add("JD_TARGETED");
            } else {
                sources.add("UNKNOWN");
            }
        }
        return sources;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    private static String redact(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.replaceAll("(?i)sk-[A-Za-z0-9\\-_]+", "[redacted]");
    }

    private static String empty(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? "(none)" : text;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static Map<String, Object> compareUploads(Map<String, String> before, Map<String, String> after) {
        Map<String, Object> compare = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> locked = new ArrayList<>();
        for (String key : after.keySet()) {
            if (!before.containsKey(key)) {
                added.add(key);
            } else if (!before.get(key).equals(after.get(key))) {
                changed.add(key);
            }
            if (String.valueOf(after.get(key)).startsWith("LOCKED:")) {
                locked.add(key);
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                deleted.add(key);
            }
        }
        compare.put("added", added.size());
        compare.put("deleted", deleted.size());
        compare.put("changed", changed.size());
        compare.put("locked", locked.size());
        compare.put("addedPaths", added);
        compare.put("deletedPaths", deleted);
        compare.put("changedPaths", changed);
        compare.put("lockedPaths", locked);
        compare.put("beforeCount", before.size());
        compare.put("afterCount", after.size());
        return compare;
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
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'),
                            "LOCKED:" + ex.getClass().getSimpleName());
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
                    hashes.put(root.relativize(file).toString().replace('\\', '/'),
                            "ERROR:" + ex.getClass().getSimpleName());
                }
            });
        }
        return hashes;
    }

    private static void writeJson(Path path, Object value) throws Exception {
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), value);
    }

    private static String sha256(String text) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(Path file) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(Path.of(".").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private static String gitExecutable() {
        Path windows = Path.of("C:\\Program Files\\Git\\cmd\\git.exe");
        if (Files.isRegularFile(windows)) {
            return windows.toString();
        }
        return "git";
    }

    private record RunPlan(String runId, JdSpec jd, int repeat) {
    }

    private static final class JdSpec {
        private final String id;
        private final String name;
        private final String title;
        private final String source;
        private final String text;
        private final String textSha256;
        private final List<String> keyRequirements;

        private JdSpec(String id, String name, String title, String source, String text) {
            this.id = id;
            this.name = name;
            this.title = title;
            this.source = source;
            this.text = text;
            try {
                this.textSha256 = sha256(text);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            this.keyRequirements = extractRequirements(text);
        }
    }

    private static List<String> extractRequirements(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("- ")) {
                lines.add(stripped.substring(2).strip());
            } else if (!stripped.isBlank()
                    && !stripped.endsWith(":")
                    && !stripped.equals("Junior Java Backend Developer")
                    && stripped.length() > 8) {
                lines.add(stripped);
            }
        }
        return List.copyOf(lines);
    }

    private static final class RecordingGenerator implements V3EntryRewriteGenerator {
        private final V3EntryRewriteGenerator delegate;
        private final List<Call> calls = new ArrayList<>();
        private final AtomicInteger count = new AtomicInteger();

        private RecordingGenerator(V3EntryRewriteGenerator delegate) {
            this.delegate = delegate;
        }

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            count.incrementAndGet();
            V3EntryRewriteResult result = delegate.generate(request);
            calls.add(new Call(request.canonicalEntryKey(), request.repair(), result));
            return result;
        }

        private Call first(String key, boolean repair) {
            for (Call call : calls) {
                if (key.equals(call.key) && call.repair == repair) {
                    return call;
                }
            }
            return null;
        }

        private int count(String key, boolean repair) {
            int n = 0;
            for (Call call : calls) {
                if (key.equals(call.key) && call.repair == repair) {
                    n++;
                }
            }
            return n;
        }

        private TokenSum tokens(String key, boolean repair) {
            Integer prompt = null;
            Integer completion = null;
            for (Call call : calls) {
                if (!key.equals(call.key) || call.repair != repair || call.result == null) {
                    continue;
                }
                Integer p = call.result.telemetry().promptTokens();
                Integer c = call.result.telemetry().completionTokens();
                if (p != null) {
                    prompt = (prompt == null ? 0 : prompt) + p;
                }
                if (c != null) {
                    completion = (completion == null ? 0 : completion) + c;
                }
            }
            return new TokenSum(prompt, completion);
        }

        private record Call(String key, boolean repair, V3EntryRewriteResult result) {
        }
    }

    private record TokenSum(Integer prompt, Integer completion) {
        private Integer total() {
            if (prompt == null && completion == null) {
                return null;
            }
            return (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
        }
    }

    private static final class RecordingReviewer implements V3ResumeLanguageReviewer {
        private final V3ResumeLanguageReviewer delegate;
        private final List<Choice> choices = new ArrayList<>();

        private RecordingReviewer(V3ResumeLanguageReviewer delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Choice> review(String jobDescription, List<EntryComparison> entries) {
            List<Choice> result = delegate.review(jobDescription, entries);
            choices.clear();
            if (result != null) {
                choices.addAll(result);
            }
            return result;
        }

        private Choice choice(String key) {
            for (Choice item : choices) {
                if (key.equals(item.canonicalEntryKey())) {
                    return item;
                }
            }
            return null;
        }
    }
}
