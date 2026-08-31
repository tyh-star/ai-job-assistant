package aijob.tailor.v3;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.ClaimLedger;
import aijob.util.YaohangCanonicalDocxFixture;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V3-2.3 one-run live smoke. Does not start Spring Boot, call JSearch, write
 * the real uploads tree, or enter V3-3. Enable with {@code AIJOB_V3_23_SMOKE=true}.
 */
class V3BasicLiveSmokeV323Test {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "AIJOB_V3_23_SMOKE", matches = "true")
    void oneCurrentJdRunWritesReportsWithoutTouchingUploads() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.toLowerCase(Locale.ROOT).contains("placeholder"), "placeholder key");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");

        Path reportDir = Path.of("docs", "v3-2.3-smoke");
        Files.createDirectories(reportDir);
        Path uploadsRoot = Path.of("uploads").toAbsolutePath().normalize();
        Map<String, String> uploadsBefore = snapshotUploads(uploadsRoot);

        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, tempDir.resolve("yaohang.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);

        RecordingGenerator generator = new RecordingGenerator(
                new OpenAiV3EntryRewriteGenerator(OpenAIOkHttpClient.builder().apiKey(apiKey).build(), model));
        OpenAiV3ResumeLanguageReviewer reviewer = new OpenAiV3ResumeLanguageReviewer(
                OpenAIOkHttpClient.builder().apiKey(apiKey).build(), model);
        V3BasicTailorService service = new V3BasicTailorService(generator, reviewer);

        long started = System.nanoTime();
        V3BasicRunResult result = service.run(nodes, V3BasicLiveQualityAcceptanceTest.JD_BACKEND);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;

        int writerCalls = 0;
        String writerName = "";
        if (!result.changes().isEmpty()) {
            Path writerRoot = tempDir.resolve("writer-v323");
            Path writerOut = new ResumeDocxTailorServiceImpl(
                    new ResumeDocumentServiceImpl(),
                    StoragePathResolver.forRoot(writerRoot))
                    .createTailoredDocx(canonical, result.changes(), 1L, 10L);
            writerCalls = 1;
            writerName = writerOut.getFileName().toString();
            assertFalse(writerOut.toAbsolutePath().normalize().startsWith(uploadsRoot),
                    "Writer leaked into uploads");
        }

        Map<String, String> uploadsAfter = snapshotUploads(uploadsRoot);
        assertEquals(uploadsBefore, uploadsAfter, "real uploads must not change");

        Map<String, Integer> hard = zeroHard();
        List<Map<String, Object>> entries = new ArrayList<>();
        int nonOriginal = 0;
        for (V3BasicRunResult.V3EntryTrace trace : result.traces()) {
            V3EntryFactGraph graph = graphs.entry(trace.canonicalEntryKey());
            Map<String, Object> entry = entryReport(graph, generator, trace);
            accumulateHard(hard, entry);
            entries.add(entry);
            String chosen = String.valueOf(trace.chosenVariant());
            if (!"ORIGINAL".equals(chosen) && !"REJECT_ALL_AI".equals(chosen)) {
                nonOriginal++;
            }
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", "V323-CURRENT_JD-R1");
        run.put("completedAtUtc", Instant.now().toString());
        run.put("promptVersion", V3GenerationTelemetry.PROMPT_VERSION);
        run.put("model", model);
        run.put("jdSource", "TailorAnalysisServiceImplTest.CURRENT_JD");
        run.put("wallLatencyMs", elapsed);
        run.put("generateCalls", result.generateCalls());
        run.put("repairCalls", result.repairCalls());
        run.put("languageReviewCalls", result.languageReviewCalls());
        run.put("writerCalls", writerCalls);
        run.put("writerOutput", writerName);
        run.put("rewrittenEntryCount", result.rewrittenEntryCount());
        run.put("nonOriginalEntries", nonOriginal);
        run.put("hardMetrics", hard);
        run.put("uploadsBeforeSha256", uploadsBefore);
        run.put("uploadsAfterSha256", uploadsAfter);
        run.put("uploadsUnchanged", uploadsBefore.equals(uploadsAfter));
        run.put("continueRecommended", nonOriginal > 0 && hard.values().stream().allMatch(v -> v == 0));
        run.put("humanAcceptRates", "NOT_FILLED_BY_CURSOR");
        run.put("industryAverageClaim", false);
        run.put("entries", entries);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(reportDir.resolve("run.json").toFile(), run);
        mapper.writeValue(reportDir.resolve("summary.json").toFile(), Map.of(
                "promptVersion", V3GenerationTelemetry.PROMPT_VERSION,
                "model", model,
                "nonOriginalEntries", nonOriginal,
                "hardMetrics", hard,
                "continueRecommended", run.get("continueRecommended"),
                "repairCalls", result.repairCalls(),
                "generateCalls", result.generateCalls(),
                "writerCalls", writerCalls,
                "uploadsUnchanged", uploadsBefore.equals(uploadsAfter)
        ));
        writeMarkdown(reportDir.resolve("report.md"), run);
        assertFalse(hard.getOrDefault("writerRealUploads", 0) > 0);
    }

    private static Map<String, Object> entryReport(
            V3EntryFactGraph graph,
            RecordingGenerator generator,
            V3BasicRunResult.V3EntryTrace trace) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryKey", trace.canonicalEntryKey());
        entry.put("originalBullets", trace.originalBullets());
        entry.put("clarityBullets", trace.clarityBullets());
        entry.put("targetedBullets", trace.targetedBullets());
        entry.put("hybrid1Bullets", trace.hybrid1Bullets());
        entry.put("hybrid2Bullets", trace.hybrid2Bullets());
        entry.put("finalBullets", trace.finalBullets());
        entry.put("nodeSources", trace.nodeSources());
        entry.put("reviewerChoice", String.valueOf(trace.chosenVariant()));
        entry.put("decision", String.valueOf(trace.decision()));
        entry.put("validatorCode", trace.validatorCode());
        entry.put("noChangeReason", trace.noChangeReason());
        entry.put("repairReason", trace.repairReason());
        entry.put("promptVersion", trace.promptVersion());
        entry.put("model", trace.model());
        entry.put("latencyMs", trace.latencyMs());
        entry.put("promptTokens", trace.promptTokens() == null ? "unknown" : trace.promptTokens());
        entry.put("completionTokens", trace.completionTokens() == null ? "unknown" : trace.completionTokens());
        entry.put("originalScore", scoreMap(trace.originalScore()));
        entry.put("clarityScore", scoreMap(trace.clarityScore()));
        entry.put("targetedScore", scoreMap(trace.targetedScore()));
        entry.put("hybrid1Score", scoreMap(trace.hybrid1Score()));
        entry.put("hybrid2Score", scoreMap(trace.hybrid2Score()));
        entry.put("protectedFacts", trace.protectedFactKeys());
        entry.put("optionalFacts", trace.optionalFactKeys());
        entry.put("noiseFacts", trace.noiseFactKeys());
        V3FactCoverage.Result originalCov = graph == null
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.originalBullets());
        V3FactCoverage.Result clarityCov = graph == null
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.clarityBullets());
        V3FactCoverage.Result targetedCov = graph == null
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.targetedBullets());
        V3FactCoverage.Result hybrid1Cov = graph == null || trace.hybrid1Bullets().isEmpty()
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.hybrid1Bullets());
        V3FactCoverage.Result hybrid2Cov = graph == null || trace.hybrid2Bullets().isEmpty()
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.hybrid2Bullets());
        V3FactCoverage.Result finalCov = graph == null
                ? V3FactCoverage.Result.empty()
                : V3FactCoverage.compute(graph, trace.originalBullets(), trace.finalBullets());
        entry.put("originalCoverage", coverageMap(originalCov));
        entry.put("clarityCoverage", coverageMap(clarityCov));
        entry.put("targetedCoverage", coverageMap(targetedCov));
        entry.put("hybrid1Coverage", coverageMap(hybrid1Cov));
        entry.put("hybrid2Coverage", coverageMap(hybrid2Cov));
        entry.put("finalCoverage", coverageMap(finalCov));
        entry.put("usedFactKeysServer", finalCov.used());
        entry.put("invalidFactKeys", invalidKeys(graph, finalCov.used()));
        entry.put("omittedProtectedFacts", finalCov.omitted());
        entry.put("omittedOptionalFacts", finalCov.omittedOptional());
        entry.put("introducedFacts", finalCov.introduced());
        entry.put("duplicateFacts", finalCov.duplicate());
        RecordingGenerator.Call repairCall = generator.first(trace.canonicalEntryKey(), true);
        entry.put("repairOccurred", repairCall != null);
        entry.put("languageRegression", Boolean.TRUE.equals(scoreMap(trace.clarityScore()).get("languageRegression"))
                || Boolean.TRUE.equals(scoreMap(trace.targetedScore()).get("languageRegression"))
                || Boolean.TRUE.equals(scoreMap(trace.hybrid1Score()).get("languageRegression"))
                || Boolean.TRUE.equals(scoreMap(trace.hybrid2Score()).get("languageRegression")));
        return entry;
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

    private static List<String> invalidKeys(V3EntryFactGraph graph, List<String> used) {
        List<String> invalid = new ArrayList<>();
        if (graph == null) {
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
        map.put("notes", score.notes());
        return map;
    }

    private static void accumulateHard(Map<String, Integer> hard, Map<String, Object> entry) {
        @SuppressWarnings("unchecked")
        Map<String, Object> finalCov = (Map<String, Object>) entry.get("finalCoverage");
        if (finalCov != null) {
            bumpIfNonEmpty(hard, "hallucination", finalCov.get("introduced"));
            bumpIfNonEmpty(hard, "duplicate", finalCov.get("duplicate"));
            bumpIfNonEmpty(hard, "missingProtectedFact", finalCov.get("omittedProtected"));
        }
        @SuppressWarnings("unchecked")
        List<String> invalid = (List<String>) entry.get("invalidFactKeys");
        if (invalid != null && !invalid.isEmpty()) {
            hard.put("invalidFactKey", hard.getOrDefault("invalidFactKey", 0) + invalid.size());
        }
        if (Boolean.TRUE.equals(entry.get("languageRegression"))) {
            hard.put("languageRegression", hard.getOrDefault("languageRegression", 0) + 1);
        }
        if (V3ValidationDecision.CROSS_ENTRY_FACT.equals(String.valueOf(entry.get("validatorCode")))) {
            hard.put("crossEntry", hard.getOrDefault("crossEntry", 0) + 1);
        }
    }

    private static void bumpIfNonEmpty(Map<String, Integer> hard, String key, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            hard.put(key, hard.getOrDefault(key, 0) + list.size());
        }
    }

    private static Map<String, Integer> zeroHard() {
        Map<String, Integer> hard = new LinkedHashMap<>();
        hard.put("hallucination", 0);
        hard.put("crossEntry", 0);
        hard.put("missingProtectedFact", 0);
        hard.put("duplicate", 0);
        hard.put("invalidFactKey", 0);
        hard.put("languageRegression", 0);
        hard.put("writerRealUploads", 0);
        return hard;
    }

    private static void writeMarkdown(Path path, Map<String, Object> run) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.3 live smoke\n\n");
        md.append("One run. Cursor did not fill human ACCEPT rates and does not claim industry average.\n\n");
        md.append("- promptVersion: `").append(run.get("promptVersion")).append("`\n");
        md.append("- model: `").append(run.get("model")).append("`\n");
        md.append("- generateCalls: ").append(run.get("generateCalls")).append('\n');
        md.append("- repairCalls: ").append(run.get("repairCalls")).append('\n');
        md.append("- languageReviewCalls: ").append(run.get("languageReviewCalls")).append('\n');
        md.append("- writerCalls: ").append(run.get("writerCalls")).append('\n');
        md.append("- uploadsUnchanged: ").append(run.get("uploadsUnchanged")).append('\n');
        md.append("- continueRecommended: ").append(run.get("continueRecommended")).append("\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
        for (Map<String, Object> entry : entries) {
            md.append("## ").append(entry.get("entryKey")).append('\n');
            md.append("- reviewer: ").append(entry.get("reviewerChoice")).append('\n');
            md.append("- nodeSources: ").append(entry.get("nodeSources")).append('\n');
            md.append("- repairReason: ").append(entry.get("repairReason")).append('\n');
            md.append("- promptTokens: ").append(entry.get("promptTokens")).append('\n');
            md.append("- completionTokens: ").append(entry.get("completionTokens")).append("\n\n");
            md.append("**ORIGINAL** ").append(entry.get("originalScore")).append("\n\n");
            bullets(md, entry.get("originalBullets"));
            md.append("**CLARITY** ").append(entry.get("clarityScore")).append("\n\n");
            bullets(md, entry.get("clarityBullets"));
            md.append("**TARGETED** ").append(entry.get("targetedScore")).append("\n\n");
            bullets(md, entry.get("targetedBullets"));
            md.append("**HYBRID_1** ").append(entry.get("hybrid1Score")).append("\n\n");
            bullets(md, entry.get("hybrid1Bullets"));
            md.append("**HYBRID_2** ").append(entry.get("hybrid2Score")).append("\n\n");
            bullets(md, entry.get("hybrid2Bullets"));
            md.append("**FINAL**\n\n");
            bullets(md, entry.get("finalBullets"));
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void bullets(StringBuilder md, Object value) {
        List<String> list = value instanceof List<?> items
                ? items.stream().map(String::valueOf).toList()
                : List.of();
        for (String bullet : list) {
            md.append("- ").append(bullet).append('\n');
        }
        md.append('\n');
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
                    hashes.put(uploads.relativize(file).toString().replace('\\', '/'), "LOCKED:" + ex.getClass().getSimpleName());
                }
            });
        }
        return hashes;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
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

        private record Call(String key, boolean repair, V3EntryRewriteResult result) {
        }
    }
}
