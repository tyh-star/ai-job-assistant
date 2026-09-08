package aijob.util;

import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ClaimLedger grouping tests. Project names such as "Prime Deals" below are
 * synthetic fixture labels, not real products. */
class ClaimLedgerTest {

    @Test
    void groupsBulletsUnderProjectHeadersAndRejectsCrossProjectTech() {
        List<ResumeDocumentNode> nodes = sampleProjects();
        ClaimLedger ledger = ClaimLedger.from(nodes);

        assertTrue(ledger.sameEntry("p-2", "p-3"));
        assertFalse(ledger.sameEntry("p-2", "p-5"));
        assertEquals("PROJECT", ledger.entryOf("p-2").sectionRole());
        assertTrue(ledger.coversTech(
                "Designed and implemented a Java Spring Boot application using the MVC architecture.",
                "p-2"
        ));
        assertFalse(ledger.coversTech(
                "Implemented a Java-based recommendation algorithm for CPU, GPU, and RAM.",
                "p-2"
        ));
        assertTrue(ledger.coversTech(
                "Implemented a Java-based recommendation algorithm that suggests CPU, GPU, and RAM components based on users’ budgets.",
                "p-5"
        ));
        assertFalse(ledger.coversNumbers(
                "Implemented an algorithm that reduced cost by 40%.",
                "p-5"
        ));
        assertTrue(ledger.coversNumbers(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "p-5"
        ));
    }

    @Test
    void dutyBulletsStartingWithAdverbsStayInTheSameProject() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Catalog Service | 2024"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "Successfully deployed the service onto AWS."),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "Maintained production workloads and patched runtime issues.")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        assertTrue(ledger.sameEntry("p-2", "p-3"));
        assertTrue(ledger.sameEntry("p-1", "p-2"));
    }

    @Test
    void separatesTwoExperienceJobs() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Backend Intern | Acme | Jan 2024 – Present"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "●   Built REST APIs in Java."),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "Data Analyst | Other Co | Jan 2023 – Dec 2023"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "●   Deployed backend services on AWS for production workloads.")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        assertFalse(ledger.sameEntry("p-2", "p-4"));
        assertTrue(ledger.coversTech("Built REST APIs in Java.", "p-2"));
        assertFalse(ledger.coversTech("Built REST APIs in Java on AWS.", "p-2"));
    }

    @Test
    void skillsAreNotPartOfProjectEntries() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "TECHNICAL SKILLS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Java, Docker, Kubernetes"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "Store App | 2024"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "●   Built a Java Spring Boot application.")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        assertFalse(ledger.coversTech("Built a Java Spring Boot application with Docker.", "p-4"));
        assertTrue(ledger.entryOf("p-1") == null || !"PROJECT".equals(ledger.entryOf("p-1").sectionRole()));
    }

    @Test
    void projectUrlBelongsToEntryAndEducationIsNotAProject() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "PROJECTS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Prime Deals | Personal Project | Sept 2024 - Present"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Define project scope, clarify requirements and utilized UML Class Diagram for planning"),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "https://atlas.example.com"),
                new ResumeDocumentNode("p-5", "PARAGRAPH", "EDUCATION"),
                new ResumeDocumentNode("p-6", "PARAGRAPH", "Example University")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        assertTrue(ledger.sameEntry("p-1", "p-2"));
        assertTrue(ledger.sameEntry("p-2", "p-3"));
        assertTrue(ledger.sameEntry("p-3", "p-4"));
        assertTrue(ledger.entryText("p-3").contains("https://"));
        assertTrue(ledger.entryOf("p-6") == null || !"PROJECT".equals(ledger.entryOf("p-6").sectionRole()));
        assertTrue(ledger.isOngoing("p-3"));
        assertFalse(ledger.isEnded("p-3"));
    }

    private List<ResumeDocumentNode> sampleProjects() {
        return List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Online Store | Jan 2024"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "Component Advisor | 2023"),
                new ResumeDocumentNode("p-5", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget"),
                new ResumeDocumentNode("p-6", "PARAGRAPH",
                        "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)")
        );
    }
}
