package aijob.util;

import aijob.dto.RewriteIntent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeMaterialFactsTest {

    private static final String MAVEN_ORIGINAL =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String ALGORITHM_ORIGINAL =
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)";
    private static final String PARTIAL_ALGORITHM =
            "Implemented an algorithm for dynamic component suggestions (CPU, GPU, RAM) within a Java Maven project, using OOP principles for modularity.";
    private static final String FULL_MERGE =
            "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.";
    private static final String PLANNING_ORIGINAL =
            "Define project scope, clarify requirements and utilized UML Class Diagram for planning";
    private static final String REST_ORIGINAL =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String REST_WITH_PROJECT_WORD =
            "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart management, successfully deploying the project on AWS.";
    private static final String AWS_ORIGINAL =
            "Successfully deployed the initial version of the project onto AWS";

    @Test
    void partialAlgorithmDoesNotFullySubsumeMavenHome() {
        assertEquals(
                ConservativeMaterialFacts.Status.PARTIALLY_USED,
                ConservativeMaterialFacts.coverage(MAVEN_ORIGINAL, PARTIAL_ALGORITHM));
        assertFalse(ConservativeMaterialFacts.fullySubsumes(MAVEN_ORIGINAL, PARTIAL_ALGORITHM));
        List<String> keys = ConservativeMaterialFacts.distinctive(
                ConservativeMaterialFacts.extract(MAVEN_ORIGINAL)).stream().map(ConservativeMaterialFacts.Fact::key).toList();
        assertTrue(keys.contains("user:select"), keys.toString());
        assertTrue(keys.contains("task:budget") || keys.stream().anyMatch(key -> key.contains("budget")), keys.toString());
        assertFalse(ConservativeMaterialFacts.covers(
                ConservativeMaterialFacts.extract(MAVEN_ORIGINAL).stream()
                        .filter(fact -> "user:select".equals(fact.key()))
                        .findFirst()
                        .orElseThrow(),
                PARTIAL_ALGORITHM));
    }

    @Test
    void fullMergeSubsumesMavenAndAlgorithmHomes() {
        assertTrue(ConservativeMaterialFacts.fullySubsumes(MAVEN_ORIGINAL, FULL_MERGE), missing(MAVEN_ORIGINAL, FULL_MERGE));
        assertTrue(ConservativeMaterialFacts.fullySubsumes(ALGORITHM_ORIGINAL, FULL_MERGE), missing(ALGORITHM_ORIGINAL, FULL_MERGE));
    }

    @Test
    void genericProjectTokenDoesNotCountAsPlanningSupport() {
        assertFalse(ConservativeMaterialFacts.contributesConcreteFact(
                REST_ORIGINAL, PLANNING_ORIGINAL, REST_WITH_PROJECT_WORD));
        assertEquals(
                ConservativeMaterialFacts.Status.UNUSED,
                ConservativeMaterialFacts.coverage(PLANNING_ORIGINAL, REST_WITH_PROJECT_WORD));
        assertTrue(ConservativeMaterialFacts.contributesConcreteFact(
                REST_ORIGINAL, AWS_ORIGINAL, REST_WITH_PROJECT_WORD));
    }

    @Test
    void userSelectCoversAssistAndAidingParaphrases() {
        ConservativeMaterialFacts.Fact userSelect = ConservativeMaterialFacts.extract(MAVEN_ORIGINAL).stream()
                .filter(fact -> "user:select".equals(fact.key()))
                .findFirst()
                .orElseThrow();
        assertTrue(ConservativeMaterialFacts.covers(
                userSelect,
                "Developed a Java Maven project applying OOP principles to assist users in choosing optimal components within a budget."));
        assertTrue(ConservativeMaterialFacts.covers(
                userSelect,
                "Implemented an algorithm in a Java Maven project to dynamically suggest CPU, GPU, and RAM components, aiding users in selecting optimal parts within budget constraints."));
        assertEquals(
                ConservativeMaterialFacts.Status.FULLY_SUBSUMED,
                ConservativeMaterialFacts.coverage(
                        MAVEN_ORIGINAL,
                        "Developed a Java Maven project applying OOP principles to assist users in choosing optimal components within a budget."));
    }

    @Test
    void ramDoesNotMatchInsideProgramming() {
        ConservativeMaterialFacts.Fact ram = ConservativeMaterialFacts.extract(ALGORITHM_ORIGINAL).stream()
                .filter(fact -> fact.aliases().stream().anyMatch(alias -> "ram".equalsIgnoreCase(alias)))
                .findFirst()
                .orElseThrow();
        assertFalse(ConservativeMaterialFacts.covers(
                ram,
                "Developed a Java Maven project applying Object-Oriented Programming principles for modularity."));
        assertFalse(ConservativeMaterialFacts.contributesConcreteFact(
                MAVEN_ORIGINAL,
                ALGORITHM_ORIGINAL,
                "Developed a Java Maven project that helps users select optimal components within a budget, applying Object-Oriented Programming principles for modularity."));
    }

    @Test
    void complementaryHomesEachKeepUniqueFacts() {
        String mavenHome = "Developed a Java Maven project that helps users select optimal components within a given budget.";
        String algorithmHome = "Implemented an algorithm that dynamically suggests CPU, GPU, and RAM components using OOP.";
        assertFalse(ConservativeMaterialFacts.uniqueKeys(mavenHome, List.of(algorithmHome)).isEmpty());
        assertFalse(ConservativeMaterialFacts.uniqueKeys(algorithmHome, List.of(mavenHome)).isEmpty());
        assertEquals(1.0, ConservativeMaterialFacts.batchRetention(
                List.of(MAVEN_ORIGINAL, ALGORITHM_ORIGINAL),
                List.of(mavenHome, algorithmHome)), 0.001);
    }

    private static String missing(String original, String rewritten) {
        List<String> missing = new ArrayList<>();
        for (ConservativeMaterialFacts.Fact fact : ConservativeMaterialFacts.distinctive(
                ConservativeMaterialFacts.extract(original))) {
            if (!ConservativeMaterialFacts.covers(fact, rewritten)) {
                missing.add(fact.key() + fact.aliases());
            }
        }
        return missing.toString();
    }
}
