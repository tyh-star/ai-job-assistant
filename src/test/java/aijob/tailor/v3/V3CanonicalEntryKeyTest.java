package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V3CanonicalEntryKeyTest {

    @Test
    void catalogSlugIsCanonicalKeyAndLedgerIdIsNot() {
        ClaimLedger ledger = ClaimLedger.from(primeAndSmart());
        ClaimLedger.Entry prime = ledger.entryOf("p-2");
        ClaimLedger.Entry smart = ledger.entryOf("p-6");
        assertEquals("PROJECT-0", prime.entryId());
        assertEquals("PROJECT:prime-deals", V3CanonicalEntryKey.of(prime));
        assertEquals("PROJECT:smartpc-builder", V3CanonicalEntryKey.of(smart));
        assertEquals("PROJECT:prime-deals", V3CanonicalEntryKey.of(ledger, "p-2"));
        assertEquals(
                CanonicalEntryKeyResolver.slug(prime.nodes().get(0).getText(), CanonicalEntryKeyResolver.PROJECT),
                "prime-deals");
        assertEquals(CanonicalEntryKeyResolver.keyForEntry(prime), V3CanonicalEntryKey.of(prime));
        assertNotEquals(prime.entryId(), V3CanonicalEntryKey.of(prime));
    }

    @Test
    void titleEditChangesKeyAndPrefixMustNotMatch() {
        ClaimLedger original = ClaimLedger.from(primeAndSmart());
        ClaimLedger renamed = ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals Marketplace | Personal Project"),
                node("p-2", "Design and implement Java Spring Boot.")
        ));
        String before = V3CanonicalEntryKey.of(original.entryOf("p-2"));
        String after = V3CanonicalEntryKey.of(renamed.entryOf("p-2"));
        assertEquals("PROJECT:prime-deals", before);
        assertEquals("PROJECT:prime-deals-marketplace", after);
        assertNotEquals(before, after);
        assertFalse("PROJECT:prime".equals(before));
        assertFalse(before.startsWith("PROJECT:prime-deals-market"));
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(original);
        assertFalse(index.containsKey("PROJECT:prime"));
        assertTrue(index.containsKey("PROJECT:prime-deals"));
    }

    @Test
    void homonymProjectsAreNotMerged() {
        ClaimLedger ledger = ClaimLedger.from(List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | 2024"),
                node("p-2", "Built a Java checkout service."),
                node("p-3", "Prime Deals | 2023"),
                node("p-4", "Built a Python catalog.")
        ));
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);
        String first = index.keyForNodeId("p-2");
        String second = index.keyForNodeId("p-4");
        assertEquals("PROJECT:prime-deals", first);
        assertEquals("PROJECT:prime-deals#" + ledger.entryOf("p-4").entryId(), second);
        assertNotEquals(first, second);
        assertFalse(index.entry(first) == index.entry(second));
        assertFalse(ledger.sameEntry("p-2", "p-4"));
    }

    public static List<ResumeDocumentNode> primeAndSmart() {
        return List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Prime Deals | Personal Project | Sept 2024 - Present"),
                node("p-2", "Design and implement the application using Java Spring Boot with an MVC pattern."),
                node("p-3", "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                node("p-4", "Successfully deployed the initial version of the project onto AWS"),
                node("p-5", "SmartPC Builder | York University | Jan 2025 - Mar 2025"),
                node("p-6", "Developed a Java Maven project that helps users select optimal components within a given budget"),
                node("p-7", "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)")
        );
    }

    public static ResumeDocumentNode node(String id, String text) {
        return new ResumeDocumentNode(id, "PARAGRAPH", text);
    }
}
