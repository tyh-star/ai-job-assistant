package aijob.tailor.v3;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3EnhancedEntryRewritePromptTest {

    @Test
    void enhancedPromptIncludesConfirmedAndRejectedFacts() {
        String prompt = V3EnhancedEntryRewritePrompt.build(new V3EntryRewriteRequest(
                V3ProductMode.ENHANCED,
                "PROJECT:prime-deals",
                "PROJECT",
                "Prime Deals",
                "",
                "Deploy and maintain applications on AWS",
                List.of(),
                List.of(),
                List.of(),
                V3EntryFactGraph.empty(),
                null,
                List.of(new V3UserEvidence(41L, "PROJECT:prime-deals", "CONFIRMED_USED",
                        "weekly patching", "", "", "", "maintain")),
                List.of(new V3UserEvidence(42L, "PROJECT:prime-deals", "CONFIRMED_NOT_USED",
                        "kubernetes", "", "", "", "k8s")),
                1,
                120,
                ""));
        assertTrue(prompt.contains("USER CONFIRMED FACTS FOR THIS ENTRY"));
        assertTrue(prompt.contains("weekly patching"));
        assertTrue(prompt.contains("USER REJECTED FACTS FOR THIS ENTRY"));
        assertTrue(prompt.contains("kubernetes"));
        assertTrue(prompt.contains(V3GenerationTelemetry.ENHANCED_PROMPT_VERSION));
        assertFalse(prompt.contains("BASIC mode"));
    }
}
