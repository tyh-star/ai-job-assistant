package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.UpliftType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeContinueSelectorTest {

    private static final String MAVEN_ORIGINAL =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String ALGORITHM_ORIGINAL =
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)";
    private static final String FULL_MERGE =
            "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.";
    private static final String MYSQL_ORIGINAL =
            "Tuned MySQL indexes to speed up order search queries.";
    private static final String MYSQL_REWRITE =
            "Tuned MySQL indexes to reduce order search latency in production.";

    @Test
    void lockedP16P17P23AreNotSelectedAgain() {
        List<TailorChange> locked = List.of(
                change("p-16", "Built Spring Boot APIs.", "Built Spring Boot REST APIs."),
                change("p-17", "Integrated REST auth.", "Integrated REST authentication."),
                change("p-23", ALGORITHM_ORIGINAL, FULL_MERGE)
        );
        ConservativeBatchSelector.PlanPick repeat = pick(
                "p-16", "PROJECT:prime", RewriteIntent.CLARITY_IMPROVEMENT, 90,
                "Built Spring Boot APIs.", "Built Spring Boot REST APIs for checkout.");
        ConservativeBatchSelector.PlanPick next = pick(
                "p-30", "PROJECT:other", RewriteIntent.CLARITY_IMPROVEMENT, 88,
                MYSQL_ORIGINAL, MYSQL_REWRITE, List.of("p-30"));

        ConservativeBatchSelector.Selection selection = ConservativeContinueSelector.select(
                locked,
                Map.of("p-16", "PROJECT:prime", "p-17", "PROJECT:prime", "p-23", "PROJECT:smartpc"),
                List.of(repeat, next)
        );

        Set<String> ids = selection.selected().stream()
                .map(pick -> pick.change().getNodeId())
                .collect(Collectors.toSet());
        assertEquals(Set.of("p-30"), ids);
    }

    @Test
    void fullySubsumedP21DoesNotReappear() {
        TailorChange lockedP23 = change("p-23", ALGORITHM_ORIGINAL, FULL_MERGE);
        ConservativeBatchSelector.PlanPick p21 = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 86,
                MAVEN_ORIGINAL, MAVEN_ORIGINAL + ".");
        ConservativeBatchSelector.PlanPick p30 = pick(
                "p-30", "PROJECT:other", RewriteIntent.CLARITY_IMPROVEMENT, 88,
                MYSQL_ORIGINAL, MYSQL_REWRITE);

        ConservativeBatchSelector.Selection selection = ConservativeContinueSelector.select(
                List.of(lockedP23),
                Map.of("p-23", "PROJECT:smartpc"),
                List.of(p21, p30)
        );

        Set<String> ids = selection.selected().stream()
                .map(pick -> pick.change().getNodeId())
                .collect(Collectors.toSet());
        assertEquals(Set.of("p-30"), ids);
        assertTrue(ConservativeContinueSelector.nodeFullySubsumed(MAVEN_ORIGINAL, List.of(lockedP23)));
    }

    @Test
    void lockedOptionalSupportingIsNotReusedOnContinue() {
        TailorChange lockedP21 = change(
                "p-21",
                MAVEN_ORIGINAL,
                "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.");
        lockedP21.setEvidenceNodeIds(List.of("p-21", "p-22"));
        ConservativeBatchSelector.PlanPick reuseOop = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                ALGORITHM_ORIGINAL,
                "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components using OOP.",
                List.of("p-23", "p-22"));
        ConservativeBatchSelector.PlanPick distinct = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 86,
                ALGORITHM_ORIGINAL,
                "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.",
                List.of("p-23"));

        ConservativeBatchSelector.Selection selection = ConservativeContinueSelector.select(
                List.of(lockedP21),
                Map.of("p-21", "PROJECT:smartpc"),
                List.of(reuseOop, distinct),
                Set.of("p-23"),
                Set.of("p-22")
        );

        assertEquals(1, selection.selected().size(), selection.dropped().toString());
        assertEquals("p-23", selection.selected().get(0).change().getNodeId());
        assertFalse(selection.selected().get(0).change().getNewText().toLowerCase().contains("oop"));
    }

    @Test
    void newSuggestionMustHaveUniqueFactContribution() {
        TailorChange locked = change(
                "p-16",
                "Designed a Java Spring Boot MVC service.",
                "Designed and implemented a Java Spring Boot MVC service.");
        ConservativeBatchSelector.PlanPick overlap = pick(
                "p-17", "PROJECT:prime", RewriteIntent.CLARITY_IMPROVEMENT, 90,
                "Implemented a Java Spring Boot MVC service.",
                "Implemented a Java Spring Boot MVC service for the same application.");

        ConservativeBatchSelector.Selection selection = ConservativeContinueSelector.select(
                List.of(locked),
                Map.of("p-16", "PROJECT:prime"),
                List.of(overlap)
        );

        assertTrue(selection.selected().isEmpty());
        assertTrue(selection.dropped().stream().anyMatch(item ->
                "p-17".equals(item.getNodeId())
                        && RejectedTailorChange.LOW_VALUE_REWRITE.equals(item.getReasonCode())));
    }

    private static TailorChange change(String nodeId, String original, String rewritten) {
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(original);
        change.setNewText(rewritten);
        change.setUpliftType(UpliftType.SUBSTANTIVE_REWRITE);
        change.setEvidenceNodeIds(List.of(nodeId));
        return change;
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String originalText,
            String newText) {
        return pick(nodeId, entryKey, intent, total, originalText, newText, List.of(nodeId));
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String originalText,
            String newText,
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
        change.setUpliftType(UpliftType.SUBSTANTIVE_REWRITE);
        ConservativeCandidateLocalScorer.Score score = new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, total, 60, total - 60, true, true,
                change.getUpliftType(), false, intent, null);
        return new ConservativeBatchSelector.PlanPick(plan, change, score, supportingIds);
    }
}
