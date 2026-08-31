package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.UpliftType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeRewriteQualityEvaluatorTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, REST APIs, Docker and Kubernetes.";

    @Test
    void pluralityOnlyIsLowValueRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        "Design and implement applications using Java Spring Boot with an MVC pattern.",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, verdict.reasonCode());
    }

    @Test
    void rejectsSingleSynonymChange() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Strong understanding of computer science fundamentals including data structures and algorithms",
                        "Strong understanding of computer science fundamentals, focusing on data structures and algorithms",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, verdict.reasonCode());
    }

    @Test
    void punctuationOnlyIsLowValueRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                        "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart managements",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, verdict.reasonCode());
    }

    @Test
    void grammarFixForManagementsAndOxfordComma() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                        "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
                        JD
                );
        assertTrue(verdict.accepted());
        assertEquals(UpliftType.GRAMMAR_FIX, verdict.upliftType());
    }

    @Test
    void grammarFixManagementsToOperations() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                        "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.",
                        JD
                );
        assertTrue(verdict.accepted());
        assertEquals(UpliftType.GRAMMAR_FIX, verdict.upliftType());
    }

    @Test
    void rejectsWordOrderOnlyDocumentationRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality",
                        "Delivered well-documented, clean code using Java doc strings and conducted thorough testing to ensure quality.",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, verdict.reasonCode());
    }

    @Test
    void rejectsDesignedAndImplementedReplacedByCreated() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        "Created an application using Java Spring Boot, following an MVC design pattern.",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, verdict.reasonCode());
    }

    @Test
    void acceptsPastTenseProfessionalRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        "Designed and implemented a Java Spring Boot application using the MVC architecture.",
                        JD
                );
        assertTrue(verdict.accepted());
        assertEquals(UpliftType.SUBSTANTIVE_REWRITE, verdict.upliftType());
    }

    @Test
    void rejectsMissingTechnology() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        "Designed and implemented an application using the MVC architecture.",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.INFORMATION_LOSS, verdict.reasonCode());
    }

    @Test
    void rejectsKeywordStuffing() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Developed backend services for internal tools.",
                        "Developed Docker Kubernetes Docker Kubernetes Docker backend services for internal tools.",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.KEYWORD_STUFFING, verdict.reasonCode());
    }

    @Test
    void rejectsSkillRemoval() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS",
                        "Java, SQL, HTML, CSS",
                        JD
                );
        assertFalse(verdict.accepted());
        assertEquals(RejectedTailorChange.SKILL_REMOVAL_NOT_ALLOWED, verdict.reasonCode());
    }

    @Test
    void acceptsExistingVerifiedSecurityRewrite() {
        ConservativeRewriteQualityEvaluator.Verdict verdict =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "●   Developed and integrated IP rate-limiting features.",
                        "●   Developed and integrated IP rate-limiting features for request traffic.",
                        "Need Spring Boot security experience."
                );
        assertTrue(verdict.accepted());
    }
}
