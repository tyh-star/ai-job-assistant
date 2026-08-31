package aijob.util;

import aijob.dto.AnswerStatus;
import aijob.dto.EntryOption;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.tailor.v3.V3EntryFactGraph;
import aijob.tailor.v3.V3EntryFactGraphFactory;
import aijob.tailor.v3.V3Fact;
import aijob.tailor.v3.V3FactPolarity;
import aijob.tailor.v3.V3FactSource;
import aijob.tailor.v3.V3ResumeFactGraphs;
import aijob.tailor.v3.V3UserEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3YaohangFactGraphGoldenTest {

    static final String PRIME_KEY = "PROJECT:prime-deals";
    static final String SMART_KEY = "PROJECT:smartpc-builder";

    @TempDir
    Path tempDir;

    private ResumeStructure structure;
    private List<ResumeDocumentNode> childNodes;
    private ClaimLedger childLedger;

    @BeforeEach
    void parseRealPdfAndCanonicalChild() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        structure = YaohangCanonicalDocxFixture.structureFromPdf(pdf);
        Path docx = tempDir.resolve("yaohang-canonical.docx");
        YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, docx);
        childNodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        childLedger = ClaimLedger.from(childNodes);
    }

    @Test
    void parentChildBasicEnhancedAndEvidenceShareOnePrimeKeyAndOneSmartKey() {
        String parentPrime = CanonicalEntryKeyResolver.key(
                structure.getProjects().stream()
                        .filter(project -> "Prime Deals".equals(project.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getName(),
                ResumeEntryCatalog.PROJECT);
        String parentSmart = CanonicalEntryKeyResolver.key(
                structure.getProjects().stream()
                        .filter(project -> "SmartPC Builder".equals(project.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getName(),
                ResumeEntryCatalog.PROJECT);
        assertEquals(PRIME_KEY, parentPrime, "PDF parent Prime Deals key");
        assertEquals(SMART_KEY, parentSmart, "PDF parent SmartPC Builder key");

        List<String> parentCatalog = ResumeEntryCatalog.options(List.of(), structure).stream()
                .map(EntryOption::getEntryKey)
                .toList();
        List<String> childCatalog = ResumeEntryCatalog.options(childNodes, structure).stream()
                .map(EntryOption::getEntryKey)
                .toList();
        assertEquals(PRIME_KEY, parentCatalog.stream().filter(PRIME_KEY::equals).findFirst().orElseThrow());
        assertEquals(SMART_KEY, parentCatalog.stream().filter(SMART_KEY::equals).findFirst().orElseThrow());
        assertEquals(PRIME_KEY, childCatalog.stream().filter(PRIME_KEY::equals).findFirst().orElseThrow());
        assertEquals(SMART_KEY, childCatalog.stream().filter(SMART_KEY::equals).findFirst().orElseThrow());

        CanonicalEntryKeyResolver.Index childIndex = CanonicalEntryKeyResolver.index(childLedger);
        String childPrime = CanonicalEntryKeyResolver.keyForEntry(childLedger.entries().stream()
                .filter(entry -> entry.nodes() != null
                        && !entry.nodes().isEmpty()
                        && entry.nodes().get(0).getText() != null
                        && entry.nodes().get(0).getText().contains("Prime Deals"))
                .findFirst()
                .orElseThrow());
        String childSmart = CanonicalEntryKeyResolver.keyForEntry(childLedger.entries().stream()
                .filter(entry -> entry.nodes() != null
                        && !entry.nodes().isEmpty()
                        && entry.nodes().get(0).getText() != null
                        && entry.nodes().get(0).getText().contains("SmartPC Builder"))
                .findFirst()
                .orElseThrow());
        assertEquals(parentPrime, childPrime, "parent and child Prime Deals keys must be identical");
        assertEquals(parentSmart, childSmart, "parent and child SmartPC Builder keys must be identical");
        assertEquals(PRIME_KEY, childIndex.entryByKey().keySet().stream().filter(PRIME_KEY::equals).findFirst().orElseThrow());
        assertEquals(SMART_KEY, childIndex.entryByKey().keySet().stream().filter(SMART_KEY::equals).findFirst().orElseThrow());
        assertFalse(childIndex.containsKey("PROJECT:prime"));
        assertFalse(childIndex.containsKey("PROJECT:smartpc"));
        for (ClaimLedger.Entry entry : childLedger.entries()) {
            assertFalse(CanonicalEntryKeyResolver.keyForEntry(entry).equals(entry.entryId()));
        }

        V3UserEvidence evidence = new V3UserEvidence(
                77L,
                PRIME_KEY,
                AnswerStatus.CONFIRMED_USED,
                "load tested checkout",
                "handled 200 requests per second",
                "200",
                "",
                "throughput"
        );
        V3ResumeFactGraphs basic = V3EntryFactGraphFactory.from(childLedger);
        V3ResumeFactGraphs enhanced = V3EntryFactGraphFactory.from(childLedger, List.of(evidence));
        V3EntryFactGraph primeBasic = basic.entry(PRIME_KEY);
        V3EntryFactGraph primeEnhanced = enhanced.entry(PRIME_KEY);
        V3EntryFactGraph smartBasic = basic.entry(SMART_KEY);
        V3EntryFactGraph smartEnhanced = enhanced.entry(SMART_KEY);
        assertNotNull(primeBasic);
        assertNotNull(primeEnhanced);
        assertNotNull(smartBasic);
        assertNotNull(smartEnhanced);
        assertEquals(parentPrime, primeBasic.canonicalEntryKey(), "BASIC Fact Graph Prime Deals key");
        assertEquals(parentPrime, primeEnhanced.canonicalEntryKey(), "ENHANCED Fact Graph Prime Deals key");
        assertEquals(parentSmart, smartBasic.canonicalEntryKey(), "BASIC Fact Graph SmartPC Builder key");
        assertEquals(parentSmart, smartEnhanced.canonicalEntryKey(), "ENHANCED Fact Graph SmartPC Builder key");
        assertEquals(parentPrime, evidence.storedEntryKey(), "Evidence item Prime Deals key");
        assertTrue(primeEnhanced.facts().stream().anyMatch(fact -> Long.valueOf(77L).equals(fact.evidenceItemId())));
        assertFalse(smartEnhanced.facts().stream().anyMatch(fact -> Long.valueOf(77L).equals(fact.evidenceItemId())));
        assertEveryFactHasRequiredFields(primeEnhanced);
        assertEveryFactHasRequiredFields(smartEnhanced);
    }

    private static void assertEveryFactHasRequiredFields(V3EntryFactGraph graph) {
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
}
