package aijob.util;

import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityDetectorTest {

    @Test
    void scansEveryEligibleBulletIncludingDocumentation() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "user@example.com")
        );
        List<OpportunityDetector.Opportunity> scanned = OpportunityDetector.scan(nodes, "Java Spring Boot REST");
        List<String> ids = scanned.stream().map(OpportunityDetector.Opportunity::nodeId).toList();
        assertEquals(List.of("p-1", "p-2", "p-3"), ids);
        assertEquals(OpportunityDetector.TENSE, type(scanned, "p-1"));
        assertEquals(OpportunityDetector.GRAMMAR, type(scanned, "p-2"));
        assertTrue(OpportunityDetector.needsGeneration(scanned.get(0)));
        assertFalse(ids.contains("p-4"));
    }

    private static String type(List<OpportunityDetector.Opportunity> scanned, String nodeId) {
        return scanned.stream()
                .filter(item -> nodeId.equals(item.nodeId()))
                .findFirst()
                .orElseThrow()
                .type();
    }
}
