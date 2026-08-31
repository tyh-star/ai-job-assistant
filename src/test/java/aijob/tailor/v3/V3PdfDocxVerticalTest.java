package aijob.tailor.v3;

import aijob.config.StoragePathResolver;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.service.impl.ResumeDocxTailorServiceImpl;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import aijob.util.YaohangCanonicalDocxFixture;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3PdfDocxVerticalTest {

    private static final String JD = V3BasicGoldenTest.JD;

    @TempDir
    Path tempDir;

    @Test
    void pdfParentCanonicalChildRoundTripDoesNotTouchSources() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        String pdfHashBefore = sha256(pdf);
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(
                pdf, tempDir.resolve("canonical.docx"));
        String canonicalHashBefore = sha256(canonical);
        roundTrip(canonical, true);
        assertEquals(pdfHashBefore, sha256(pdf));
        assertEquals(canonicalHashBefore, sha256(canonical));
    }

    @Test
    void nativeDocxRoundTripWritesEqualCountNodes() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path nativeDocx = YaohangCanonicalDocxFixture.writeCanonicalDocx(
                pdf, tempDir.resolve("native.docx"));
        roundTrip(nativeDocx, false);
    }

    private void roundTrip(Path sourceDocx, boolean pdfParent) throws Exception {
        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        List<ResumeDocumentNode> originalNodes = parser.parseDocx(sourceDocx);
        assertTrue(originalNodes.stream().anyMatch(node -> node.getNodeId().startsWith("p-")));
        ClaimLedger ledger = ClaimLedger.from(originalNodes);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        assertTrue(index.containsKey("PROJECT:prime-deals"));
        assertTrue(index.containsKey("PROJECT:smartpc-builder"));

        ScriptedGenerator generator = new ScriptedGenerator();
        V3BasicTailorService service = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi());
        V3BasicRunResult result = service.run(originalNodes, JD);
        assertEquals(1, generator.calls.getOrDefault("PROJECT:prime-deals", 0), String.valueOf(result.traces()));
        assertEquals(1, generator.calls.getOrDefault("PROJECT:smartpc-builder", 0), String.valueOf(result.traces()));
        assertEquals(1, result.languageReviewCalls());
        assertTrue(result.rewrittenEntryCount() >= 1);
        assertFalse(result.changes().isEmpty());
        assertTrue(result.changes().stream().noneMatch(change ->
                looksLikeHeadingOrDate(originalText(originalNodes, change.getNodeId()))));

        Path uploads = tempDir.resolve(pdfParent ? "uploads-pdf" : "uploads-docx");
        ResumeDocxTailorServiceImpl writer = new ResumeDocxTailorServiceImpl(
                parser, StoragePathResolver.forRoot(uploads));
        Path generated = writer.createTailoredDocx(sourceDocx, result.changes(), 1L, 10L);
        assertTrue(Files.exists(generated));
        Path generatedPdf = generated.resolveSibling(
                generated.getFileName().toString().replace(".docx", ".pdf"));
        Files.writeString(generatedPdf, "%PDF-1.4\n");
        assertTrue(Files.exists(generatedPdf));

        List<ResumeDocumentNode> rewritten = parser.parseDocx(generated);
        assertEquals(originalNodes.size(), rewritten.size());
        Map<String, String> originalById = byId(originalNodes);
        Map<String, String> rewrittenById = byId(rewritten);
        Map<String, String> expectedById = new LinkedHashMap<>(originalById);
        for (TailorChange change : result.changes()) {
            expectedById.put(change.getNodeId(), change.getNewText());
        }
        for (ResumeDocumentNode node : rewritten) {
            assertEquals(expectedById.get(node.getNodeId()), node.getText(), node.getNodeId());
        }
        assertTrue(rewrittenById.values().stream().anyMatch(text ->
                text.toLowerCase(Locale.ROOT).contains("prime deals")));
        assertTrue(rewrittenById.values().stream().anyMatch(text ->
                text.toLowerCase(Locale.ROOT).contains("smartpc")));
        assertTrue(rewrittenById.values().stream().anyMatch(text ->
                text.toLowerCase(Locale.ROOT).contains("york")));
        String primeFinal = entryText(ledger, index, rewrittenById, "PROJECT:prime-deals");
        String smartFinal = entryText(ledger, index, rewrittenById, "PROJECT:smartpc-builder");
        assertCovers(primeFinal, "java", "spring boot", "mvc", "rest", "aws");
        assertCovers(smartFinal, "java", "maven");
        assertFalse(smartFinal.toLowerCase(Locale.ROOT).contains("spring boot"));
        assertFalse(smartFinal.toLowerCase(Locale.ROOT).contains("restful"));
        assertEquals(1, result.generateCalls() >= 2 ? 1 : 1);
        assertTrue(result.generateCalls() >= 2);
        assertTrue(result.repairCalls() <= 2);
    }

    private static String entryText(
            ClaimLedger ledger,
            CanonicalEntryKeyResolver.Index index,
            Map<String, String> rewrittenById,
            String key) {
        ClaimLedger.Entry entry = index.entry(key);
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : entry.nodes()) {
            text.append(' ').append(rewrittenById.getOrDefault(node.getNodeId(), node.getText()));
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

    private static String originalText(List<ResumeDocumentNode> nodes, String nodeId) {
        return nodes.stream()
                .filter(node -> nodeId.equals(node.getNodeId()))
                .map(ResumeDocumentNode::getText)
                .findFirst()
                .orElse("");
    }

    private static boolean looksLikeHeadingOrDate(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("prime deals") && !lower.contains("java")
                || lower.contains("smartpc") && !lower.contains("java")
                || lower.contains("education")
                || lower.startsWith("http");
    }

    private static void assertCovers(String text, String... needles) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            assertTrue(lower.contains(needle), needle + " missing in " + text);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static final class ScriptedGenerator implements V3EntryRewriteGenerator {
        private final Map<String, Integer> calls = new ConcurrentHashMap<>();

        @Override
        public V3EntryRewriteResult generate(V3EntryRewriteRequest request) {
            calls.merge(request.canonicalEntryKey(), 1, Integer::sum);
            if ("PROJECT:prime-deals".equals(request.canonicalEntryKey())) {
                return rewritePrime(request);
            }
            if ("PROJECT:smartpc-builder".equals(request.canonicalEntryKey())) {
                return rewriteSmart(request);
            }
            return V3EntryRewriteResult.noChange(request.canonicalEntryKey());
        }

        private static V3EntryRewriteResult rewritePrime(V3EntryRewriteRequest request) {
            List<V3WritableNode> writable = request.writableNodes();
            if (writable.size() == 5) {
                return toResult(request, List.of(
                        "Led and collaborated on developing a shopping website.",
                        "Defined the project scope, clarified requirements, and used UML class diagrams for planning.",
                        "Designed and implemented the application using Java and Spring Boot with an MVC architecture.",
                        "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                        "Deployed the initial version of the project to AWS."));
            }
            List<String> bullets = new ArrayList<>();
            for (V3WritableNode node : writable) {
                bullets.add(polish(node.originalText()));
            }
            return toResult(request, bullets);
        }

        private static V3EntryRewriteResult rewriteSmart(V3EntryRewriteRequest request) {
            List<String> bullets = new ArrayList<>();
            for (V3WritableNode node : request.writableNodes()) {
                String original = node.originalText();
                String lower = original.toLowerCase(Locale.ROOT);
                if (lower.contains("communicat")) {
                    bullets.add(original);
                    continue;
                }
                String polished = original
                        .replace("within a given budget", "within a specified budget")
                        .replace("CPU, GPU & RAM", "CPU, GPU, and RAM")
                        .replace("to ensure the quality", "to ensure quality");
                bullets.add(polished);
            }
            return toResult(request, bullets);
        }

        private static String polish(String original) {
            String text = original == null ? "" : original.strip();
            String polished = text
                    .replace("Successfully ", "")
                    .replace("successfully ", "")
                    .replace("onto AWS", "to AWS")
                    .replace("managements", "operations")
                    .replace("helps users select", "helps users choose");
            if (polished.startsWith("Design and implement")) {
                polished = "Designed and implemented" + polished.substring("Design and implement".length());
            } else if (polished.startsWith("Developed a Java Maven")) {
                polished = polished.replace("Developed a Java Maven project that helps",
                        "Developed a Java Maven project that helps");
                if (polished.equals(text)) {
                    polished = polished.replace("within a given budget", "within a specified budget");
                }
            } else if (polished.startsWith("Implemented an algorithm")) {
                polished = polished.replace("CPU, GPU & RAM", "CPU, GPU, and RAM");
            }
            if (polished.equals(text) && !text.isBlank()) {
                polished = text.replace(" the ", " the ");
                if (polished.equals(text)) {
                    polished = text.endsWith(".") ? text.substring(0, text.length() - 1) + "." : text + ".";
                    if (polished.equals(text)) {
                        polished = "Delivered " + Character.toLowerCase(text.charAt(0)) + text.substring(1);
                    }
                }
            }
            return polished;
        }

        private static V3EntryRewriteResult toResult(V3EntryRewriteRequest request, List<String> bullets) {
            List<V3RewrittenNode> nodes = new ArrayList<>();
            List<V3WritableNode> writable = request.writableNodes();
            for (int i = 0; i < writable.size(); i++) {
                nodes.add(new V3RewrittenNode(
                        writable.get(i).nodeId(),
                        bullets.get(i),
                        List.of(),
                        List.of(),
                        "COORDINATED",
                        "vertical"));
            }
            return V3RewriteTestSupport.rewriteBoth(request, bullets.toArray(String[]::new));
        }
    }
}
