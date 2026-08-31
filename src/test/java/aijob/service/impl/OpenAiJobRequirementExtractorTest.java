package aijob.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiJobRequirementExtractorTest {

    @Test
    void instructionsForbidInventingToolsAndAskForMustHaveSemantics() {
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("Do not add technologies"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("MUST_HAVE"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("Atomize technical skills"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("Do not explode ordinary soft skills"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("OTHER_KEYWORD"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("Never default to QUALIFICATION"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("Qualifications does not make"));
        assertTrue(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("candidate only"));
        assertFalse(OpenAiJobRequirementExtractor.INSTRUCTIONS.contains("overallScore"));
        assertTrue(OpenAiJobRequirementExtractor.buildInput("Need Java").contains("Need Java"));
    }
}
