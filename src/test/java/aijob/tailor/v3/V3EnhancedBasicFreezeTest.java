package aijob.tailor.v3;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedBasicFreezeTest {

    @Test
    void v32_4BasicPromptValidatorGateAndRunStayFrozen() throws Exception {
        assertEquals("v3-2.4-action-object-provenance", V3GenerationTelemetry.PROMPT_VERSION);
        assertEquals("v3-3b-enhanced-user-confirmed", V3GenerationTelemetry.ENHANCED_PROMPT_VERSION);
        assertFalse(V3GenerationTelemetry.PROMPT_VERSION.equals(V3GenerationTelemetry.ENHANCED_PROMPT_VERSION));
        String basic = Files.readString(
                Path.of("src/main/java/aijob/tailor/v3/V3BasicTailorService.java"), StandardCharsets.UTF_8);
        assertTrue(basic.contains("V3EntryFactGraphFactory.from(ledger);"));
        assertTrue(basic.contains("V3ProductMode.BASIC"));
        assertTrue(basic.contains("List.of(),"));
        assertFalse(basic.contains("V3EnhancedOpportunityService"));
        String jobService = Files.readString(
                Path.of("src/main/java/aijob/service/impl/JobServiceImpl.java"), StandardCharsets.UTF_8);
        assertTrue(jobService.contains("v3BasicTailorService.run(nodes, jobDescription)"));
        assertTrue(jobService.contains("response.setProductMode(\"BASIC\")"));
        assertTrue(jobService.contains("createEnhancedProposal"));
        assertFalse(jobService.contains("setProductMode(\"AGGRESSIVE\")"));
        String validator = Files.readString(
                Path.of("src/main/java/aijob/tailor/v3/V3EntryRewriteValidator.java"), StandardCharsets.UTF_8);
        assertTrue(validator.contains("V3ActionObjectProvenanceGate.check"));
        assertFalse(validator.contains("MISSING_CONFIRMED_EVIDENCE"));
        String opportunity = Files.readString(
                Path.of("src/main/java/aijob/tailor/v3/V3EnhancedOpportunityService.java"), StandardCharsets.UTF_8);
        assertFalse(opportunity.contains("OpenAIOkHttpClient"));
        assertFalse(opportunity.contains("com.openai"));
        assertFalse(opportunity.contains("ResumeDocxTailorService"));
        assertFalse(opportunity.contains("insert("));
        String generator = Files.readString(
                Path.of("src/main/java/aijob/tailor/v3/OpenAiV3EntryRewriteGenerator.java"), StandardCharsets.UTF_8);
        assertTrue(generator.contains("V3GenerationTelemetry.PROMPT_VERSION"));
        assertFalse(generator.contains("ENHANCED_PROMPT_VERSION"));
        String enhancedGenerator = Files.readString(
                Path.of("src/main/java/aijob/tailor/v3/OpenAiV3EnhancedEntryRewriteGenerator.java"),
                StandardCharsets.UTF_8);
        assertTrue(enhancedGenerator.contains("ENHANCED_PROMPT_VERSION"));
        assertFalse(enhancedGenerator.contains("BASIC mode: resume facts only"));
    }
}
