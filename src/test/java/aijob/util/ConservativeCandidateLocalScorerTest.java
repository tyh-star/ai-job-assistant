package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeCandidateLocalScorerTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @Test
    void synonymRewriteIsNotSubstantiveNinety() {
        ResumeDocumentNode node = new ResumeDocumentNode(
                "p-4",
                "PARAGRAPH",
                "Demonstrated Java Spring Boot experience in coursework."
        );
        ConservativeRewritePlan plan = new ConservativeRewritePlan(
                node.getNodeId(),
                List.of("java", "spring"),
                "Rewrite this bullet",
                170,
                "BULLET"
        );
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                node, plan, null, null, JD,
                "Showcased Java Spring Boot experience in coursework.",
                List.of(),
                null);
        assertTrue(score.substantiveImprovement() <= 40, score.toString());
        assertEquals(RewriteIntent.LOW_VALUE_REWRITE, score.classifiedIntent());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, score.rejectReason());
        assertTrue(!score.admitted());
    }

    @Test
    void clarityBelowSixtyIsRejectedEvenWhenTotalWouldLookHigh() {
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                ConservativeCandidateLocalScorer.admissionFailure(
                        100, 80, 90, 38, 24, 84,
                        RewriteIntent.CLARITY_IMPROVEMENT,
                        "Designed and implemented a Java Spring Boot application."));
    }

    @Test
    void enrichmentRequiresHigherSubstantiveAndJdFloors() {
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                ConservativeCandidateLocalScorer.admissionFailure(
                        100, 50, 80, 80, 20, 80,
                        RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT,
                        "Designed and implemented a Java Spring Boot application."));
    }

    @Test
    void objectClarificationIsAdmitted() {
        ResumeDocumentNode node = new ResumeDocumentNode(
                "p-23",
                "PARAGRAPH",
                "●   Developed and integrated IP rate-limiting features."
        );
        ConservativeRewritePlan plan = new ConservativeRewritePlan(
                node.getNodeId(),
                List.of("security"),
                "Rewrite this bullet",
                170,
                "BULLET"
        );
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                node, plan, null, null, "Need Spring Boot security experience.",
                "●   Developed and integrated IP rate-limiting features for traffic.",
                List.of(),
                null);
        assertTrue(score.admitted(), score.toString());
        assertTrue(score.substantiveImprovement() >= 70, score.toString());
        assertEquals(RewriteIntent.CLARITY_IMPROVEMENT, score.classifiedIntent());
    }

    @Test
    void tenseOnlyIsCappedAtSeventy() {
        int points = ConservativeCandidateLocalScorer.substantivePoints(
                "Design and implement a Java Spring Boot application using the MVC pattern.",
                "Designed and implemented a Java Spring Boot application using the MVC pattern.",
                List.of(),
                null,
                null);
        assertEquals(70, points);
    }
}
