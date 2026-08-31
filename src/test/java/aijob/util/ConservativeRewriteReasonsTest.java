package aijob.util;

import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.UpliftType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeRewriteReasonsTest {

    @Test
    void p21HomeMentionsMavenBasedAndSpecifiedBudget() {
        TailorChange change = change(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Developed a Maven-based Java project to help users select optimal components within a specified budget.");
        ConservativeRewriteReasons.apply(change, RewriteIntent.CLARITY_IMPROVEMENT, List.of());
        assertEquals(UpliftType.CLARITY_IMPROVEMENT, change.getUpliftType());
        assertTrue(change.getReason().toLowerCase().contains("maven"));
        assertTrue(change.getReasonZh().contains("specified") || change.getReasonZh().contains("Maven"));
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
    }

    @Test
    void p21MentionsOopAndKeptMavenFacts() {
        TailorChange change = change(
                "Developed a Java Maven project that helps users select optimal components within a given budget",
                "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.");
        ConservativeRewriteReasons.apply(
                change,
                RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT,
                List.of("Applied Object-Oriented Programming (OOP) principles to model component relationships."));
        assertEquals(UpliftType.SUBSTANTIVE_REWRITE, change.getUpliftType());
        assertTrue(change.getReasonZh().contains("OOP"));
        assertTrue(change.getReasonZh().contains("Java"));
        assertTrue(change.getReasonZh().contains("Maven"));
        assertTrue(change.getReasonZh().contains("预算"));
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
    }

    @Test
    void p17MentionsManagementsAndRestSimplification() {
        TailorChange change = change(
                "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements",
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.");
        ConservativeRewriteReasons.apply(change, RewriteIntent.GRAMMAR_FIX, List.of());
        assertEquals(UpliftType.GRAMMAR_FIX, change.getUpliftType());
        assertTrue(change.getReasonZh().contains("managements"));
        assertTrue(change.getReasonZh().contains("REST"));
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
    }

    @Test
    void p16MentionsTenseAndSpringStack() {
        TailorChange change = change(
                "Design and implement a Java Spring Boot application using the MVC pattern.",
                "Designed and implemented a Java Spring Boot application using the MVC pattern.");
        ConservativeRewriteReasons.apply(change, RewriteIntent.GRAMMAR_FIX, List.of());
        assertEquals(UpliftType.GRAMMAR_FIX, change.getUpliftType());
        assertTrue(change.getReasonZh().contains("时态"));
        assertTrue(change.getReasonZh().contains("Java"));
        assertTrue(change.getReasonZh().contains("Spring Boot"));
        assertTrue(change.getReasonZh().contains("MVC"));
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
    }

    @Test
    void p23MentionsAlgorithmAndHardwareTargets() {
        TailorChange change = change(
                "Implemented an algorithm that dynamically suggests components (CPU, GPU & RAM)",
                "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.");
        ConservativeRewriteReasons.apply(change, RewriteIntent.CLARITY_IMPROVEMENT, List.of());
        assertEquals(UpliftType.CLARITY_IMPROVEMENT, change.getUpliftType());
        assertTrue(change.getReasonZh().contains("算法"));
        assertTrue(change.getReasonZh().contains("CPU"));
        assertTrue(change.getReasonZh().contains("GPU"));
        assertTrue(change.getReasonZh().contains("RAM"));
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
    }

    @Test
    void neverUsesUnifiedTemplate() {
        TailorChange change = change(
                "Tuned MySQL indexes for search.",
                "Tuned MySQL indexes to speed up order search.");
        ConservativeRewriteReasons.apply(change, RewriteIntent.CLARITY_IMPROVEMENT, List.of());
        assertFalse(ConservativeRewriteReasons.isBannedTemplate(change.getReasonZh()));
        assertFalse(change.getReason().contains("more professional, clearer"));
    }

    private static TailorChange change(String original, String rewritten) {
        TailorChange change = new TailorChange();
        change.setOriginalText(original);
        change.setNewText(rewritten);
        return change;
    }
}
