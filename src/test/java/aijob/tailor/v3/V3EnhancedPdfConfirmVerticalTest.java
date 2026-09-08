package aijob.tailor.v3;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import aijob.util.GeneratedResumePdfFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedPdfConfirmVerticalTest {

    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";

    @TempDir
    Path tempDir;

    @Test
    void pdfParentEnhancedProposalConfirmParsesGeneratedDocx() throws Exception {
        Path uploadsRoot = Path.of("uploads");
        Map<String, String> before = snapshot(uploadsRoot);
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path canonical = GeneratedResumePdfFixture.writeCanonicalDocx(pdf, tempDir.resolve("child.docx"));
        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        List<ResumeDocumentNode> original = parser.parseDocx(canonical);
        ClaimLedger ledger = ClaimLedger.from(original);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        assertEquals(PRIME, index.keyForEntryId(ledger.entryOf(firstBullet(original, "java spring")).entryId()));
        assertTrue(index.containsKey(SMART));

        V3UserEvidence confirmed = new V3UserEvidence(
                41L, PRIME, "CONFIRMED_USED",
                "maintained the AWS application with weekly patching", "", "", "", "maintain");
        ScriptedEnhancedGenerator generator = new ScriptedEnhancedGenerator();
        V3EnhancedTailorService service = new V3EnhancedTailorService(
                generator, V3RewriteTestSupport.acceptBestAi());
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(
                original, V3BasicLiveQualityAcceptanceTest.JD_INTERN, List.of(confirmed));
        V3EnhancedRunResult result = service.run(
                original, V3BasicLiveQualityAcceptanceTest.JD_INTERN, List.of(confirmed), map);
        assertEquals(0, result.writerCalls());
        assertFalse(result.changes().isEmpty());
        assertTrue(result.entryReviews().stream().anyMatch(review -> PRIME.equals(review.getCanonicalEntryKey())));
        assertTrue(result.changes().stream().noneMatch(change -> looksLikeHeading(original, change.getNodeId())));

        List<TailorChange> primeOnly = result.changes().stream()
                .filter(change -> {
                    String key = CanonicalEntryKeyResolver.index(ClaimLedger.from(original)).keyForNodeId(change.getNodeId());
                    return PRIME.equals(key);
                })
                .toList();
        for (TailorChange change : primeOnly) {
            change.setApplied(true);
        }

        ResumeDocxTailorServiceImpl writer = new ResumeDocxTailorServiceImpl(
                parser, StoragePathResolver.forRoot(tempDir.resolve("uploads-enhanced")));
        Path generated = writer.createTailoredDocx(canonical, primeOnly, 1L, 10L);
        Path generatedPdf = generated.resolveSibling(
                generated.getFileName().toString().replace(".docx", ".pdf"));
        Files.writeString(generatedPdf, "%PDF-1.4\n");
        List<ResumeDocumentNode> rewritten = parser.parseDocx(generated);
        assertEquals(original.size(), rewritten.size());
        Map<String, String> originalById = byId(original);
        Map<String, String> rewrittenById = byId(rewritten);
        for (ResumeDocumentNode node : rewritten) {
            String expected = originalById.get(node.getNodeId());
            for (TailorChange change : primeOnly) {
                if (change.getNodeId().equals(node.getNodeId())) {
                    expected = change.getNewText();
                }
            }
            assertEquals(expected, node.getText(), node.getNodeId());
        }
        String smart = entryText(index, rewrittenById, SMART);
        assertFalse(smart.toLowerCase(Locale.ROOT).contains("spring boot"));
        assertTrue(entryText(index, rewrittenById, PRIME).toLowerCase(Locale.ROOT).contains("maintain"));
        assertTrue(rewrittenById.values().stream().anyMatch(text -> text.toLowerCase(Locale.ROOT).contains("education")
                || text.toLowerCase(Locale.ROOT).contains("example")));
        assertEquals(before, snapshot(uploadsRoot));
        assertEquals(sha256(pdf), sha256(GeneratedResumePdfFixture.pdf()));
    }

    private static String firstBullet(List<ResumeDocumentNode> nodes, String needle) {
        String match = needle.toLowerCase(Locale.ROOT);
        return nodes.stream()
                .filter(node -> node.getText() != null && node.getText().toLowerCase(Locale.ROOT).contains(match))
                .map(ResumeDocumentNode::getNodeId)
                .findFirst()
                .orElseThrow();
    }

    private static boolean looksLikeHeading(List<ResumeDocumentNode> nodes, String nodeId) {
        String text = nodes.stream()
                .filter(node -> nodeId.equals(node.getNodeId()))
                .map(ResumeDocumentNode::getText)
                .findFirst()
                .orElse("");
        String lower = text.toLowerCase(Locale.ROOT);
        return (lower.contains("prime deals") && !lower.contains("java"))
                || (lower.contains("smartpc") && !lower.contains("java"))
                || lower.startsWith("http");
    }

    private static Map<String, String> byId(List<ResumeDocumentNode> nodes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ResumeDocumentNode node : nodes) {
            map.put(node.getNodeId(), node.getText());
        }
        return map;
    }

    private static String entryText(
            CanonicalEntryKeyResolver.Index index, Map<String, String> byId, String key) {
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : index.entry(key).nodes()) {
            text.append(' ').append(byId.getOrDefault(node.getNodeId(), node.getText()));
        }
        return text.toString();
    }

    private static Map<String, String> snapshot(Path uploads) throws Exception {
        Map<String, String> hashes = new java.util.TreeMap<>();
        if (!Files.isDirectory(uploads)) {
            return hashes;
        }
        try (var stream = Files.walk(uploads)) {
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

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static final class ScriptedEnhancedGenerator implements V3EnhancedEntryRewriteGenerator {
        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            List<String> bullets = new ArrayList<>();
            for (V3WritableNode node : request.writableNodes()) {
                String text = node.originalText();
                if (PRIME.equals(request.canonicalEntryKey())
                        && text.toLowerCase(Locale.ROOT).contains("deployed")
                        && request.userConfirmedFacts().stream().anyMatch(item ->
                        item.combinedText().toLowerCase(Locale.ROOT).contains("maintain"))) {
                    text = text.replace("Successfully ", "")
                            .replace("successfully ", "")
                            .replace("Deployed ", "Deployed and maintained ")
                            .replace("deployed ", "deployed and maintained ");
                    if (!text.toLowerCase(Locale.ROOT).contains("weekly")) {
                        text = text.replace("AWS", "AWS with weekly patching");
                    }
                }
                bullets.add(text);
            }
            return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
        }
    }
}
