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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V3-2.4 JD2 × 3 verification after Action-Object Provenance. Does not rerun
 * the frozen nine-round eval and does not overwrite {@code docs/v3-2.3-*}.
 * Enable with {@code AIJOB_V3_24_JD2=true}.
 */
class V3BasicLiveJd2VerifyV324Test {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "AIJOB_V3_24_JD2", matches = "true")
    void threeAlexMorganJdRunsKeepProvenanceWithoutTouchingUploads() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required");
        assumeTrue(!apiKey.toLowerCase(Locale.ROOT).contains("placeholder"), "placeholder key");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");
        assumeTrue("v3-2.4-action-object-provenance".equals(V3GenerationTelemetry.PROMPT_VERSION));

        Path reportDir = Path.of("docs", "v3-2.4-jd2-verify");
        Files.createDirectories(reportDir);
        assumeTrue(Files.isDirectory(Path.of("docs", "v3-2.3-live-9")), "keep V3-2.3 live-9 reports");
        assumeTrue(Files.exists(Path.of("docs", "v3-2.3-smoke", "run.json")), "keep first smoke");

        Path uploadsRoot = Path.of("uploads").toAbsolutePath().normalize();
        Map<String, String> uploadsBefore = snapshotUploads(uploadsRoot);
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path canonical = GeneratedResumePdfFixture.writeCanonicalDocx(pdf, tempDir.resolve("alexmorgan.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ClaimLedger.from(nodes));
        String jd = V3BasicLiveQualityAcceptanceTest.JD_INTERN;

        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        List<Map<String, Object>> runs = new ArrayList<>();
        int introducedRelation = 0;
        int maintainHallucination = 0;
        int friendSubstitution = 0;
        int nonOriginalPrime = 0;

        for (int repeat = 1; repeat <= 3; repeat++) {
            String runId = "JD2-R" + repeat;
            V3BasicTailorService service = new V3BasicTailorService(
                    new OpenAiV3EntryRewriteGenerator(client, model),
                    new OpenAiV3ResumeLanguageReviewer(client, model));
            long started = System.nanoTime();
            V3BasicRunResult result = service.run(nodes, jd);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;

            int writerCalls = 0;
            if (!result.changes().isEmpty()) {
                Path writerOut = new ResumeDocxTailorServiceImpl(
                        new ResumeDocumentServiceImpl(),
                        StoragePathResolver.forRoot(tempDir.resolve("writer-" + runId)))
                        .createTailoredDocx(canonical, result.changes(), 1L, 10L);
                writerCalls = 1;
                assertFalse(writerOut.toAbsolutePath().normalize().startsWith(uploadsRoot));
            }
            assertEquals(uploadsBefore, snapshotUploads(uploadsRoot), "uploads changed on " + runId);

            Map<String, Object> run = new LinkedHashMap<>();
            run.put("runId", runId);
            run.put("jdName", "ALEX_MORGAN_JAVA_JD");
            run.put("promptVersion", V3GenerationTelemetry.PROMPT_VERSION);
            run.put("model", model);
            run.put("wallLatencyMs", elapsed);
            run.put("generateCalls", result.generateCalls());
            run.put("repairCalls", result.repairCalls());
            run.put("languageReviewCalls", result.languageReviewCalls());
            run.put("writerCalls", writerCalls);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (V3BasicRunResult.V3EntryTrace trace : result.traces()) {
                V3EntryFactGraph graph = graphs.entry(trace.canonicalEntryKey());
                V3ValidationDecision finalGate = V3EntryRewriteValidator.validate(graphs, new V3EntryRewrite(
                        trace.canonicalEntryKey(),
                        graph == null ? "" : graph.originalHeading(),
                        graph == null ? "" : graph.originalDateText(),
                        trace.finalBullets()));
                boolean relation = V3ValidationDecision.INTRODUCED_RELATION.equals(finalGate.reasonCode());
                boolean maintain = V3ActionObjectProvenanceGate.hasMaintainHallucination(graph, trace.finalBullets());
                boolean friend = V3ActionObjectProvenanceGate.hasFriendSubstitution(
                        String.join("\n", trace.originalBullets()),
                        String.join("\n", trace.finalBullets()));
                if (relation) {
                    introducedRelation++;
                }
                if (maintain) {
                    maintainHallucination++;
                }
                if (friend) {
                    friendSubstitution++;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("canonicalEntryKey", trace.canonicalEntryKey());
                entry.put("finalChoice", String.valueOf(trace.chosenVariant()));
                entry.put("originalBullets", trace.originalBullets());
                entry.put("finalBullets", trace.finalBullets());
                entry.put("validatorCode", finalGate.reasonCode());
                entry.put("introducedRelation", relation);
                entry.put("maintainHallucination", maintain);
                entry.put("friendSubstitution", friend);
                entries.add(entry);
                if (trace.canonicalEntryKey().contains("prime")
                        && !"ORIGINAL".equals(String.valueOf(trace.chosenVariant()))
                        && !"REJECT_ALL_AI".equals(String.valueOf(trace.chosenVariant()))) {
                    nonOriginalPrime++;
                }
            }
            run.put("entries", entries);
            runs.add(run);
            mapper.writeValue(reportDir.resolve(runId + ".json").toFile(), run);
        }

        Map<String, String> uploadsAfter = snapshotUploads(uploadsRoot);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("completedAtUtc", Instant.now().toString());
        summary.put("promptVersion", V3GenerationTelemetry.PROMPT_VERSION);
        summary.put("model", model);
        summary.put("runs", 3);
        summary.put("introducedActionObjectRelation", introducedRelation);
        summary.put("maintainHallucination", maintainHallucination);
        summary.put("friendSubstitution", friendSubstitution);
        summary.put("nonOriginalPrimeRounds", nonOriginalPrime);
        summary.put("uploadsUnchanged", uploadsBefore.equals(uploadsAfter));
        summary.put("industryAverageClaim", false);
        mapper.writeValue(reportDir.resolve("summary.json").toFile(), summary);

        StringBuilder md = new StringBuilder();
        md.append("# V3-2.4 JD2 × 3 provenance verification\n\n");
        md.append("V3-2.3 reports were not overwritten. Cursor does not claim industry average.\n\n");
        md.append("- introducedActionObjectRelation: ").append(introducedRelation).append('\n');
        md.append("- maintainHallucination: ").append(maintainHallucination).append('\n');
        md.append("- friendSubstitution: ").append(friendSubstitution).append('\n');
        md.append("- nonOriginalPrimeRounds: ").append(nonOriginalPrime).append('\n');
        md.append("- uploadsUnchanged: ").append(uploadsBefore.equals(uploadsAfter)).append("\n\n");
        for (Map<String, Object> run : runs) {
            md.append("## ").append(run.get("runId")).append('\n');
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) run.get("entries");
            for (Map<String, Object> entry : entries) {
                md.append("- ").append(entry.get("canonicalEntryKey"));
                md.append(" FINAL=").append(entry.get("finalChoice")).append('\n');
            }
            md.append('\n');
        }
        Files.writeString(reportDir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);

        assertEquals(0, introducedRelation);
        assertEquals(0, maintainHallucination);
        assertEquals(0, friendSubstitution);
        assertTrue(nonOriginalPrime >= 2, "need at least two natural non-ORIGINAL Prime rounds, was "
                + nonOriginalPrime);
        assertEquals(uploadsBefore, uploadsAfter);
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

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }
}
