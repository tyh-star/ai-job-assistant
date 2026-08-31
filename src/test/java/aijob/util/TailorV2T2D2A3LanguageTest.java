package aijob.util;

import aijob.dto.ConservativeCandidateRanking;
import aijob.dto.ConservativeCandidateScore;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.service.AiResumeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TailorV2T2D2A3LanguageTest {

    private static final String P16_ORIGINAL =
            "Design and implement the application using Java Spring Boot with an MVC pattern.";
    private static final String P16_HOME =
            "Designed and implemented a Java Spring Boot application using the MVC architecture.";
    private static final String P16_STRUCTURED =
            "Designed and implemented an application leveraging Java Spring Boot with an MVC pattern for structured architecture.";
    private static final String P16_UTILIZING =
            "Designed and implemented the application utilizing Java Spring Boot with an MVC architecture.";
    private static final String P17_ORIGINAL =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String P17_MANAGING =
            "Integrated RESTful APIs for managing user authentication, product updates, and shopping cart operations.";
    private static final String P21_ORIGINAL =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String P21_SYNONYM =
            "Developed a Java Maven project aiding users in selecting the best components within a specified budget.";

    @Test
    void sameNodeHandleToManagingIsAllowed() {
        assertEquals(
                null,
                EvidenceRelationValidator.rejectReason(P17_MANAGING, P17_ORIGINAL, List.of(P17_ORIGINAL)));
    }

    @Test
    void umlCartRelationStaysRejected() {
        assertEquals(
                RejectedTailorChange.RELATION_NOT_SUPPORTED,
                EvidenceRelationValidator.rejectReason(
                        "Integrated RESTful APIs for managing user authentication and product updates, "
                                + "using UML Class Diagram for planning cart functionalities.",
                        P17_ORIGINAL,
                        List.of(P17_ORIGINAL,
                                "Define project scope, clarify requirements and utilized UML Class Diagram for planning")));
    }

    @Test
    void p21SynonymRewriteIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(P21_ORIGINAL, P21_SYNONYM));
    }

    @Test
    void structuredArchitectureIsUnsupportedClaim() {
        assertTrue(ConservativeCandidateHardGate.inventsUnsupportedContext(
                P16_ORIGINAL, P16_STRUCTURED, null, null));
    }

    @Test
    void judgeCannotRestoreHardGateRejectedCandidate() {
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(pick("p-16", P16_ORIGINAL, P16_HOME, RewriteIntent.GRAMMAR_FIX, true)),
                List.of(new RejectedTailorChange("p-17", RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE)));
        AiResumeService ai = mock(AiResumeService.class);
        when(ai.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenReturn(ConservativeLanguageJudge.passthroughRanking(List.of()));
        ConservativeBatchSelector.Selection judged = ConservativeLanguageJudge.apply(ai, "Java", batch);
        assertEquals(1, judged.selected().size());
        assertEquals(P16_HOME, judged.selected().get(0).change().getNewText());
        assertTrue(judged.selected().stream().noneMatch(item ->
                item.change().getNewText() != null && item.change().getNewText().toLowerCase().contains("aws")));
    }

    @Test
    void judgeUnavailableDropsNonGrammarBorderlineCandidate() {
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(pick("p-21", P21_ORIGINAL, P21_SYNONYM, RewriteIntent.CLARITY_IMPROVEMENT, false)),
                List.of());
        ConservativeBatchSelector.Selection judged = ConservativeLanguageJudge.apply(null, "Java", batch);
        assertTrue(judged.selected().isEmpty());
        assertTrue(judged.dropped().stream().anyMatch(item ->
                "p-21".equals(item.getNodeId())
                        && RejectedTailorChange.LOW_VALUE_REWRITE.equals(item.getReasonCode())));
    }

    @Test
    void judgeUnavailableKeepsEstablishedGrammarFix() {
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(pick("p-16", P16_ORIGINAL, P16_HOME, RewriteIntent.CLARITY_IMPROVEMENT, true)),
                List.of());
        ConservativeBatchSelector.Selection judged = ConservativeLanguageJudge.apply(null, "Java", batch);
        assertEquals(1, judged.selected().size());
        assertEquals(P16_HOME, judged.selected().get(0).change().getNewText());
    }

    @Test
    void awkwardUtilizingIsDroppedEvenIfJudgeWouldKeep() {
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(pick("p-16", P16_ORIGINAL, P16_UTILIZING, RewriteIntent.CLARITY_IMPROVEMENT, true)),
                List.of());
        AiResumeService ai = mock(AiResumeService.class);
        when(ai.rankConservativeCandidates(any(), any(), any(), anyList()))
                .thenReturn(ConservativeLanguageJudge.passthroughRanking(List.of()));
        ConservativeBatchSelector.Selection judged = ConservativeLanguageJudge.apply(ai, "Java", batch);
        assertTrue(judged.selected().isEmpty(), judged.selected().toString());
    }

    @Test
    void finalBatchMayContainOnlyOneOrTwoItems() {
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(
                        pick("p-16", P16_ORIGINAL, P16_HOME, RewriteIntent.GRAMMAR_FIX, true),
                        pick("p-17", P17_ORIGINAL, P17_MANAGING, RewriteIntent.GRAMMAR_FIX, true)),
                List.of());
        ConservativeBatchSelector.Selection judged = ConservativeLanguageJudge.apply(null, "Java", batch);
        assertTrue(judged.selected().size() >= 1 && judged.selected().size() <= 2);
    }

    private static ConservativeCandidateRanking highScoreRanking() {
        ConservativeCandidateRanking ranking = new ConservativeCandidateRanking();
        ranking.setSelectedIndex(0);
        ConservativeCandidateScore score = new ConservativeCandidateScore();
        score.setIndex(0);
        score.setGrammarScore(90);
        score.setFactualPreservationScore(100);
        ranking.setScores(List.of(score));
        return ranking;
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String original,
            String rewritten,
            String intent,
            boolean grammarUplift) {

        ConservativeRewritePlan plan = new ConservativeRewritePlan(
                nodeId,
                nodeId,
                "PROJECT:test",
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
                80,
                ""
        );
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(original);
        change.setNewText(rewritten);
        ConservativeCandidateLocalScorer.Score score = new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, 80, 60, 20, true, true,
                grammarUplift ? aijob.dto.UpliftType.GRAMMAR_FIX : aijob.dto.UpliftType.CLARITY_IMPROVEMENT,
                grammarUplift, intent, null);
        return new ConservativeBatchSelector.PlanPick(plan, change, score, List.of(nodeId));
    }
}
