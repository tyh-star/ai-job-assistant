package aijob.tailor.v3;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.ClaimLedger;
import aijob.util.ConservativeEvidencePacket;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V3-2.1 live OpenAI quality acceptance. Does not start Spring Boot, does not
 * call JSearch, and does not write the real uploads tree. Enable with
 * {@code AIJOB_V3_21_LIVE=true} plus {@code OPENAI_API_KEY}.
 *
 * <p>Harness only. Prompts, scorers, validators, reviewers, and thresholds stay frozen.
 */
public class V3BasicLiveQualityAcceptanceTest {

    static final String PROMPT_VERSION = V3GenerationTelemetry.PROMPT_VERSION;

    /** Saved fixture: TailorAnalysisServiceImplTest.CURRENT_JD */
    static final String JD_BACKEND = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Maven
            - Implement features using MVC architecture
            - Pursuing or completed a degree in Computer Science or related field

            Responsibilities:
            - Design and implement backend services

            Preferred:
            - Docker and Kubernetes
            - Agile
            """;

    /** Saved fixture: TailorAnalysisTaxonomyS1Test.YAOHANG_JAVA_JD */
    public static final String JD_INTERN = """
            Junior Java Backend Developer

            Required qualifications:
            - Java and Spring Boot
            - REST APIs
            - Experience with Maven
            - Proficiency in SQL and MySQL
            - Knowledge of data structures and algorithms
            - Currently pursuing or recently completed a degree in Computer Science
            - Authorized to work in Canada

            Responsibilities:
            - Build and integrate RESTful APIs
            - Debug, test and document backend applications
            - Deliver clean, well-documented and thoroughly tested code
            - Optimize application performance
            - Deploy and maintain applications on AWS
            - Collaborate through an Agile development process
            - Design and implement backend services

            Preferred:
            - Docker and Kubernetes
            - Strong communication and teamwork skills
            """;

    /** Saved fixture: TailorAnalysisS1ATest.S1A_JD */
    static final String JD_CLOUD = """
            Junior Java Backend Developer

            Required qualifications:
            Currently pursuing or recently completed a degree in Computer Science

            Responsibilities:
            Write thoroughly tested and well-documented code.
            Successfully deployed onto AWS.
            You will optimize program functionalities.
            You will debug production issues.
            """;

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "AIJOB_V3_21_LIVE", matches = "true")
    void nineLiveRunsWriteReportsWithoutTouchingUploads() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.toLowerCase(Locale.ROOT).contains("placeholder"), "placeholder key");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");

        Path reportDir = Path.of("docs", "v3-2.1-live");
        Files.createDirectories(reportDir);
        Path uploadsRoot = Path.of("uploads").toAbsolutePath().normalize();

        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, tempDir.resolve("yaohang.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);

        List<JdSpec> jds = List.of(
                new JdSpec("JD1_JUNIOR_JAVA_BACKEND", "Junior Java Backend Developer",
                        "TailorAnalysisServiceImplTest.CURRENT_JD", JD_BACKEND, sha256(JD_BACKEND)),
                new JdSpec("JD2_SOFTWARE_INTERN", "Junior Java Backend Developer (intern-complete fixture)",
                        "TailorAnalysisTaxonomyS1Test.YAOHANG_JAVA_JD", JD_INTERN, sha256(JD_INTERN)),
                new JdSpec("JD3_BACKEND_CLOUD", "Junior Java Backend Developer (S1A AWS/testing fixture)",
                        "TailorAnalysisS1ATest.S1A_JD", JD_CLOUD, sha256(JD_CLOUD))
        );
        writeJson(reportDir.resolve("jds.json"), jds.stream().map(jd -> Map.of(
                "id", jd.id,
                "title", jd.title,
                "source", jd.source,
                "textSha256", jd.textSha256,
                "text", jd.text
        )).toList());

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        List<Map<String, Object>> allRuns = new ArrayList<>();
        Map<String, Integer> hard = zeroHard();
        int originalWins = 0;
        int clarityWins = 0;
        int targetedWins = 0;
        int generateTotal = 0;
        int repairTotal = 0;
        int reviewTotal = 0;
        long latencyTotal = 0;
        int tokenTotal = 0;

        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        for (JdSpec jd : jds) {
            for (int repeat = 1; repeat <= 3; repeat++) {
                String runId = jd.id + "-R" + repeat;
                RecordingGenerator generator = new RecordingGenerator(
                        new OpenAiV3EntryRewriteGenerator(client, model));
                V3BasicTailorService service = new V3BasicTailorService(
                        generator, new OpenAiV3ResumeLanguageReviewer(client, model));
                long started = System.nanoTime();
                V3BasicRunResult result = service.run(nodes, jd.text);
                long elapsed = (System.nanoTime() - started) / 1_000_000L;

                int writerCalls = 0;
                String writerName = "";
                if (!result.changes().isEmpty()) {
                    Path writerRoot = tempDir.resolve("writer-" + runId);
                    ResumeDocxTailorServiceImpl writer = new ResumeDocxTailorServiceImpl(
                            new ResumeDocumentServiceImpl(),
                            StoragePathResolver.forRoot(writerRoot));
                    Path writerOut = writer.createTailoredDocx(canonical, result.changes(), 1L, 10L);
                    writerCalls = 1;
                    writerName = writerOut.getFileName().toString();
                    assertFalse(writerOut.toAbsolutePath().normalize().startsWith(uploadsRoot),
                            "Writer leaked into uploads");
                }

                Map<String, Object> run = new LinkedHashMap<>();
                run.put("runId", runId);
                run.put("jdId", jd.id);
                run.put("jdTitle", jd.title);
                run.put("jdSource", jd.source);
                run.put("jdTextSha256", jd.textSha256);
                run.put("model", model);
                run.put("promptVersion", PROMPT_VERSION);
                run.put("wallLatencyMs", elapsed);
                run.put("generateCalls", result.generateCalls());
                run.put("repairCalls", result.repairCalls());
                run.put("languageReviewCalls", result.languageReviewCalls());
                run.put("writerCalls", writerCalls);
                run.put("writerOutput", writerName);
                run.put("rewrittenEntryCount", result.rewrittenEntryCount());

                List<Map<String, Object>> entryReports = new ArrayList<>();
                for (V3BasicRunResult.V3EntryTrace trace : result.traces()) {
                    V3EntryFactGraph graph = graphs.entry(trace.canonicalEntryKey());
                    Map<String, Object> entry = entryReport(graphs, graph, generator, trace);
                    accumulateHard(hard, entry);
                    entryReports.add(entry);
                    String chosen = String.valueOf(trace.chosenVariant());
                    if ("ORIGINAL".equals(chosen) || "REJECT_ALL_AI".equals(chosen)) {
                        originalWins++;
                    } else if ("CLARITY_FIRST".equals(chosen)) {
                        clarityWins++;
                    } else if ("JD_TARGETED".equals(chosen)) {
                        targetedWins++;
                    }
                    latencyTotal += trace.latencyMs();
                    tokenTotal += trace.promptTokens() + trace.completionTokens();
                }
                run.put("entries", entryReports);
                run.put("specialChecks", specialChecks(result.traces()));
                allRuns.add(run);
                generateTotal += result.generateCalls();
                repairTotal += result.repairCalls();
                reviewTotal += result.languageReviewCalls();
                mapper.writeValue(reportDir.resolve(runId + ".json").toFile(), run);
            }
        }

        boolean hardFail = hard.values().stream().anyMatch(v -> v != null && v > 0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("completedAtUtc", Instant.now().toString());
        summary.put("promptVersion", PROMPT_VERSION);
        summary.put("model", model);
        summary.put("runs", allRuns.size());
        summary.put("generateCalls", generateTotal);
        summary.put("repairCalls", repairTotal);
        summary.put("languageReviewCalls", reviewTotal);
        summary.put("sumTraceLatencyMs", latencyTotal);
        summary.put("sumTraceTokens", tokenTotal);
        summary.put("originalWins", originalWins);
        summary.put("clarityWins", clarityWins);
        summary.put("targetedWins", targetedWins);
        summary.put("hardMetrics", hard);
        summary.put("hardFail", hardFail);
        summary.put("humanAcceptRates", "NOT_FILLED_BY_CURSOR");
        summary.put("industryAverageClaim", false);
        mapper.writeValue(reportDir.resolve("summary.json").toFile(), summary);
        writeTechnicalMarkdown(reportDir.resolve("technical.md"), jds, allRuns, summary);
        writeBlindMarkdown(reportDir.resolve("blind.md"), allRuns);
        writeFinalsMarkdown(reportDir.resolve("finals.md"), allRuns);

        if (hardFail) {
            throw new AssertionError("V3-2.1 live hard metrics failed: "
                    + mapper.writeValueAsString(hard)
                    + " Reports retained under docs/v3-2.1-live/");
        }
    }

    private static Map<String, Object> entryReport(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            RecordingGenerator generator,
            V3BasicRunResult.V3EntryTrace trace) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryKey", trace.canonicalEntryKey());
        entry.put("originalBullets", trace.originalBullets());
        entry.put("clarityBullets", trace.clarityBullets());
        entry.put("targetedBullets", trace.targetedBullets());
        entry.put("finalBullets", trace.finalBullets());
        entry.put("reviewerChoice", String.valueOf(trace.chosenVariant()));
        entry.put("reviewerReason", trace.noChangeReason());
        entry.put("decision", String.valueOf(trace.decision()));
        entry.put("noChangeReason", trace.noChangeReason());
        entry.put("validatorCode", trace.validatorCode());
        entry.put("promptVersion", trace.promptVersion());
        entry.put("model", trace.model());
        entry.put("latencyMs", trace.latencyMs());
        entry.put("promptTokens", trace.promptTokens());
        entry.put("completionTokens", trace.completionTokens());
        entry.put("originalScore", scoreMap(trace.originalScore()));
        entry.put("clarityScore", scoreMap(trace.clarityScore()));
        entry.put("targetedScore", scoreMap(trace.targetedScore()));

        V3ValidationDecision clarityGate = validate(graphs, graph, trace.clarityBullets());
        V3ValidationDecision targetedGate = validate(graphs, graph, trace.targetedBullets());
        V3ValidationDecision finalGate = validate(graphs, graph, trace.finalBullets());
        entry.put("clarityHardGate", gateMap(clarityGate));
        entry.put("targetedHardGate", gateMap(targetedGate));
        entry.put("finalHardGate", gateMap(finalGate));

        V3FactCoverage.Result clarityCov = V3FactCoverage.compute(graph, trace.originalBullets(), trace.clarityBullets());
        V3FactCoverage.Result targetedCov = V3FactCoverage.compute(graph, trace.originalBullets(), trace.targetedBullets());
        V3FactCoverage.Result finalCov = V3FactCoverage.compute(graph, trace.originalBullets(), trace.finalBullets());
        entry.put("clarityCoverage", coverageMap(clarityCov, graph, trace.originalBullets(), trace.clarityBullets()));
        entry.put("targetedCoverage", coverageMap(targetedCov, graph, trace.originalBullets(), trace.targetedBullets()));
        entry.put("finalCoverage", coverageMap(finalCov, graph, trace.originalBullets(), trace.finalBullets()));

        RecordingGenerator.Call firstCall = generator.first(trace.canonicalEntryKey(), false);
        RecordingGenerator.Call repairCall = generator.first(trace.canonicalEntryKey(), true);
        entry.put("repairOccurred", repairCall != null);
        entry.put("repairReason", repairCall == null ? "" : "second generate after both variants failed fact gate");
        List<String> usedFinal = new ArrayList<>();
        usedFinal.addAll(finalCov.retained());
        usedFinal.addAll(finalCov.moved());
        entry.put("usedFactKeysServer", usedFinal);
        entry.put("usedFactKeysClarity", modelFactKeys(firstCall, true));
        entry.put("usedFactKeysTargeted", modelFactKeys(firstCall, false));
        entry.put("invalidFactKeys", invalidKeys(graph, modelFactKeys(firstCall, true), modelFactKeys(firstCall, false)));
        entry.put("emptyUsedFactKeys", usedFinal.isEmpty() && trace.decision() == V3RewriteDecision.REWRITE);
        return entry;
    }

    private static Map<String, Object> specialChecks(List<V3BasicRunResult.V3EntryTrace> traces) {
        Map<String, Object> checks = new LinkedHashMap<>();
        for (V3BasicRunResult.V3EntryTrace trace : traces) {
            String joined = String.join("\n", trace.finalBullets()).toLowerCase(Locale.ROOT);
            String original = String.join("\n", trace.originalBullets()).toLowerCase(Locale.ROOT);
            String key = trace.canonicalEntryKey().toLowerCase(Locale.ROOT);
            if (key.contains("prime")) {
                checks.put("primeLeadRetained", joined.contains("lead") || joined.contains("led"));
                checks.put("primeJavaSpringNatural", joined.contains("java") && joined.contains("spring"));
                checks.put("primeRestAuthProductCart",
                        (joined.contains("rest") || joined.contains("api"))
                                && (joined.contains("auth") || original.contains("auth"))
                                && (joined.contains("product") || original.contains("product"))
                                && (joined.contains("cart") || original.contains("cart")));
                checks.put("primeDeployedWording", joined.contains("deploy"));
                checks.put("primeExecutedDeployment", joined.contains("executed the deployment")
                        || joined.contains("executed the deploy"));
            }
            if (key.contains("smart") || key.contains("pc")) {
                checks.put("smartJavaMaven", joined.contains("java") && joined.contains("maven"));
                checks.put("smartBudgetOopAlgo",
                        (joined.contains("budget") || original.contains("budget"))
                                && (joined.contains("oop") || joined.contains("object") || original.contains("oop"))
                                && (joined.contains("algorithm") || original.contains("algorithm")));
                checks.put("smartCpuGpuRam",
                        (joined.contains("cpu") || original.contains("cpu"))
                                && (joined.contains("gpu") || original.contains("gpu"))
                                && (joined.contains("ram") || original.contains("ram")));
                checks.put("smartDocsTesting",
                        joined.contains("test") || joined.contains("javadoc") || joined.contains("document")
                                || original.contains("test") || original.contains("javadoc"));
                checks.put("smartCommunicationKeptOrBetter",
                        joined.contains("communicat") || original.contains("communicat"));
            }
        }
        return checks;
    }

    private static void accumulateHard(Map<String, Integer> hard, Map<String, Object> entry) {
        @SuppressWarnings("unchecked")
        Map<String, Object> finalGate = (Map<String, Object>) entry.get("finalHardGate");
        String code = String.valueOf(finalGate.get("reasonCode"));
        if ("INVENTED_SKILL".equals(code) || "INVENTED_CREDENTIAL".equals(code)) {
            bump(hard, "hallucination");
        }
        if ("CROSS_ENTRY_FACT".equals(code)) {
            bump(hard, "crossEntry");
        }
        if ("UNCONFIRMED_METRIC".equals(code)) {
            bump(hard, "unsupportedMetric");
        }
        if ("ROLE_ESCALATION".equals(code)) {
            bump(hard, "roleEscalation");
        }
        if ("IDENTITY_MUTATION".equals(code)) {
            bump(hard, "identityMutation");
        }
        if ("MISSING_MATERIAL_FACT".equals(code)) {
            bump(hard, "missingProtectedFact");
        }
        if ("DUPLICATE_MATERIAL_FACT".equals(code)) {
            bump(hard, "introducedDuplicate");
        }
        @SuppressWarnings("unchecked")
        List<String> invalid = (List<String>) entry.get("invalidFactKeys");
        if (invalid != null && !invalid.isEmpty()) {
            bump(hard, "invalidFactKey");
        }
        if (Boolean.TRUE.equals(entry.get("emptyUsedFactKeys"))) {
            bump(hard, "emptyUsedFactKeys");
        }
    }

    private static Map<String, Integer> zeroHard() {
        Map<String, Integer> hard = new LinkedHashMap<>();
        hard.put("hallucination", 0);
        hard.put("crossEntry", 0);
        hard.put("unsupportedMetric", 0);
        hard.put("roleEscalation", 0);
        hard.put("identityMutation", 0);
        hard.put("missingProtectedFact", 0);
        hard.put("introducedDuplicate", 0);
        hard.put("invalidFactKey", 0);
        hard.put("emptyUsedFactKeys", 0);
        hard.put("writerRealUploads", 0);
        return hard;
    }

    private static void bump(Map<String, Integer> hard, String key) {
        hard.put(key, hard.getOrDefault(key, 0) + 1);
    }

    private static V3ValidationDecision validate(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            List<String> bullets) {
        if (graph == null) {
            return V3ValidationDecision.reject(V3ValidationDecision.IDENTITY_MUTATION, "missing graph");
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

    private static Map<String, Object> coverageMap(
            V3FactCoverage.Result coverage,
            V3EntryFactGraph graph,
            List<String> original,
            List<String> rewritten) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("retained", coverage.retained());
        map.put("moved", coverage.moved());
        map.put("omitted", coverage.omitted());
        map.put("introducedTech", introducedTech(original, rewritten));
        map.put("duplicateTech", duplicateTech(graph, rewritten));
        return map;
    }

    private static List<String> introducedTech(List<String> original, List<String> rewritten) {
        Set<String> before = ConservativeEvidencePacket.extractNormalizedTech(String.join(" ", original));
        Set<String> after = ConservativeEvidencePacket.extractNormalizedTech(String.join(" ", rewritten));
        List<String> extra = new ArrayList<>();
        for (String tech : after) {
            if (!before.contains(tech)) {
                extra.add(tech);
            }
        }
        return extra;
    }

    private static List<String> duplicateTech(V3EntryFactGraph graph, List<String> bullets) {
        List<String> dup = new ArrayList<>();
        if (graph == null) {
            return dup;
        }
        for (V3Fact fact : graph.factsOf(V3FactKind.TECHNOLOGY)) {
            int hits = 0;
            for (String bullet : bullets) {
                if (fact.coveredBy(bullet)) {
                    hits++;
                }
            }
            if (hits > 1) {
                dup.add(fact.factKey());
            }
        }
        return dup;
    }

    private static List<String> modelFactKeys(RecordingGenerator.Call call, boolean clarity) {
        if (call == null || call.result == null) {
            return List.of();
        }
        V3EntryRewriteVariant variant = clarity ? call.result.clarityFirst() : call.result.jdTargeted();
        List<String> keys = new ArrayList<>();
        for (V3RewrittenNode node : variant.rewrittenNodes()) {
            keys.addAll(node.usedFactKeys());
        }
        keys.addAll(variant.factsRetained());
        return List.copyOf(new TreeSet<>(keys));
    }

    private static List<String> invalidKeys(V3EntryFactGraph graph, List<String> a, List<String> b) {
        List<String> invalid = new ArrayList<>();
        if (graph == null) {
            return invalid;
        }
        for (String key : a) {
            if (!key.isBlank() && !graph.hasFactKey(key)) {
                invalid.add(key);
            }
        }
        for (String key : b) {
            if (!key.isBlank() && !graph.hasFactKey(key) && !invalid.contains(key)) {
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

    private static void writeTechnicalMarkdown(
            Path path,
            List<JdSpec> jds,
            List<Map<String, Object>> runs,
            Map<String, Object> summary) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.1 live technical report\n\n");
        md.append("Cursor did not fill human ACCEPT rates and does not claim industry average.\n\n");
        md.append("- promptVersion: `").append(PROMPT_VERSION).append("`\n");
        md.append("- model: `").append(summary.get("model")).append("`\n");
        md.append("- generateCalls: ").append(summary.get("generateCalls")).append('\n');
        md.append("- repairCalls: ").append(summary.get("repairCalls")).append('\n');
        md.append("- languageReviewCalls: ").append(summary.get("languageReviewCalls")).append('\n');
        md.append("- ORIGINAL wins: ").append(summary.get("originalWins")).append('\n');
        md.append("- CLARITY wins: ").append(summary.get("clarityWins")).append('\n');
        md.append("- TARGETED wins: ").append(summary.get("targetedWins")).append("\n\n");
        md.append("## JD sources\n\n");
        for (JdSpec jd : jds) {
            md.append("### ").append(jd.id).append(" - ").append(jd.title).append('\n');
            md.append("- source: ").append(jd.source).append('\n');
            md.append("- sha256: `").append(jd.textSha256).append("`\n\n");
        }
        for (Map<String, Object> run : runs) {
            md.append("## ").append(run.get("runId")).append('\n');
            md.append("- generateCalls=").append(run.get("generateCalls"));
            md.append(" repairCalls=").append(run.get("repairCalls"));
            md.append(" reviewCalls=").append(run.get("languageReviewCalls"));
            md.append(" writerCalls=").append(run.get("writerCalls"));
            md.append(" wallMs=").append(run.get("wallLatencyMs")).append('\n');
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("\n### ").append(entry.get("entryKey")).append('\n');
                md.append("- reviewer: ").append(entry.get("reviewerChoice"));
                md.append(" reason: ").append(entry.get("reviewerReason")).append('\n');
                md.append("- repair: ").append(entry.get("repairOccurred")).append('\n');
                md.append("- ORIGINAL\n");
                bullets(md, entry.get("originalBullets"));
                md.append("- CLARITY_FIRST\n");
                bullets(md, entry.get("clarityBullets"));
                md.append("- JD_TARGETED\n");
                bullets(md, entry.get("targetedBullets"));
                md.append("- FINAL\n");
                bullets(md, entry.get("finalBullets"));
            }
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void writeBlindMarkdown(Path path, List<Map<String, Object>> runs) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.1 blind human review\n\n");
        md.append("Mark each pair ACCEPT_AS_IS / ACCEPT_WITH_MINOR_EDIT / REJECT.\n");
        md.append("Variant type, scores, and reviewer choice are hidden.\n\n");
        int n = 1;
        for (Map<String, Object> run : runs) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("## Item ").append(n++).append('\n');
                md.append("JD group: ").append(run.get("jdId")).append("\n\n");
                md.append("**ORIGINAL**\n\n");
                bullets(md, entry.get("originalBullets"));
                md.append("**FINAL**\n\n");
                bullets(md, entry.get("finalBullets"));
                md.append("- Better than original?\n- Natural / human?\n- More JD-relevant?\n");
                md.append("- All important facts kept?\n- Vague or puffed language?\n- Repeated facts?\n");
                md.append("- Worth putting on the final resume?\n- Label:\n\n");
            }
        }
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void writeFinalsMarkdown(Path path, List<Map<String, Object>> runs) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# V3-2.1 nine-run final text\n\n");
        for (Map<String, Object> run : runs) {
            md.append("## ").append(run.get("runId")).append(" - ").append(run.get("jdTitle")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("### ").append(entry.get("entryKey")).append('\n');
                bullets(md, entry.get("finalBullets"));
            }
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

    private static void writeJson(Path path, Object value) throws Exception {
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), value);
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private record JdSpec(String id, String title, String source, String text, String textSha256) {
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
