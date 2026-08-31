package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeTailorPlannerTest {

    @Test
    void plan_onlySelectsAllowedNodes() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "●   Design Java REST APIs for billing services."),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "user@example.com"),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "https://github.com/example"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "Software Engineer | Jan 2024 – Present"),
                new ResumeDocumentNode("p-5", "PARAGRAPH", "EDUCATION"),
                new ResumeDocumentNode("p-6", "PARAGRAPH",
                        "●   Completed coursework in operating systems.")
        );

        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(
                nodes,
                "Need Java REST APIs for billing platforms."
        );

        Set<String> ids = plans.stream()
                .map(ConservativeRewritePlan::nodeId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("p-1"), ids);
        assertFalse(ids.contains("p-0"));
        assertFalse(ids.contains("p-2"));
        assertFalse(ids.contains("p-6"));
    }

    @Test
    void plan_excludedNodeIdsAreSkippedWithoutChangingOtherRanking() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "●   Design Java REST APIs for billing services."),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "●   Build Python scripts for reporting dashboards.")
        );
        String jd = "Need Java REST APIs and Python reporting dashboards.";
        List<ConservativeRewritePlan> first = ConservativeTailorPlanner.plan(nodes, jd);
        List<ConservativeRewritePlan> continuing = ConservativeTailorPlanner.plan(
                nodes, jd, Set.of("p-1"));

        assertTrue(first.stream().anyMatch(plan -> "p-1".equals(plan.nodeId())));
        assertFalse(continuing.stream().anyMatch(plan -> "p-1".equals(plan.nodeId())));
        assertTrue(continuing.stream().anyMatch(plan -> "p-2".equals(plan.nodeId())));
    }

    @Test
    void plan_capsAtFiveNodesInRankOrder() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "●   Develop Java services for internal tools."),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "●   Build Python scripts for reporting dashboards."),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "●   Implement Kubernetes Java APIs for billing."),
                new ResumeDocumentNode("p-4", "PARAGRAPH",
                        "●   Wrote documentation for onboarding guides."),
                new ResumeDocumentNode("p-5", "PARAGRAPH",
                        "●   Maintain Java billing APIs used in production.")
        );

        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(
                nodes,
                "Need Java billing APIs."
        );

        assertTrue(plans.size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        assertEquals("p-3", plans.get(0).nodeId());
        List<String> ids = plans.stream().map(ConservativeRewritePlan::nodeId).toList();
        assertTrue(ids.contains("p-3"));
        assertTrue(plans.get(0).relevantJobTerms().contains("java"));
        assertTrue(plans.get(0).maxCharacters() > 0);
        assertEquals("EXPERIENCE_BULLET", plans.get(0).nodeRole());
        assertFalse(plans.get(0).requiredFacts().isEmpty());
    }

    @Test
    void plan_ranksImplementationAboveDocumentation() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Online Store | 2024"),
                new ResumeDocumentNode("p-2", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "Component Advisor | 2023"),
                new ResumeDocumentNode("p-5", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget"),
                new ResumeDocumentNode("p-6", "PARAGRAPH",
                        "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)"),
                new ResumeDocumentNode("p-7", "PARAGRAPH",
                        "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality")
        );

        ConservativeTailorPlanner.CoveragePage page = ConservativeTailorPlanner.nextPage(
                nodes,
                "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.",
                Set.of()
        );
        List<String> ids = page.generationPlans().stream().map(ConservativeRewritePlan::nodeId).toList();
        assertTrue(ids.size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        assertTrue(ids.contains("p-2") || page.evaluatedNodeIds().contains("p-2"));
        assertTrue(page.eligibleNodeIds().contains("p-7"));
        assertTrue(page.evaluatedNodeIds().contains("p-7")
                || page.remainingNodeIds().contains("p-7"));
        assertFalse(page.evaluatedNodeIds().isEmpty());
    }

    @Test
    void plan_prefersExperienceAndProjectOverSummaryAndSkills() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "SUMMARY OF QUALIFICATIONS"),
                new ResumeDocumentNode("p-1", "PARAGRAPH",
                        "Strong understanding of computer science fundamentals including data structures and algorithms"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "TECHNICAL SKILLS"),
                new ResumeDocumentNode("p-3", "PARAGRAPH",
                        "C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "RELEVANT PROJECTS"),
                new ResumeDocumentNode("p-5", "PARAGRAPH",
                        "Design and implement the application using Java Spring Boot with an MVC pattern."),
                new ResumeDocumentNode("p-6", "PARAGRAPH",
                        "Developed a Java Maven project that helps users select optimal components within a given budget")
        );

        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(
                nodes,
                "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs."
        );

        assertFalse(plans.isEmpty());
        assertEquals("p-5", plans.get(0).nodeId());
        assertEquals("PROJECT_BULLET", plans.get(0).nodeRole());
        assertFalse(plans.stream().anyMatch(plan -> plan.nodeId().equals("p-3")));
        assertFalse(plans.stream().anyMatch(plan -> "SKILLS".equals(plan.nodeRole())));
        ConservativeTailorPlanner.CoveragePage page = ConservativeTailorPlanner.nextPage(nodes,
                "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.", Set.of());
        assertTrue(page.eligibleNodeIds().contains("p-1"));
        assertFalse(page.eligibleNodeIds().contains("p-3"));
    }

    @Test
    void plan_excludesHeadingDateContactAndUrl() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "Jane Doe"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "user@example.com"),
                new ResumeDocumentNode("p-2", "PARAGRAPH", "https://github.com/example"),
                new ResumeDocumentNode("p-3", "PARAGRAPH", "Software Engineer | Jan 2024 – Present"),
                new ResumeDocumentNode("p-4", "PARAGRAPH", "EXPERIENCE"),
                new ResumeDocumentNode("p-5", "PARAGRAPH",
                        "●   Design Java REST APIs for billing services.")
        );

        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(
                nodes,
                "Need Java REST APIs for billing platforms."
        );

        assertEquals(List.of("p-5"), plans.stream().map(ConservativeRewritePlan::nodeId).toList());
    }
}
