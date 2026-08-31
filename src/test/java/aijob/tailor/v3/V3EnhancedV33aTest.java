package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import aijob.dto.ResumeDocumentNode;
import aijob.entity.ResumeEvidenceItem;
import aijob.util.ActionConceptRegistry;
import aijob.util.ClaimLedger;
import aijob.util.ResumeEntryCatalog;
import aijob.util.YaohangCanonicalDocxFixture;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedV33aTest {

    private static final String PRIME = "PROJECT:prime-deals";
    private static final String SMART = "PROJECT:smartpc-builder";

    @TempDir
    Path tempDir;

    @Test
    void internJdMapCapsQuestionsAndSkipsExistingFacts() throws Exception {
        Path uploads = Path.of("uploads");
        Map<String, String> before = snapshotUploads(uploads);
        V3EnhancedOpportunityService service = new V3EnhancedOpportunityService();
        V3OpportunityMap map = service.scan(primeAndSmart(), V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        assertEquals(0, map.writerCalls());
        assertEquals(0, map.openaiCalls());
        assertFalse(map.wroteJobOrResume());
        assertTrue(map.questions().size() <= V3EnhancedQuestionPlanner.MAX_TOTAL, String.valueOf(map.questions()));
        assertTrue(map.entries().stream().anyMatch(entry -> PRIME.equals(entry.canonicalEntryKey())));
        assertTrue(map.entries().stream().anyMatch(entry -> SMART.equals(entry.canonicalEntryKey())));
        V3OpportunityEntry prime = entry(map, PRIME);
        assertEquals(V3OpportunityPriority.HIGH, prime.priority());
        assertTrue(prime.partiallyCoveredRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("deploy")
                        && item.toLowerCase(Locale.ROOT).contains("maintain")));
        assertFalse(prime.coveredRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("deploy")
                        && item.toLowerCase(Locale.ROOT).contains("maintain")));
        V3OpportunityEntry smart = entry(map, SMART);
        assertFalse(smart.canStrengthenWithExistingFacts().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("spring boot")));
        assertFalse(smart.canStrengthenWithExistingFacts().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("rest")));
        assertFalse(smart.canStrengthenWithExistingFacts().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("aws")));
        assertTrue(smart.partiallyCoveredRequirements().stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT).contains("java")
                        && item.toLowerCase(Locale.ROOT).contains("spring")));
        assertTrue(prime.missingFactsThatCanBeConfirmed().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("maintenance")));
        assertTrue(prime.cannotInvent().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("docker")));
        assertTrue(prime.cannotInvent().stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).contains("communication")));
        assertFalse(containsTopic(map, "docker"));
        assertFalse(containsTopic(map, "kubernetes"));
        assertFalse(containsTopic(map, "communication"));
        assertFalse(containsTopic(map, "teamwork"));
        assertFalse(map.questions().stream().anyMatch(question ->
                question.promptEn().toLowerCase(Locale.ROOT).contains("restful apis")
                        && question.promptEn().toLowerCase(Locale.ROOT).contains("have you used")));
        assertEquals(0, questionsFor(map, SMART).stream().filter(question -> "maintain".equals(question.topic())).count());
        assertTrue(questionsFor(map, PRIME).stream().anyMatch(question -> "maintain".equals(question.topic())));
        assertTrue(questionsFor(map, PRIME).size() <= V3EnhancedQuestionPlanner.MAX_PER_ENTRY);
        assertTrue(questionsFor(map, SMART).size() <= V3EnhancedQuestionPlanner.MAX_PER_ENTRY);
        for (V3EnhancedQuestion question : map.questions()) {
            assertTrue(question.skipAllowed());
            assertFalse(question.canonicalEntryKey().isBlank());
            assertFalse(V3EnhancedQuestionPlanner.looksLikeYesNoTrap(question.promptEn()));
            assertFalse(V3EnhancedQuestionPlanner.looksLikeYesNoTrap(question.promptZh()));
            assertFalse(question.promptEn().contains("factKey"));
            assertFalse(question.promptEn().contains("nodeId"));
        }
        String skipped = map.questions().stream()
                .filter(question -> "maintain".equals(question.topic()))
                .map(V3EnhancedQuestion::id)
                .findFirst()
                .orElseThrow();
        V3OpportunityMap continued = service.scan(
                primeAndSmart(), V3BasicLiveQualityAcceptanceTest.JD_INTERN, List.of(), List.of(skipped));
        assertTrue(continued.questions().stream().noneMatch(question -> skipped.equals(question.id())));
        assertFalse(continued.questions().isEmpty());
        assertEquals(before, snapshotUploads(uploads));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(
                tempDir.resolve("opportunity-map.json").toFile(), map);
        assertTrue(Files.size(tempDir.resolve("opportunity-map.json")) > 20);
    }

    @Test
    void confirmedMaintainAttachesOnlyToExactEntry() {
        ClaimLedger ledger = ClaimLedger.from(primeAndSmart());
        ResumeEvidenceItem row = new ResumeEvidenceItem();
        row.setId(41L);
        row.setEntryKey(PRIME);
        row.setAnswerStatus(AnswerStatus.CONFIRMED_USED);
        row.setActionText("maintained the AWS deployment with weekly patching");
        row.setResultText("");
        row.setMetricText("");
        row.setContextText("");
        row.setTopicLabel("maintain");
        V3UserEvidence confirmed = V3UserEvidenceAdapter.from(row);
        V3UserEvidence rejected = new V3UserEvidence(
                42L, PRIME, AnswerStatus.CONFIRMED_NOT_USED, "", "", "", "", "Kubernetes");
        V3UserEvidence general = new V3UserEvidence(
                43L, ResumeEntryCatalog.GENERAL, AnswerStatus.CONFIRMED_USED,
                "maintained a personal blog", "", "", "", "maintain");
        V3UserEvidence wrongKey = new V3UserEvidence(
                44L, "PROJECT:prime", AnswerStatus.CONFIRMED_USED,
                "maintained something else", "", "", "", "maintain");
        V3UserEvidence otherEntry = new V3UserEvidence(
                45L, SMART, AnswerStatus.CONFIRMED_USED,
                "should not leak to prime", "", "", "", "ghost");
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(
                ledger, List.of(confirmed, rejected, general, wrongKey, otherEntry));
        V3EntryFactGraph prime = graphs.entry(PRIME);
        V3EntryFactGraph smart = graphs.entry(SMART);
        assertTrue(prime.factsOf(V3FactKind.USER_CONFIRMED).stream().anyMatch(fact -> Long.valueOf(41L).equals(fact.evidenceItemId())));
        assertTrue(prime.factsOf(V3FactKind.USER_REJECTED).stream().anyMatch(fact -> Long.valueOf(42L).equals(fact.evidenceItemId())));
        assertFalse(prime.facts().stream().anyMatch(fact -> Long.valueOf(43L).equals(fact.evidenceItemId())));
        assertFalse(prime.facts().stream().anyMatch(fact -> Long.valueOf(44L).equals(fact.evidenceItemId())));
        assertFalse(prime.facts().stream().anyMatch(fact -> Long.valueOf(45L).equals(fact.evidenceItemId())));
        assertTrue(smart.facts().stream().anyMatch(fact -> Long.valueOf(45L).equals(fact.evidenceItemId())));
        assertFalse(smart.facts().stream().anyMatch(fact -> Long.valueOf(41L).equals(fact.evidenceItemId())));
        assertEquals(2, graphs.unlinkedEvidence().size());

        List<String> primeMaintained = List.of(
                "Lead and collaborate with a friend to develop a shopping website",
                "Deployed and maintained the initial version of the project on AWS");
        List<String> smartMaintained = List.of(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Deployed and maintained the application on AWS");
        assertFalse(V3ActionObjectProvenanceGate.hasMaintainHallucination(prime, primeMaintained));
        assertTrue(V3ActionObjectProvenanceGate.hasMaintainHallucination(smart, smartMaintained));
        assertEquals(V3ValidationDecision.INTRODUCED_RELATION,
                V3ActionObjectProvenanceGate.check(smart, smartMaintained).reasonCode());
        assertTrue(ActionConceptRegistry.contains(confirmed.combinedText(), ActionConceptRegistry.MAINTAIN));

        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(
                primeAndSmart(), V3BasicLiveQualityAcceptanceTest.JD_INTERN, List.of(confirmed));
        assertEquals(0, questionsFor(map, PRIME).stream().filter(question -> "maintain".equals(question.topic())).count());
    }

    @Test
    void pdfParentAndCanonicalChildShareCanonicalEntryKeys() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, tempDir.resolve("child.docx"));
        ResumeDocumentServiceImpl parser = new ResumeDocumentServiceImpl();
        List<ResumeDocumentNode> childNodes = parser.parseDocx(canonical);
        ClaimLedger childLedger = ClaimLedger.from(childNodes);
        ClaimLedger parentLike = ClaimLedger.from(childNodes);
        CanonicalEntryKeyResolverIndex(childLedger);
        V3ResumeFactGraphs childGraphs = V3EntryFactGraphFactory.from(childLedger);
        V3ResumeFactGraphs parentGraphs = V3EntryFactGraphFactory.from(parentLike);
        assertNotNull(childGraphs.entry(PRIME));
        assertNotNull(parentGraphs.entry(PRIME));
        assertEquals(parentGraphs.entry(PRIME).canonicalEntryKey(), childGraphs.entry(PRIME).canonicalEntryKey());
        assertEquals(parentGraphs.entry(SMART).canonicalEntryKey(), childGraphs.entry(SMART).canonicalEntryKey());
        V3OpportunityMap map = new V3EnhancedOpportunityService().scan(
                childNodes, V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        assertTrue(map.entries().stream().anyMatch(entry -> PRIME.equals(entry.canonicalEntryKey())));
        assertTrue(map.entries().stream().anyMatch(entry -> SMART.equals(entry.canonicalEntryKey())));
        assertEquals(0, map.writerCalls());
        assertFalse(map.wroteJobOrResume());
        assertTrue(map.questions().size() <= 5);
        for (V3OpportunityEntry entry : map.entries()) {
            assertTrue(questionsFor(map, entry.canonicalEntryKey()).size() <= 3);
        }
    }

    @Test
    void scanDoesNotMutateResumeNodes() {
        List<ResumeDocumentNode> nodes = primeAndSmart();
        List<String> before = nodes.stream().map(ResumeDocumentNode::getText).toList();
        new V3EnhancedOpportunityService().scan(nodes, V3BasicLiveQualityAcceptanceTest.JD_INTERN);
        assertEquals(before, nodes.stream().map(ResumeDocumentNode::getText).toList());
    }

    private static void CanonicalEntryKeyResolverIndex(ClaimLedger ledger) {
        aijob.util.CanonicalEntryKeyResolver.Index index = aijob.util.CanonicalEntryKeyResolver.index(ledger);
        assertTrue(index.containsKey(PRIME));
        assertTrue(index.containsKey(SMART));
    }

    private static V3OpportunityEntry entry(V3OpportunityMap map, String key) {
        return map.entries().stream().filter(item -> key.equals(item.canonicalEntryKey())).findFirst().orElseThrow();
    }

    private static List<V3EnhancedQuestion> questionsFor(V3OpportunityMap map, String key) {
        return map.questions().stream().filter(question -> key.equals(question.canonicalEntryKey())).toList();
    }

    private static boolean containsTopic(V3OpportunityMap map, String topic) {
        String needle = topic.toLowerCase(Locale.ROOT);
        return map.questions().stream().anyMatch(question ->
                question.topic().toLowerCase(Locale.ROOT).contains(needle)
                        || question.promptEn().toLowerCase(Locale.ROOT).contains(needle)
                        || question.promptZh().toLowerCase(Locale.ROOT).contains(needle));
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
