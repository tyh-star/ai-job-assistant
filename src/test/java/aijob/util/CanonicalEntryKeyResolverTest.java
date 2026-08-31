package aijob.util;

import aijob.dto.EntryOption;
import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalEntryKeyResolverTest {

    @Test
    void catalogSlugAndResolverKeyStayIdentical() {
        String heading = "Prime Deals | Personal Project | Sept 2024 - Present";
        assertEquals(
                ResumeEntryCatalog.slug(heading, ResumeEntryCatalog.PROJECT),
                CanonicalEntryKeyResolver.slug(heading, CanonicalEntryKeyResolver.PROJECT));
        assertEquals(
                "PROJECT:prime-deals",
                CanonicalEntryKeyResolver.key(heading, CanonicalEntryKeyResolver.PROJECT));
        assertEquals(
                ResumeEntryCatalog.PROJECT + ":" + ResumeEntryCatalog.slug(heading, ResumeEntryCatalog.PROJECT),
                CanonicalEntryKeyResolver.key(heading, ResumeEntryCatalog.PROJECT));
    }

    @Test
    void ledgerSequentialIdIsNeverAnEvidenceKey() {
        ClaimLedger ledger = ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | 2024"),
                node("p-2", "Built a Java checkout.")
        ));
        ClaimLedger.Entry entry = ledger.entryOf("p-2");
        assertEquals("PROJECT-0", entry.entryId());
        assertEquals("PROJECT:prime-deals", CanonicalEntryKeyResolver.keyForEntry(entry));
        assertNotEquals(entry.entryId(), CanonicalEntryKeyResolver.keyForEntry(entry));
        assertFalse(CanonicalEntryKeyResolver.index(ledger).containsKey("PROJECT-0"));
    }

    @Test
    void similarTitlesDoNotMatchAndOldEvidenceDoesNotFuzzyAttach() {
        String original = CanonicalEntryKeyResolver.key("Prime Deals", ResumeEntryCatalog.PROJECT);
        String edited = CanonicalEntryKeyResolver.key("Prime Deals Marketplace", ResumeEntryCatalog.PROJECT);
        String neighbor = CanonicalEntryKeyResolver.key("Prime Deal", ResumeEntryCatalog.PROJECT);
        assertEquals("PROJECT:prime-deals", original);
        assertEquals("PROJECT:prime-deals-marketplace", edited);
        assertEquals("PROJECT:prime-deal", neighbor);
        assertNotEquals(original, edited);
        assertNotEquals(original, neighbor);
        assertFalse(original.equals("PROJECT:prime"));
        ClaimLedger renamed = ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals Marketplace | 2024"),
                node("p-2", "Built a Java checkout.")
        ));
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(renamed);
        assertTrue(index.containsKey(edited));
        assertFalse(index.containsKey(original));
    }

    @Test
    void duplicateTitlesDisambiguateInResolverAndCatalogKeepsFirstOnly() {
        List<ResumeDocumentNode> nodes = List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | 2024"),
                node("p-2", "Built a Java checkout."),
                node("p-3", "Prime Deals | 2023"),
                node("p-4", "Built a Python catalog.")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        String first = index.keyForNodeId("p-2");
        String second = index.keyForNodeId("p-4");
        assertEquals("PROJECT:prime-deals", first);
        assertEquals("PROJECT:prime-deals#" + ledger.entryOf("p-4").entryId(), second);
        List<String> catalogKeys = ResumeEntryCatalog.options(nodes, null).stream()
                .map(EntryOption::getEntryKey)
                .toList();
        assertEquals(1, catalogKeys.stream().filter("PROJECT:prime-deals"::equals).count());
        assertFalse(catalogKeys.contains(second));
    }

    @Test
    void experienceSimilarTitlesStayDistinct() {
        String original = CanonicalEntryKeyResolver.key("Backend Intern Acme", ResumeEntryCatalog.EXPERIENCE);
        String edited = CanonicalEntryKeyResolver.key("Backend Intern Acme Labs", ResumeEntryCatalog.EXPERIENCE);
        assertEquals("EXPERIENCE:backend-intern-acme", original);
        assertEquals("EXPERIENCE:backend-intern-acme-labs", edited);
        assertNotEquals(original, edited);
    }

    private static ResumeDocumentNode node(String id, String text) {
        return new ResumeDocumentNode(id, "PARAGRAPH", text);
    }
}
