package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.UpliftType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeBatchSelectorTest {

    @Test
    void keepsHigherScoreWhenSuggestionsAreHighlySimilar() {
        ConservativeBatchSelector.PlanPick low = pick(
                "p-16", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 80,
                ConservativeCandidateHardGateTest.PRIME_GOLD, false);
        ConservativeBatchSelector.PlanPick high = pick(
                "p-17", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                ConservativeCandidateHardGateTest.PRIME_GOLD, false);
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of(low, high));
        assertEquals(1, selection.selected().size());
        assertEquals("p-17", selection.selected().get(0).change().getNodeId());
        assertTrue(selection.dropped().stream().anyMatch(item ->
                "p-16".equals(item.getNodeId())
                        && RejectedTailorChange.LOW_VALUE_REWRITE.equals(item.getReasonCode())));
    }

    @Test
    void grammarOnlyCappedAtOne() {
        ConservativeBatchSelector.PlanPick first = pick(
                "p-1", "PROJECT:a", RewriteIntent.GRAMMAR_FIX, 80,
                "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart operations.",
                true);
        ConservativeBatchSelector.PlanPick second = pick(
                "p-2", "PROJECT:b", RewriteIntent.GRAMMAR_FIX, 82,
                "Integrated RESTful APIs to handle checkout flows, inventory updates, and order operations.",
                true);
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of(first, second));
        assertEquals(2, selection.selected().size());
        assertTrue(selection.dropped().isEmpty());
    }

    @Test
    void emptyPicksAreCorrectNoOp() {
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of());
        assertTrue(selection.selected().isEmpty());
        assertTrue(selection.dropped().isEmpty());
    }

    @Test
    void p21OopAndP23AlgorithmBothEnterBatchWithDistinctJobs() {
        ConservativeBatchSelector.PlanPick maven = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 88,
                MAVEN_ORIGINAL, P21_HOME_GOLD, false, List.of("p-21"));
        ConservativeBatchSelector.PlanPick algorithm = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 86,
                ALGORITHM_ORIGINAL, P23_HOME_GOLD, false, List.of("p-23"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(
                List.of(maven, algorithm),
                new ConservativeBatchSelector.Context(List.of(), Set.of("p-21", "p-23"), Set.of()));
        assertEquals(2, selection.selected().size(), selection.dropped().toString());
        ConservativeBatchSelector.PlanPick p21 = byNode(selection, "p-21");
        ConservativeBatchSelector.PlanPick p23 = byNode(selection, "p-23");
        assertEquals(P21_HOME_GOLD, p21.change().getNewText());
        assertEquals(P23_HOME_GOLD, p23.change().getNewText());
        String p21Lower = p21.change().getNewText().toLowerCase(java.util.Locale.ROOT);
        String p23Lower = p23.change().getNewText().toLowerCase(java.util.Locale.ROOT);
        assertFalse(p21Lower.contains("cpu") || p21Lower.contains("gpu") || p21Lower.contains("ram"));
        assertFalse(p21Lower.contains("oop"));
        assertFalse(p23Lower.contains("oop"));
        assertTrue(p23Lower.contains("cpu") && p23Lower.contains("gpu") && p23Lower.contains("ram"));
        assertTrue(p23Lower.contains("algorithm"));
    }

    @Test
    void fullySubsumingMergeDropsReservedTargetWhenItIsTheOnlyAlt() {
        ConservativeBatchSelector.PlanPick merge = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 92,
                MAVEN_ORIGINAL, FULL_MERGE, false, List.of("p-21", "p-23"));
        ConservativeBatchSelector.PlanPick algorithm = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 84,
                ALGORITHM_ORIGINAL, ALGORITHM_HOME, false, List.of("p-23"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of(merge, algorithm));
        assertEquals(1, selection.selected().size());
        assertEquals("p-21", selection.selected().get(0).change().getNodeId());
        assertTrue(selection.dropped().stream().anyMatch(item -> "p-23".equals(item.getNodeId())));
    }

    @Test
    void partialSupportingKeepsBothComplementaryHomes() {
        ConservativeBatchSelector.PlanPick maven = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 84,
                MAVEN_ORIGINAL, MAVEN_HOME, false, List.of("p-21"));
        ConservativeBatchSelector.PlanPick algorithm = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 86,
                ALGORITHM_ORIGINAL, PARTIAL_ALGORITHM, false, List.of("p-23", "p-21", "p-22"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of(maven, algorithm));
        assertEquals(2, selection.selected().size());
        assertTrue(selection.selected().stream().anyMatch(pick -> "p-21".equals(pick.change().getNodeId())));
        assertTrue(selection.selected().stream().anyMatch(pick -> "p-23".equals(pick.change().getNodeId())));
        assertEquals(1.0, ConservativeMaterialFacts.batchRetention(
                List.of(MAVEN_ORIGINAL, ALGORITHM_ORIGINAL),
                selection.selected().stream().map(pick -> pick.change().getNewText()).toList()), 0.001);
    }

    @Test
    void complementaryOopHomeSurvivesWhenAlgorithmAlsoKeepsMavenFacts() {
        ConservativeBatchSelector.PlanPick maven = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 85,
                MAVEN_ORIGINAL, MAVEN_OOP_HOME, false, List.of("p-21", "p-22"));
        ConservativeBatchSelector.PlanPick algorithm = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 87,
                ALGORITHM_ORIGINAL, ALGORITHM_WITH_MAVEN_FACTS, false, List.of("p-23", "p-21"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(List.of(maven, algorithm));
        assertEquals(2, selection.selected().size(), selection.dropped().toString());
        assertTrue(selection.selected().stream().anyMatch(pick -> "p-21".equals(pick.change().getNodeId())));
        assertTrue(selection.selected().stream().anyMatch(pick -> "p-23".equals(pick.change().getNodeId())));
        assertEquals(1.0, ConservativeMaterialFacts.batchRetention(
                List.of(MAVEN_ORIGINAL, ALGORITHM_ORIGINAL),
                selection.selected().stream().map(pick -> pick.change().getNewText()).toList()), 0.001);
    }

    private static final String MAVEN_ORIGINAL =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String MAVEN_HOME =
            "Developed a Java Maven project that helps users select optimal components within a given budget.";
    private static final String ALGORITHM_ORIGINAL =
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)";
    private static final String ALGORITHM_HOME =
            "Implemented an algorithm that dynamically suggests CPU, GPU, and RAM components using OOP.";
    private static final String PARTIAL_ALGORITHM =
            "Implemented an algorithm for dynamic component suggestions (CPU, GPU, RAM) within a Java Maven project, using OOP principles for modularity.";
    private static final String FULL_MERGE =
            "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.";
    private static final String MAVEN_OOP_HOME =
            "Developed a Java Maven project applying OOP principles to assist users in choosing optimal components within a budget.";
    private static final String ALGORITHM_WITH_MAVEN_FACTS =
            "Implemented an algorithm in a Java Maven project to dynamically suggest CPU, GPU, and RAM components, aiding users in selecting optimal parts within budget constraints.";
    private static final String P21_HOME_GOLD =
            "Developed a Maven-based Java project to help users select optimal components within a specified budget.";
    private static final String P21_OOP_GOLD =
            "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.";
    private static final String P23_HOME_GOLD =
            "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.";

    @Test
    void mutualEnrichmentPrefersClarityPlusHomeRewrite() {
        ConservativeBatchSelector.PlanPick springGold = pick(
                "p-16", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 88,
                SPRING_ORIGINAL, ConservativeCandidateHardGateTest.PRIME_GOLD, false, List.of("p-16", "p-17"));
        ConservativeBatchSelector.PlanPick springClarity = pick(
                "p-16", "PROJECT:prime", RewriteIntent.CLARITY_IMPROVEMENT, 80,
                SPRING_ORIGINAL, SPRING_CLARITY, false, List.of("p-16"));
        ConservativeBatchSelector.PlanPick restGold = pick(
                "p-17", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                REST_ORIGINAL, REST_PACKED_GOLD, false, List.of("p-17", "p-16"));
        ConservativeBatchSelector.PlanPick restHome = pick(
                "p-17", "PROJECT:prime", RewriteIntent.CLARITY_IMPROVEMENT, 82,
                REST_ORIGINAL, REST_HOME, false, List.of("p-17"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(
                List.of(springGold, springClarity, restGold, restHome));

        assertEquals(2, selection.selected().size());
        assertEquals(0, ConservativeBatchSelector.circularSupportingCount(selection.selected()));
        assertEquals(0, ConservativeBatchSelector.semanticOverlapCount(selection.selected()));
        ConservativeBatchSelector.PlanPick spring = byNode(selection, "p-16");
        ConservativeBatchSelector.PlanPick rest = byNode(selection, "p-17");
        assertFalse(coversRest(spring.change().getNewText()));
        assertTrue(coversRest(rest.change().getNewText()));
        assertEquals(SPRING_CLARITY, spring.change().getNewText());
        assertEquals(REST_HOME, rest.change().getNewText());
    }

    @Test
    void mutualEnrichmentWithoutAltKeepsHigherCoverage() {
        ConservativeBatchSelector.PlanPick springGold = pick(
                "p-16", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 88,
                SPRING_ORIGINAL, ConservativeCandidateHardGateTest.PRIME_GOLD, false, List.of("p-16", "p-17"));
        ConservativeBatchSelector.PlanPick restGold = pick(
                "p-17", "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                REST_ORIGINAL, REST_PACKED_GOLD, false, List.of("p-17", "p-16"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(
                List.of(springGold, restGold));

        assertEquals(1, selection.selected().size());
        assertEquals(0, ConservativeBatchSelector.circularSupportingCount(selection.selected()));
        assertEquals("p-16", selection.selected().get(0).change().getNodeId());
        assertTrue(selection.dropped().stream().anyMatch(item -> "p-17".equals(item.getNodeId())));
    }

    private static final String SPRING_ORIGINAL =
            "Design and implement a Java Spring Boot application using the MVC pattern.";
    private static final String SPRING_CLARITY =
            "Designed and implemented a Java Spring Boot application using the MVC architecture.";
    private static final String REST_ORIGINAL =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String REST_HOME =
            "Integrated RESTful APIs for authentication, product updates, and shopping cart workflows.";
    private static final String REST_PACKED_GOLD =
            "Integrated RESTful APIs for authentication, product updates, and shopping cart workflows using Java Spring Boot.";

    private static ConservativeBatchSelector.PlanPick byNode(
            ConservativeBatchSelector.Selection selection, String nodeId) {
        return selection.selected().stream()
                .filter(pick -> nodeId.equals(pick.change().getNodeId()))
                .findFirst()
                .orElseThrow();
    }

    private static boolean coversRest(String text) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("rest")
                && (lower.contains("auth") || lower.contains("authentication"))
                && (lower.contains("product") || lower.contains("cart"));
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String newText,
            boolean grammarOnly) {
        return pick(nodeId, entryKey, intent, total, newText, newText, grammarOnly, List.of(nodeId));
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String originalText,
            String newText,
            boolean grammarOnly,
            List<String> supportingIds) {

        ConservativeRewritePlan plan = new ConservativeRewritePlan(
                nodeId,
                nodeId,
                entryKey,
                List.of(),
                intent,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("java"),
                "rewrite",
                170,
                "BULLET",
                List.of(nodeId),
                "",
                total,
                ""
        );
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(originalText);
        change.setNewText(newText);
        change.setUpliftType(grammarOnly ? UpliftType.GRAMMAR_FIX : UpliftType.SUBSTANTIVE_REWRITE);
        ConservativeCandidateLocalScorer.Score score = new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, total, 60, total - 60, true, true,
                change.getUpliftType(), grammarOnly, intent, null);
        return new ConservativeBatchSelector.PlanPick(plan, change, score, supportingIds);
    }
}
