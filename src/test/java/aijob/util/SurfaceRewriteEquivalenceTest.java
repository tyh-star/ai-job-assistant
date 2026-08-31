package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceRewriteEquivalenceTest {

    @Test
    void dynamicallySuggestInfinitiveIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
                "Implemented an algorithm to dynamically suggest components (CPU, GPU & RAM)"));
    }

    @Test
    void cpuListPunctuationIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
                "Implemented an algorithm that dynamically suggests components such as CPU, GPU, and RAM"));
    }

    @Test
    void applicationToProjectIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Developed a Java Maven application that helps users select optimal components within a given budget",
                "Developed a Java Maven project that helps users select optimal components within a given budget"));
    }

    @Test
    void helpsUsersSelectToEnablesUsersToChooseIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Developed a Java Maven project that enables users to choose optimal components within a given budget"));
    }

    @Test
    void aidBestSpecifiedAreLowValueSynonyms() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Developed a Java Maven project aiding users in selecting the best components within a specified budget."));
    }

    @Test
    void utilizeEmployAreLowValueWithoutGrammarFix() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(
                "Built a Java Spring Boot application using the MVC pattern.",
                "Built a Java Spring Boot project employing the MVC pattern."));
    }

    @Test
    void designToDesignedIsValuableGrammar() {
        assertTrue(SurfaceRewriteEquivalence.hasValuableGrammarFix(
                "Design and implement a Java Spring Boot application using the MVC pattern.",
                "Designed and implemented a Java Spring Boot application using the MVC pattern."));
        assertFalse(SurfaceRewriteEquivalence.isLowValueSurface(
                "Design and implement a Java Spring Boot application using the MVC pattern.",
                "Designed and implemented a Java Spring Boot application using the MVC architecture."));
    }
}
