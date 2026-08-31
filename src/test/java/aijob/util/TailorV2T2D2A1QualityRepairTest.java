package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import aijob.dto.UpliftType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorV2T2D2A1QualityRepairTest {

    private static final String P17 =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String P15 =
            "Define project scope, clarify requirements and utilized UML Class Diagram for planning";
    private static final String P21 =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String P22 =
            "Applied Object-Oriented Programming (OOP) principles to model component relationships.";
    private static final String P23 =
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)";
    private static final String UML_CART =
            "Integrated RESTful APIs for managing user authentication and product updates, "
                    + "using UML Class Diagram for planning cart functionalities.";
    private static final String REST_HOME =
            "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.";
    private static final String P21_HOME =
            "Developed a Maven-based Java project to help users select optimal components within a specified budget.";
    private static final String P21_OOP =
            "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.";
    private static final String P21_PARTIAL_CPU =
            "Developed a Java Maven project that helps users select optimal CPU, GPU, and RAM components within a given budget.";
    private static final String P23_HOME =
            "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.";
    private static final String P23_WITH_OOP =
            "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components using OOP.";
    private static final String FULL_SUBSUME =
            "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.";

    @Test
    void umlPlusCartRelationIsRejected() {
        assertEquals(
                RejectedTailorChange.RELATION_NOT_SUPPORTED,
                EvidenceRelationValidator.rejectReason(UML_CART, P17, List.of(P17, P15)));
    }

    @Test
    void restHomeRewriteIsAllowed() {
        assertNull(EvidenceRelationValidator.rejectReason(REST_HOME, P17, List.of(P17, P15)));
    }

    @Test
    void sameEntryDoesNotMakeTheRelationTrue() {
        assertEquals(
                RejectedTailorChange.RELATION_NOT_SUPPORTED,
                EvidenceRelationValidator.rejectReason(UML_CART, P17, List.of(P17, P15)));
    }

    @Test
    void partiallyUsedReservedTargetIsNotAbsorbed() {
        List<ResumeDocumentNode> nodes = List.of(
                node("p-21", P21),
                node("p-23", P23)
        );
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                ConservativeReservedTargetPolicy.absorbReason(
                        "p-21", P21, P21_PARTIAL_CPU, Set.of("p-21", "p-23"), nodes));
        assertNull(ConservativeReservedTargetPolicy.absorbReason(
                "p-21", P21, P21_OOP, Set.of("p-21", "p-23"), nodes));
    }

    @Test
    void onlyFullSubsumptionMayReplaceReservedTarget() {
        List<ResumeDocumentNode> nodes = List.of(
                node("p-21", P21),
                node("p-23", P23)
        );
        assertNull(ConservativeReservedTargetPolicy.absorbReason(
                "p-21", P21, FULL_SUBSUME, Set.of("p-21", "p-23"), nodes));
        assertEquals(
                ConservativeMaterialFacts.Status.FULLY_SUBSUMED,
                ConservativeMaterialFacts.coverage(P23, FULL_SUBSUME));
        assertEquals(
                ConservativeMaterialFacts.Status.PARTIALLY_USED,
                ConservativeMaterialFacts.coverage(P23, P21_PARTIAL_CPU));
    }

    @Test
    void firstRoundAndContinueDoNotRepeatOptionalSupporting() {
        ConservativeBatchSelector.PlanPick first = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 88,
                P21, P21_HOME, List.of("p-21"));
        ConservativeBatchSelector.PlanPick continueReuse = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 86,
                P23, P23_WITH_OOP, List.of("p-23", "p-22"));
        ConservativeBatchSelector.PlanPick continueDistinct = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 84,
                P23, P23_HOME, List.of("p-23"));
        ConservativeBatchSelector.Selection firstRound = ConservativeBatchSelector.select(
                List.of(first),
                new ConservativeBatchSelector.Context(List.of(), Set.of("p-21", "p-23"), Set.of()));
        assertEquals(1, firstRound.selected().size());
        ConservativeBatchSelector.Selection continued = ConservativeContinueSelector.select(
                List.of(firstRound.selected().get(0).change()),
                java.util.Map.of("p-21", "PROJECT:smartpc"),
                List.of(continueReuse, continueDistinct),
                Set.of("p-23"),
                Set.of("p-22"));
        assertEquals(1, continued.selected().size());
        assertEquals(P23_HOME, continued.selected().get(0).change().getNewText());
    }

    @Test
    void p21AndP23FinalDutiesStayDistinct() {
        ConservativeBatchSelector.PlanPick maven = pick(
                "p-21", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 88,
                P21, P21_HOME, List.of("p-21"));
        ConservativeBatchSelector.PlanPick algorithm = pick(
                "p-23", "PROJECT:smartpc", RewriteIntent.CLARITY_IMPROVEMENT, 86,
                P23, P23_HOME, List.of("p-23"));
        ConservativeBatchSelector.Selection selection = ConservativeBatchSelector.select(
                List.of(maven, algorithm),
                new ConservativeBatchSelector.Context(List.of(), Set.of("p-21", "p-23"), Set.of()));
        assertEquals(2, selection.selected().size());
        String p21 = byNode(selection, "p-21").change().getNewText().toLowerCase();
        String p23 = byNode(selection, "p-23").change().getNewText().toLowerCase();
        assertTrue(p21.contains("maven") && p21.contains("budget"));
        assertFalse(p21.contains("cpu"));
        assertFalse(p21.contains("oop"));
        assertTrue(p23.contains("algorithm") && p23.contains("cpu") && p23.contains("gpu") && p23.contains("ram"));
        assertFalse(p23.contains("oop"));
    }

    @Test
    void suggestionTypeMatchesClassifiedIntent() {
        assertEquals(UpliftType.GRAMMAR_FIX, ConservativeRewriteReasons.upliftTypeFor(RewriteIntent.GRAMMAR_FIX));
        assertEquals(
                UpliftType.CLARITY_IMPROVEMENT,
                ConservativeRewriteReasons.upliftTypeFor(RewriteIntent.CLARITY_IMPROVEMENT));
        assertEquals(
                UpliftType.SUBSTANTIVE_REWRITE,
                ConservativeRewriteReasons.upliftTypeFor(RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT));
    }

    @Test
    void improvementReasonIncludesTheActualChange() {
        aijob.dto.TailorChange p17 = new aijob.dto.TailorChange();
        p17.setOriginalText(P17);
        p17.setNewText(REST_HOME);
        ConservativeRewriteReasons.apply(p17, RewriteIntent.GRAMMAR_FIX, List.of());
        assertTrue(p17.getReasonZh().contains("managements"));
        assertFalse(p17.getReasonZh().contains("在保留全部事实的前提下，让这条经历更专业、更清楚。"));
    }

    private static ResumeDocumentNode node(String id, String text) {
        return new ResumeDocumentNode(id, "PARAGRAPH", text);
    }

    private static ConservativeBatchSelector.PlanPick byNode(
            ConservativeBatchSelector.Selection selection, String nodeId) {
        return selection.selected().stream()
                .filter(pick -> nodeId.equals(pick.change().getNodeId()))
                .findFirst()
                .orElseThrow();
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String original,
            String rewritten,
            List<String> supporting) {

        aijob.dto.ConservativeRewritePlan plan = new aijob.dto.ConservativeRewritePlan(
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
        aijob.dto.TailorChange change = new aijob.dto.TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(original);
        change.setNewText(rewritten);
        change.setEvidenceNodeIds(supporting);
        ConservativeCandidateLocalScorer.Score score = new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, total, 60, total - 60, true, true,
                ConservativeRewriteReasons.upliftTypeFor(intent), false, intent, null);
        return new ConservativeBatchSelector.PlanPick(plan, change, score, supporting);
    }
}
