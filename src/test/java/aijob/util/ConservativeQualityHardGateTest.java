package aijob.util;

import aijob.dto.RejectedTailorChange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeQualityHardGateTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, REST APIs, Docker and Kubernetes.";
    private static final String YAOHANG_P23 =
            "●   Developed and integrated IP rate-limiting and black/white list management features to strengthen access control and mitigate potential attack traffic.";

    @Test
    void g1_developedMustNotUpgradeToLedDirectedManagedOversawOwned() {
        assertRejected(
                "●   Developed payment APIs for checkout.",
                "●   Led payment APIs for checkout.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
        assertRejected(
                "●   Implemented Spring Boot services for checkout.",
                "●   Directed Spring Boot services for checkout.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
        assertRejected(
                "●   Developed backend jobs for reporting.",
                "●   Managed backend jobs for reporting.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
        assertRejected(
                "●   Implemented the access-control module.",
                "●   Oversaw the access-control module.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
        assertRejected(
                "●   Developed the notification pipeline.",
                "●   Owned the notification pipeline.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
    }

    @Test
    void manageDutyObjectsAreNotRoleEscalation() {
        ConservativeRewriteQualityEvaluator.Verdict auth =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                        "Integrated RESTful APIs to manage user authentication, product updates, and shopping cart operations.",
                        JD);
        assertTrue(auth.accepted() || !RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(auth.reasonCode()),
                auth.reasonCode());
        ConservativeRewriteQualityEvaluator.Verdict cart =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated RESTful APIs for shopping cart operations.",
                        "Integrated RESTful APIs to manage shopping cart operations.",
                        JD);
        assertFalse(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(cart.reasonCode()), cart.reasonCode());
        ConservativeRewriteQualityEvaluator.Verdict product =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated APIs for product data.",
                        "Integrated APIs to manage product data.",
                        JD);
        assertFalse(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(product.reasonCode()), product.reasonCode());
        ConservativeRewriteQualityEvaluator.Verdict team =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Developed the checkout service.",
                        "Managed the development team for the checkout service.",
                        JD);
        assertEquals(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, team.reasonCode());
        ConservativeRewriteQualityEvaluator.Verdict project =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Developed the checkout service.",
                        "Led the project to deliver the checkout service.",
                        JD);
        assertEquals(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, project.reasonCode());
        ConservativeRewriteQualityEvaluator.Verdict architecture =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Developed the checkout service.",
                        "Owned the architecture for the checkout service.",
                        JD);
        assertEquals(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, architecture.reasonCode());
    }

    @Test
    void g2_deployedMustNotBecomeMaintained() {
        assertRejected(
                "●   Deployed backend services on AWS for production workloads.",
                "●   Maintained backend services on AWS for production workloads.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
    }

    @Test
    void g3_agileKnowledgeMustNotBecomeAgileCollaboration() {
        assertRejected(
                "Knowledge of Agile development and Scrum ceremonies.",
                "Agile collaboration and Scrum ceremonies.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
        assertRejected(
                "Strong understanding of Agile development.",
                "Collaborated in Agile development.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
    }

    @Test
    void g4_onlyVaguePhrasesMustBeLowValue() {
        assertRejected(
                "●   Developed backend services for internal tools.",
                "●   Developed backend services for internal tools using best practices.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
        assertRejected(
                "●   Built REST APIs in Java.",
                "●   Built REST APIs in Java for stakeholders in a team environment.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
    }

    @Test
    void g5_pluralArticlePunctuationWordOrderMustBeLowValue() {
        assertRejected(
                "Design and implement the application using Java Spring Boot with an MVC pattern.",
                "Design and implement applications using Java Spring Boot with an MVC pattern.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
        assertRejected(
                "Integrated RESTful APIs to handle a shopping cart.",
                "Integrated RESTful APIs to handle the shopping cart.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
        assertRejected(
                "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart managements",
                RejectedTailorChange.LOW_VALUE_REWRITE);
        assertRejected(
                "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality",
                "Delivered well-documented, clean code using Java doc strings and conducted thorough testing to ensure quality.",
                RejectedTailorChange.LOW_VALUE_REWRITE);
    }

    @Test
    void g6b_droppingImplementFromDesignAndImplementIsInformationLoss() {
        assertRejected(
                "Design and implement the application using Java Spring Boot with an MVC pattern.",
                "Designed a Java Spring Boot application using the MVC architecture.",
                RejectedTailorChange.INFORMATION_LOSS);
    }

    @Test
    void g6_removingOriginalTechMustBeInformationLoss() {
        assertRejected(
                "Design and implement the application using Java Spring Boot with an MVC pattern.",
                "Designed and implemented an application using the MVC architecture.",
                RejectedTailorChange.INFORMATION_LOSS);
        assertRejected(
                "●   Deployed backend services on AWS with Docker.",
                "●   Deployed backend services on AWS.",
                RejectedTailorChange.INFORMATION_LOSS);
    }

    @Test
    void g10_candidateComparedToOriginalWithoutSignificantUpliftIsRejected() {
        ConservativeRewriteQualityEvaluator.Verdict sameFacts =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "●   Developed and integrated IP rate-limiting features.",
                        "●   Developed and integrated IP rate-limiting features using best practices.",
                        JD
                );
        assertFalse(sameFacts.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, sameFacts.reasonCode());
    }

    @Test
    void g12_yaohangDevelopedMustNotBecomeDirected() {
        assertRejected(
                YAOHANG_P23,
                "●   Directed and integrated IP rate-limiting and black/white list management features to strengthen access control and mitigate potential attack traffic.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
    }

    @Test
    void g12b_screenshotDirectedTheCreationIsRoleSemanticEscalation() {
        assertRejected(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Directed the creation of a Java Maven project assisting users in selecting the best components within budget limits.",
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION);
    }

    @Test
    void stillAcceptsPastTenseAndPurposeRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        "Designed and implemented a Java Spring Boot application using the MVC architecture.",
                        JD
                );
        assertTrue(verdict.accepted());
    }

    private static void assertRejected(String original, String rewritten, String reason) {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(original, rewritten, JD);
        assertFalse(verdict.accepted(), original + " -> " + rewritten);
        assertEquals(reason, verdict.reasonCode(), original + " -> " + rewritten);
    }
}
