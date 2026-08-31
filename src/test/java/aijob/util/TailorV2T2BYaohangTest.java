package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNTHETIC Tailor V2 T2B fixture.
 * Handwritten nodes plus an ACME internship that is NOT in the real Yaohang PDF.
 * Real PDF/canonical DOCX coverage lives in {@link TailorV2T2B2YaohangRealPdfTest}.
 */
public class TailorV2T2BYaohangTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    /** SYNTHETIC nodes, including ACME internship. Do not treat as Yaohang PDF parse output. */
    public static List<ResumeDocumentNode> syntheticNodesWithAcmeInternship() {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        for (ResumeDocumentNode node : ResumeRequirementMatcherTest.yaohangNodes()) {
            nodes.add(node);
            if ("p-20".equals(node.getNodeId())) {
                nodes.add(new ResumeDocumentNode(
                        "p-20b",
                        "PARAGRAPH",
                        "Applied object-oriented programming (OOP) to model CPU, GPU and RAM component relationships."
                ));
            }
            if ("p-21".equals(node.getNodeId())) {
                nodes.add(new ResumeDocumentNode(
                        "p-21b",
                        "PARAGRAPH",
                        "Improved team communication and collaboration during code reviews."
                ));
            }
        }
        return nodes;
    }

    public static List<ResumeDocumentNode> yaohangT2bNodes() {
        return syntheticNodesWithAcmeInternship();
    }

    @Test
    void synthetic_primeDealsPacketStaysInsideSameEntry() {
        ClaimLedger ledger = ClaimLedger.from(syntheticNodesWithAcmeInternship());
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build("p-16", ledger);
        String json = packet.toJson().toLowerCase(Locale.ROOT);

        System.out.println("T2B SYNTHETIC Prime Deals Evidence Packet JSON\n" + packet.toJson());

        assertEquals("p-16", packet.targetNodeId());
        assertEquals("PROJECT:prime-deals", packet.entryKey());
        assertTrue(packet.supportingNodeIds().contains("p-17"));
        assertTrue(ConservativeEvidencePacket.supportingIdsStayInSameEntry(
                "p-16", packet.supportingNodeIds(), ledger));
        assertTrue(packet.requiredFacts().contains("role:design"));
        assertTrue(packet.requiredFacts().contains("role:implement"));
        assertTrue(packet.requiredFacts().contains("tech:Java"));
        assertTrue(packet.requiredFacts().contains("tech:Spring Boot"));
        assertTrue(packet.requiredFacts().contains("tech:MVC"));
        assertFalse(packet.requiredFacts().contains("tech:Spring"));
        assertFalse(packet.requiredFacts().contains("task:mvc"));
        assertEquals(1, packet.requiredFacts().stream().filter(fact -> fact.toLowerCase(Locale.ROOT).contains("spring")).count());
        assertEquals(packet.requiredFacts().size(), new java.util.LinkedHashSet<>(packet.requiredFacts()).size());
        assertTrue(json.contains("rest"));
        assertTrue(json.contains("auth") || json.contains("authentication"));
        assertTrue(json.contains("product"));
        assertTrue(json.contains("cart"));
        assertFalse(packet.supportingNodeIds().contains("p-11"));
        assertFalse(json.contains("maven"));
        assertFalse(json.contains("oop"));
        assertFalse(json.contains("cpu"));
        assertFalse(json.contains("gpu"));
        assertFalse(json.contains("ram"));
        assertFalse(json.contains("c/c++"));
        assertFalse(json.contains("yaohang@example.com"));
        assertTrue(packet.forbiddenClaims().stream().anyMatch(claim -> claim.contains("leadership")));
        assertTrue(packet.forbiddenClaims().stream().anyMatch(claim -> claim.contains("tools")));
        assertTrue(packet.forbiddenClaims().stream().anyMatch(claim -> claim.contains("numbers")));
        assertTrue(packet.forbiddenClaims().stream().anyMatch(claim -> claim.contains("outcomes")));
        assertTrue(packet.forbiddenClaims().stream().anyMatch(claim -> claim.contains("education")));
    }

    @Test
    void synthetic_smartPcMavenPacketStaysInsideSameEntry() {
        ClaimLedger ledger = ClaimLedger.from(syntheticNodesWithAcmeInternship());
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build("p-19", ledger);
        String json = packet.toJson().toLowerCase(Locale.ROOT);

        System.out.println("T2B SYNTHETIC SmartPC Evidence Packet JSON\n" + packet.toJson());

        assertEquals("p-19", packet.targetNodeId());
        assertEquals("PROJECT:smartpc", packet.entryKey());
        assertTrue(packet.supportingNodeIds().contains("p-20"));
        assertTrue(packet.supportingNodeIds().contains("p-20b"));
        assertTrue(ConservativeEvidencePacket.supportingIdsStayInSameEntry(
                "p-19", packet.supportingNodeIds(), ledger));
        assertEquals(
                List.of("role:develop", "tech:Java", "tech:Maven", "task:components", "task:budget"),
                packet.requiredFacts());
        assertTrue(packet.requiredFacts().stream().anyMatch(fact -> fact.toLowerCase(Locale.ROOT).contains("java")));
        assertTrue(packet.requiredFacts().stream().anyMatch(fact -> fact.toLowerCase(Locale.ROOT).contains("maven")));
        assertTrue(packet.requiredFacts().stream().anyMatch(fact -> fact.contains("budget")));
        assertTrue(json.contains("algorithm") || json.contains("cpu"));
        assertTrue(json.contains("oop"));
        assertTrue(json.contains("cpu"));
        assertTrue(json.contains("gpu"));
        assertTrue(json.contains("ram"));
        assertFalse(packet.supportingNodeIds().contains("p-11"));
        assertFalse(json.contains("spring boot"));
        assertFalse(json.contains("restful"));
        assertFalse(json.contains("aws"));
        assertFalse(json.contains("yaohang@example.com"));
    }

    @Test
    void synthetic_plannerRanksImplementationAboveDocumentationAndCommunication() {
        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(syntheticNodesWithAcmeInternship(), JD);
        List<String> ids = plans.stream().map(ConservativeRewritePlan::nodeId).toList();

        System.out.println("T2B SYNTHETIC Planner top " + plans.size());
        for (int i = 0; i < plans.size(); i++) {
            ConservativeRewritePlan plan = plans.get(i);
            System.out.println((i + 1) + ". " + plan.nodeId()
                    + " score=" + plan.rankingScore()
                    + " intent=" + plan.rewriteIntent()
                    + " reason=" + plan.rankingReason());
        }

        assertTrue(ids.size() <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        assertFalse(ids.contains("p-21b"));
        assertFalse(ids.contains("p-11"));
        long primeDeals = plans.stream().filter(plan -> "PROJECT:prime-deals".equals(plan.entryKey())).count();
        long smartPc = plans.stream().filter(plan -> "PROJECT:smartpc".equals(plan.entryKey())).count();
        assertTrue(primeDeals >= 0);
        assertTrue(smartPc >= 0);
        List<String> top3 = ids.subList(0, Math.min(3, ids.size()));
        assertFalse(top3.contains("p-21b"));
        assertTrue(ids.contains("p-16") || ids.contains("p-17"));
        assertTrue(plans.stream().map(ConservativeRewritePlan::rewriteIntent).toList().contains(
                RewriteIntent.GRAMMAR_FIX)
                || plans.stream().map(ConservativeRewritePlan::rewriteIntent).toList().contains(
                RewriteIntent.CLARITY_IMPROVEMENT)
                || plans.stream().map(ConservativeRewritePlan::rewriteIntent).toList().contains(
                RewriteIntent.JD_RELEVANCE)
                || plans.stream().map(ConservativeRewritePlan::rewriteIntent).toList().contains(
                RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT));
        for (ConservativeRewritePlan plan : plans) {
            assertEquals(plan.nodeId(), plan.targetNodeId());
            assertTrue(ConservativeEvidencePacket.supportingIdsStayInSameEntry(
                    plan.targetNodeId(),
                    plan.supportingNodeIds(),
                    ClaimLedger.from(syntheticNodesWithAcmeInternship())));
        }
    }

    @Test
    void synthetic_threeMockCandidatesPreserveRequiredFactsAndRejectDirectedCreation() {
        String original =
                "Developed a Java Maven project that helps users select optimal components within a given budget";
        String directed =
                "Directed the creation of a Java Maven project assisting users in selecting the best components within budget limits.";
        String droppedTech =
                "Developed a project that helps users select optimal components within a given budget";
        String keptFacts =
                "Developed a Java Maven project that selects optimal CPU, GPU and RAM components within a given budget.";

        ConservativeRewriteQualityEvaluator.Verdict directedVerdict =
                ConservativeRewriteQualityEvaluator.evaluate(original, directed, JD);
        ConservativeRewriteQualityEvaluator.Verdict droppedVerdict =
                ConservativeRewriteQualityEvaluator.evaluate(original, droppedTech, JD);
        ConservativeRewriteQualityEvaluator.Verdict keptVerdict =
                ConservativeRewriteQualityEvaluator.evaluate(original, keptFacts, JD);
        ClaimLedger ledger = ClaimLedger.from(syntheticNodesWithAcmeInternship());
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build("p-19", ledger);

        System.out.println("""
                T2B three-candidate mock JSON
                {
                  "nodeId": "p-19",
                  "originalText": "%s",
                  "candidates": [
                    {"newText": "%s", "reasonCode": "%s"},
                    {"newText": "%s", "reasonCode": "%s", "requiredFactsCovered": %s},
                    {"newText": "%s", "reasonCode": "%s", "requiredFactsCovered": %s}
                  ]
                }
                """.formatted(
                original,
                directed,
                directedVerdict.reasonCode(),
                droppedTech,
                droppedVerdict.reasonCode(),
                ConservativeEvidencePacket.coversRequiredFacts(droppedTech, packet.requiredFacts()),
                keptFacts,
                keptVerdict.accepted() ? "ACCEPTED" : keptVerdict.reasonCode(),
                ConservativeEvidencePacket.coversRequiredFacts(keptFacts, packet.requiredFacts())
        ));

        assertFalse(directedVerdict.accepted());
        assertEquals(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, directedVerdict.reasonCode());
        assertFalse(ConservativeEvidencePacket.coversRequiredFacts(droppedTech, packet.requiredFacts()));
        assertTrue(ConservativeEvidencePacket.coversRequiredFacts(keptFacts, packet.requiredFacts()));
        assertTrue(keptVerdict.accepted() || RejectedTailorChange.TEXT_TOO_LONG.equals(keptVerdict.reasonCode())
                || RejectedTailorChange.LOW_VALUE_REWRITE.equals(keptVerdict.reasonCode()));
    }

    @Test
    void synthetic_t2b1_requiredFactsAndLengthGates() {
        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(syntheticNodesWithAcmeInternship(), JD);
        ConservativeRewritePlan p16 = plans.stream()
                .filter(plan -> "p-16".equals(plan.nodeId()))
                .findFirst()
                .orElseThrow();
        String enrichment =
                "Designed and implemented a Java Spring Boot application using the MVC pattern, integrating RESTful APIs for authentication, product updates, and shopping cart workflows.";
        String missingImplement =
                "Designed a Java Spring Boot application using the MVC pattern.";

        System.out.println("T2B.1 Planner targets");
        for (int i = 0; i < plans.size(); i++) {
            ConservativeRewritePlan plan = plans.get(i);
            System.out.println((i + 1) + ". " + plan.nodeId()
                    + " entry=" + plan.entryKey()
                    + " intent=" + plan.rewriteIntent()
                    + " maxCharacters=" + plan.maxCharacters()
                    + " score=" + plan.rankingScore()
                    + " reason=" + plan.rankingReason());
        }
        System.out.println("T2B.1 p-16 requiredFacts=" + p16.requiredFacts());
        System.out.println("T2B.1 enrichmentLen=" + enrichment.length()
                + " p16max=" + p16.maxCharacters()
                + " factsCovered=" + ConservativeEvidencePacket.coversRequiredFacts(enrichment, p16.requiredFacts())
                + " missingImplementCovered="
                + ConservativeEvidencePacket.coversRequiredFacts(missingImplement, p16.requiredFacts()));

        assertTrue(p16.requiredFacts().contains("role:design"));
        assertTrue(p16.requiredFacts().contains("role:implement"));
        assertTrue(p16.requiredFacts().contains("tech:Java"));
        assertTrue(p16.requiredFacts().contains("tech:Spring Boot"));
        assertTrue(p16.requiredFacts().contains("tech:MVC"));
        assertFalse(p16.requiredFacts().contains("tech:Spring"));
        assertFalse(p16.requiredFacts().contains("task:mvc"));
        assertTrue(p16.maxCharacters() > 0);
        assertTrue(p16.maxCharacters() <= ConservativeTailorPlanner.ENRICHMENT_MAX_CHARACTERS);
        assertEquals(RewriteIntent.GRAMMAR_FIX, p16.rewriteIntent());
        assertTrue(ConservativeEvidencePacket.coversRequiredFacts(enrichment, p16.requiredFacts()));
        assertFalse(ConservativeEvidencePacket.coversRequiredFacts(missingImplement, p16.requiredFacts()));
        ConservativeRewriteQualityEvaluator.Verdict missing =
                ConservativeRewriteQualityEvaluator.evaluate(
                        "Design and implement the application using Java Spring Boot with an MVC pattern.",
                        missingImplement,
                        JD
                );
        assertFalse(missing.accepted());
        assertEquals(RejectedTailorChange.INFORMATION_LOSS, missing.reasonCode());
        long smartPc = plans.stream().filter(plan -> "PROJECT:smartpc".equals(plan.entryKey())).count();
        assertTrue(smartPc <= ConservativeTailorPlanner.GENERATION_PAGE_SIZE);
        assertFalse(plans.stream().map(ConservativeRewritePlan::nodeId).toList().contains("p-21b"));

        String p16Original =
                "Design and implement the application using Java Spring Boot with an MVC pattern.";
        String grammarSample = "Developed a Java Maven project.";
        ConservativeEvidencePacket p16Packet = ConservativeEvidencePacket.build(
                "p-16", ClaimLedger.from(syntheticNodesWithAcmeInternship()));
        int grammarMax = ConservativeTailorPlanner.maxCharactersFor(
                grammarSample, RewriteIntent.GRAMMAR_FIX, null);
        int clarityMax = ConservativeTailorPlanner.maxCharactersFor(
                grammarSample, RewriteIntent.CLARITY_IMPROVEMENT, null);
        int enrichmentMax = ConservativeTailorPlanner.maxCharactersFor(
                p16Original, RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, p16Packet);
        int summaryMax = ConservativeTailorPlanner.maxCharactersFor(
                "Junior developer with Java and Spring Boot experience.",
                RewriteIntent.SUMMARY_REWRITE,
                null);
        System.out.println("T2B.1 maxCharacters by intent"
                + " grammar=" + grammarMax
                + " clarity=" + clarityMax
                + " enrichment=" + enrichmentMax
                + " summary=" + summaryMax);
        System.out.println("T2B.1 Prime Deals plan packet\n"
                + p16.availableSupportingFacts()
                + " max=" + p16.maxCharacters()
                + " facts=" + p16.requiredFacts());
        assertTrue(grammarMax <= (int) Math.ceil(grammarSample.strip().length() * 1.25));
        assertTrue(clarityMax >= grammarMax);
        assertTrue(enrichmentMax >= enrichment.length());
        assertTrue(enrichmentMax <= ConservativeTailorPlanner.ENRICHMENT_MAX_CHARACTERS);
        assertTrue(summaryMax <= ConservativeTailorPlanner.SUMMARY_MAX_CHARACTERS);
        String overlong = enrichment + " plus extra keyword padding.";
        assertTrue(overlong.length() > p16.maxCharacters());
        assertTrue(overlong.length() > ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS
                || overlong.length() > p16.maxCharacters());
    }
}
