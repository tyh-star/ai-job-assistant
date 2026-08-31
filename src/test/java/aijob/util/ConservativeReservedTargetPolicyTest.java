package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConservativeReservedTargetPolicyTest {

    private static final String P21 =
            "Developed a Java Maven project that helps users select optimal components within a given budget";
    private static final String P23 =
            "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)";
    private static final String PARTIAL_ABSORB =
            "Developed a Java Maven project that helps users select optimal CPU, GPU, and RAM components within a given budget.";
    private static final String OOP_HOME =
            "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.";
    private static final String FULL_SUBSUME =
            "Developed a Java Maven project using OOP and a dynamic algorithm to help users select budget-aligned CPU, GPU, and RAM components.";

    @Test
    void partiallyUsedReservedTargetIsNotAbsorbed() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-21", "PARAGRAPH", P21),
                new ResumeDocumentNode("p-23", "PARAGRAPH", P23)
        );
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                ConservativeReservedTargetPolicy.absorbReason(
                        "p-21", P21, PARTIAL_ABSORB, Set.of("p-21", "p-23"), nodes));
    }

    @Test
    void oopFromUnreservedSupportingIsAllowed() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-21", "PARAGRAPH", P21),
                new ResumeDocumentNode("p-22", "PARAGRAPH", "Applied Object-Oriented Programming principles."),
                new ResumeDocumentNode("p-23", "PARAGRAPH", P23)
        );
        assertNull(ConservativeReservedTargetPolicy.absorbReason(
                "p-21", P21, OOP_HOME, Set.of("p-21", "p-23"), nodes));
    }

    @Test
    void fullySubsumedReservedTargetMayReplaceThatTarget() {
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-21", "PARAGRAPH", P21),
                new ResumeDocumentNode("p-23", "PARAGRAPH", P23)
        );
        assertNull(ConservativeReservedTargetPolicy.absorbReason(
                "p-21", P21, FULL_SUBSUME, Set.of("p-21", "p-23"), nodes));
        assertEquals(
                ConservativeMaterialFacts.Status.FULLY_SUBSUMED,
                ConservativeMaterialFacts.coverage(P23, FULL_SUBSUME));
    }
}
