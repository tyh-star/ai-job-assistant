package aijob.service.impl;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ConservativeRewriteRepair;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.util.ConservativeTailorPlanner;
import aijob.util.TailorV2T2BYaohangTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResumeServiceConstrainedTailorTest {

    private static final ConservativeRewritePlan PLAN = new ConservativeRewritePlan(
            "p-23",
            List.of("java", "security"),
            "Emphasize existing Java security facts.",
            80,
            "EXPERIENCE_BULLET"
    );
    private static final ResumeDocumentNode NODE = new ResumeDocumentNode(
            "p-23",
            "PARAGRAPH",
            "●   Developed and integrated IP rate-limiting features."
    );

    @Test
    void constrainedInput_includesRequiredFields() {
        String input = OpenAiResumeServiceImpl.buildConstrainedInput(
                NODE,
                PLAN,
                "Need Java security experience."
        );

        assertTrue(input.contains("nodeId: p-23"));
        assertTrue(input.contains("nodeRole: EXPERIENCE_BULLET"));
        assertTrue(input.contains("originalText: " + NODE.getText()));
        assertTrue(input.contains("maxCharacters: 80"));
        assertTrue(input.contains("sameEntryEvidenceAllowed: true"));
        assertTrue(input.contains("crossEntryEvidenceAllowed: false"));
        assertTrue(input.contains("relevantJobTerms: java, security"));
        assertTrue(input.contains("Need Java security experience."));
        assertTrue(input.contains("EVIDENCE_PACKET_JSON:"));
        assertTrue(input.contains("rewriteIntent:"));
    }

    @Test
    void constrainedInstructions_includeSafetyRulesAndRepairHints() {
        String first = OpenAiResumeServiceImpl.buildConstrainedInstructions(PLAN, null);
        assertTrue(first.contains("sameEntryEvidenceAllowed=true"));
        assertTrue(first.contains("maxCharacters=80"));
        assertTrue(first.contains("Do not add tools, skills, scale, metrics, duties, or outcomes"));
        assertTrue(first.contains("exactly three candidates"));
        assertTrue(first.contains("SAME_ENTRY_EVIDENCE_ENRICHMENT"));
        assertTrue(first.contains("CLARITY_IMPROVEMENT"));
        assertTrue(first.contains("GRAMMAR_FIX"));
        assertTrue(first.contains("purpose, result, or condition"));
        assertTrue(first.contains("project, application"));
        assertTrue(first.contains("or system"));
        assertTrue(first.contains("and deployed"));
        assertTrue(first.contains("structurally different candidates"));
        assertTrue(first.contains("EVIDENCE_PACKET_JSON") || first.contains("requiredFact"));
        assertFalse(first.contains("REPAIR:"));

        String tooLong = OpenAiResumeServiceImpl.buildConstrainedInstructions(
                PLAN,
                new ConservativeRewriteRepair(RejectedTailorChange.TEXT_TOO_LONG, "overlong text")
        );
        assertTrue(tooLong.contains("REPAIR: TEXT_TOO_LONG"));
        assertTrue(tooLong.contains("maxCharacters=80"));

        String role = OpenAiResumeServiceImpl.buildConstrainedInstructions(
                PLAN,
                new ConservativeRewriteRepair(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, "directed")
        );
        assertTrue(role.contains("REPAIR: ROLE_SEMANTIC_ESCALATION"));

        String cross = OpenAiResumeServiceImpl.buildConstrainedInstructions(
                PLAN,
                new ConservativeRewriteRepair(
                        RejectedTailorChange.CROSS_NODE_EVIDENCE_NOT_ALLOWED,
                        "Java on AWS"
                )
        );
        assertTrue(cross.contains("ALLOWED SAME-ENTRY FACTS"));

        String lowValue = OpenAiResumeServiceImpl.buildConstrainedInstructions(
                PLAN,
                new ConservativeRewriteRepair(RejectedTailorChange.LOW_VALUE_REWRITE, "plurality only")
        );
        assertTrue(lowValue.contains("materially clearer professional resume bullet"));
    }

    @Test
    void constrainedInput_includesEvidencePacketFromPlanner() {
        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(
                TailorV2T2BYaohangTest.yaohangT2bNodes(),
                "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs."
        );
        ConservativeRewritePlan prime = plans.stream()
                .filter(plan -> "p-16".equals(plan.nodeId()))
                .findFirst()
                .orElseThrow();
        ResumeDocumentNode node = TailorV2T2BYaohangTest.yaohangT2bNodes().stream()
                .filter(item -> "p-16".equals(item.getNodeId()))
                .findFirst()
                .orElseThrow();
        String input = OpenAiResumeServiceImpl.buildConstrainedInput(node, prime, "Junior Java backend.");
        String instructions = OpenAiResumeServiceImpl.buildConstrainedInstructions(prime, null);

        assertTrue(input.contains("EVIDENCE_PACKET_JSON:"));
        assertTrue(input.contains("requiredFacts:"));
        assertTrue(input.contains("supportingNodeIds:"));
        assertTrue(input.contains("forbiddenClaims:"));
        assertFalse(input.toLowerCase().contains("yaohang@example.com"));
        assertTrue(instructions.contains("requiredFact"));
        assertTrue(instructions.contains("forbiddenClaims"));
        assertTrue(instructions.contains("rewriteIntent="));
    }
}
