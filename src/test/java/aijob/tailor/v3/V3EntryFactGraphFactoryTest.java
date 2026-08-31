package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static aijob.tailor.v3.V3CanonicalEntryKeyTest.primeAndSmart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EntryFactGraphFactoryTest {

    @Test
    void basicAndEnhancedReadTheSameResumeFacts() {
        aijob.util.ClaimLedger ledger = aijob.util.ClaimLedger.from(primeAndSmart());
        V3ResumeFactGraphs basic = V3EntryFactGraphFactory.from(ledger);
        V3ResumeFactGraphs enhancedEmpty = V3EntryFactGraphFactory.from(ledger, List.of());
        V3EntryFactGraph primeBasic = basic.entry("PROJECT:prime-deals");
        V3EntryFactGraph primeEnhanced = enhancedEmpty.entry("PROJECT:prime-deals");
        assertNotNull(primeBasic);
        assertEquals(primeBasic.facts(), primeEnhanced.facts());
        assertTrue(primeBasic.keysOf(V3FactKind.TECHNOLOGY).stream().anyMatch(key -> key.contains("spring") || key.contains("aws") || key.contains("java")));
        V3EntryFactGraph smart = basic.entry("PROJECT:smartpc-builder");
        assertNotNull(smart);
        assertTrue(smart.originalEntryText().toLowerCase().contains("maven"));
        assertFalse(primeBasic.originalEntryText().toLowerCase().contains("maven"));
        assertFalse(smart.originalEntryText().toLowerCase().contains("spring boot"));
    }

    @Test
    void confirmedAndRejectedEvidenceAttachOnlyByExactKey() {
        aijob.util.ClaimLedger ledger = aijob.util.ClaimLedger.from(primeAndSmart());
        V3UserEvidence confirmed = new V3UserEvidence(
                11L,
                "PROJECT:prime-deals",
                AnswerStatus.CONFIRMED_USED,
                "load tested checkout",
                "handled 200 requests per second",
                "200",
                "",
                "throughput"
        );
        V3UserEvidence rejected = new V3UserEvidence(
                12L,
                "PROJECT:prime-deals",
                AnswerStatus.CONFIRMED_NOT_USED,
                "",
                "",
                "",
                "",
                "Kubernetes"
        );
        V3UserEvidence wrongPrefix = new V3UserEvidence(
                13L,
                "PROJECT:prime",
                AnswerStatus.CONFIRMED_USED,
                "should not attach",
                "",
                "999",
                "",
                "ghost"
        );
        V3UserEvidence general = new V3UserEvidence(
                14L,
                aijob.util.ResumeEntryCatalog.GENERAL,
                AnswerStatus.CONFIRMED_USED,
                "unlinked metric",
                "",
                "42",
                "",
                "general"
        );
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(
                ledger, List.of(confirmed, rejected, wrongPrefix, general));
        V3EntryFactGraph prime = graphs.entry("PROJECT:prime-deals");
        assertTrue(prime.factsOf(V3FactKind.USER_CONFIRMED).stream().anyMatch(fact -> fact.evidenceItemId() == 11L));
        assertTrue(prime.factsOf(V3FactKind.USER_REJECTED).stream().anyMatch(fact -> fact.evidenceItemId() == 12L));
        assertTrue(prime.hasFactKey("number:200"));
        assertFalse(prime.facts().stream().anyMatch(fact -> Long.valueOf(13L).equals(fact.evidenceItemId())));
        assertFalse(prime.facts().stream().anyMatch(fact -> Long.valueOf(14L).equals(fact.evidenceItemId())));
        assertEquals(2, graphs.unlinkedEvidence().size());
        V3EntryFactGraph smart = graphs.entry("PROJECT:smartpc-builder");
        assertFalse(smart.facts().stream().anyMatch(fact -> Long.valueOf(11L).equals(fact.evidenceItemId())));
        for (V3EntryFactGraph graph : graphs.entries()) {
            assertRequiredFactFields(graph);
        }
    }

    @Test
    void retryingTheSameEvidenceItemIdAddsOneUserConfirmedFact() {
        aijob.util.ClaimLedger ledger = aijob.util.ClaimLedger.from(primeAndSmart());
        V3UserEvidence first = new V3UserEvidence(
                11L,
                "PROJECT:prime-deals",
                AnswerStatus.CONFIRMED_USED,
                "load tested checkout",
                "handled 200 requests per second",
                "200",
                "",
                "throughput"
        );
        V3UserEvidence retry = new V3UserEvidence(
                11L,
                "PROJECT:prime-deals",
                AnswerStatus.CONFIRMED_USED,
                "load tested checkout after retry",
                "handled 250 requests per second",
                "250",
                "",
                "throughput"
        );
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger, List.of(first, retry));
        V3EntryFactGraph prime = graphs.entry("PROJECT:prime-deals");
        long confirmed = prime.factsOf(V3FactKind.USER_CONFIRMED).stream()
                .filter(fact -> Long.valueOf(11L).equals(fact.evidenceItemId()))
                .count();
        assertEquals(1, confirmed);
        assertEquals(1, prime.factsOf(V3FactKind.USER_CONFIRMED).size());
    }

    @Test
    void factoryDoesNotMutateLedgerOrEvidenceItems() {
        aijob.util.ClaimLedger ledger = aijob.util.ClaimLedger.from(primeAndSmart());
        List<String> textsBefore = ledger.entries().stream()
                .flatMap(entry -> entry.nodes().stream())
                .map(aijob.dto.ResumeDocumentNode::getText)
                .toList();
        List<String> idsBefore = ledger.entries().stream().map(aijob.util.ClaimLedger.Entry::entryId).toList();
        V3UserEvidence confirmed = new V3UserEvidence(
                11L,
                "PROJECT:prime-deals",
                AnswerStatus.CONFIRMED_USED,
                "load tested checkout",
                "handled 200 requests per second",
                "200",
                "",
                "throughput"
        );
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger, List.of(confirmed));
        assertEquals(textsBefore, ledger.entries().stream()
                .flatMap(entry -> entry.nodes().stream())
                .map(aijob.dto.ResumeDocumentNode::getText)
                .toList());
        assertEquals(idsBefore, ledger.entries().stream().map(aijob.util.ClaimLedger.Entry::entryId).toList());
        assertEquals(11L, confirmed.evidenceItemId());
        assertEquals("PROJECT:prime-deals", confirmed.storedEntryKey());
        assertEquals(AnswerStatus.CONFIRMED_USED, confirmed.answerStatus());
        assertTrue(graphs.entry("PROJECT:prime-deals").facts().stream()
                .anyMatch(fact -> Long.valueOf(11L).equals(fact.evidenceItemId())));
        try {
            graphs.entry("PROJECT:prime-deals").facts().add(graphs.entry("PROJECT:prime-deals").facts().get(0));
            throw new AssertionError("Fact Graph must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    private static void assertRequiredFactFields(V3EntryFactGraph graph) {
        for (V3Fact fact : graph.facts()) {
            assertEquals(graph.canonicalEntryKey(), fact.canonicalEntryKey());
            assertNotNull(fact.factType());
            assertFalse(fact.normalizedFact().isBlank());
            assertNotNull(fact.sourceNodeIds());
            assertTrue(fact.sourceType() == V3FactSource.RESUME || fact.sourceType() == V3FactSource.USER_CONFIRMED);
            assertTrue(fact.polarity() == V3FactPolarity.CONFIRMED || fact.polarity() == V3FactPolarity.REJECTED);
            assertNotNull(fact.materiality());
            if (fact.sourceType() == V3FactSource.USER_CONFIRMED) {
                assertNotNull(fact.evidenceItemId());
            }
        }
    }

    @Test
    void homonymsKeepSeparateGraphs() {
        aijob.util.ClaimLedger ledger = aijob.util.ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | 2024"),
                node("p-2", "Built a Java checkout service."),
                node("p-3", "Prime Deals | 2023"),
                node("p-4", "Built a Python catalog.")
        ));
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);
        V3EntryFactGraph first = graphs.entry("PROJECT:prime-deals");
        V3EntryFactGraph second = graphs.entry("PROJECT:prime-deals#" + ledger.entryOf("p-4").entryId());
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.originalEntryText().toLowerCase().contains("java"));
        assertFalse(first.originalEntryText().toLowerCase().contains("python"));
        assertTrue(second.originalEntryText().toLowerCase().contains("python"));
        assertFalse(second.originalEntryText().toLowerCase().contains("checkout"));
    }
}
