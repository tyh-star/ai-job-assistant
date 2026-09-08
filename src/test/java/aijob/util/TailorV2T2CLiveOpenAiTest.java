package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.service.impl.OpenAiResumeServiceImpl;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One-shot T2C.4 live generation. Does not start Spring Boot and does not write uploads.
 * Enable with AIJOB_T2C_LIVE=true and OPENAI_API_KEY.
 */
class TailorV2T2CLiveOpenAiTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @TempDir
    Path tempDir;

    @Test
    @EnabledIfEnvironmentVariable(named = "AIJOB_T2C_LIVE", matches = "true")
    void realOpenAiCandidatesAreHardGatedAndBatchSelectedOnce() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required for the one-shot T2C live run");
        assumeTrue(!apiKey.contains("placeholder") && !apiKey.contains("sk-place"),
                "OPENAI_API_KEY is a placeholder");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");

        Path pdf = GeneratedResumePdfFixture.pdf();
        Path docx = tempDir.resolve("alexmorgan-canonical.docx");
        GeneratedResumePdfFixture.writeCanonicalDocx(pdf, docx);
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(nodes, JD).stream()
                .map(plan -> ConservativeEvidencePacket.ensurePacket(plan, ledger))
                .toList();

        OpenAiResumeServiceImpl ai = new OpenAiResumeServiceImpl(
                OpenAIOkHttpClient.builder().apiKey(apiKey).build(),
                model
        );

        Map<String, Integer> rawRejections = new LinkedHashMap<>();
        List<ConservativeBatchSelector.PlanPick> admitted = new ArrayList<>();
        System.out.println("T2C.4 LIVE plans=" + plans.size());
        for (ConservativeRewritePlan plan : plans) {
            ResumeDocumentNode node = nodes.stream()
                    .filter(item -> plan.nodeId().equals(item.getNodeId()))
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                continue;
            }
            ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(plan.nodeId(), ledger);
            var response = ai.generateConstrainedTailorCandidates(node, plan, JD, null);
            List<ConservativeRewriteCandidate> candidates =
                    response == null || response.getCandidates() == null ? List.of() : response.getCandidates();
            System.out.println("\nTARGET " + plan.nodeId() + " planIntent=" + plan.rewriteIntent());
            System.out.println("original=" + node.getText());
            System.out.println("supportingNodeIds=" + plan.supportingNodeIds());
            for (int i = 0; i < candidates.size(); i++) {
                ConservativeRewriteCandidate candidate = candidates.get(i);
                ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                        candidate, node, plan, packet, ledger, JD);
                ConservativeCandidateLocalScorer.Score score = gate.passed()
                        ? ConservativeCandidateLocalScorer.score(
                        node, plan, packet, ledger, JD, candidate.getNewText(),
                        gate.usedSupportingNodeIds(), gate.quality())
                        : null;
                System.out.println("  cand[" + i + "] modelIntent=" + candidate.getImprovementIntent()
                        + " modelSupporting=" + candidate.getUsedSupportingNodeIds());
                System.out.println("  newText=" + candidate.getNewText());
                System.out.println("  hardGate passed=" + gate.passed()
                        + " reason=" + gate.reasonCode()
                        + " serverIntent=" + gate.classifiedIntent()
                        + " resolvedSupporting=" + gate.usedSupportingNodeIds());
                if (score != null) {
                    System.out.println("  localScore total=" + score.total()
                            + " factual=" + score.factualPreservation()
                            + " jd=" + score.jdRelevance()
                            + " substantive=" + score.substantiveImprovement()
                            + " spec=" + score.specificity()
                            + " clarity=" + score.clarity()
                            + " concise=" + score.conciseness()
                            + " margin=" + score.improvementMargin()
                            + " admitted=" + score.admitted()
                            + " reject=" + score.rejectReason()
                            + " classifiedIntent=" + score.classifiedIntent());
                }
                if (!gate.passed()) {
                    rawRejections.merge(gate.reasonCode(), 1, Integer::sum);
                    continue;
                }
                if (score != null && !score.admitted()) {
                    rawRejections.merge(score.rejectReason(), 1, Integer::sum);
                    continue;
                }
                if (score != null && score.admitted()) {
                    TailorChange change = new TailorChange();
                    change.setNodeId(node.getNodeId());
                    change.setOriginalText(node.getText());
                    change.setNewText(candidate.getNewText());
                    admitted.add(new ConservativeBatchSelector.PlanPick(
                            plan, change, score, gate.usedSupportingNodeIds()));
                }
            }
        }

        ConservativeBatchSelector.Selection batch = ConservativeBatchSelector.select(admitted);
        System.out.println("\nrawCandidateRejections:");
        for (String code : List.of(
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION,
                RejectedTailorChange.INFORMATION_LOSS,
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                RejectedTailorChange.LOW_VALUE_REWRITE,
                RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED,
                RejectedTailorChange.TEXT_TOO_LONG,
                RejectedTailorChange.TEXT_UNCHANGED,
                RejectedTailorChange.EMPTY_REQUIRED_FIELD
        )) {
            System.out.println("  " + code + "=" + rawRejections.getOrDefault(code, 0));
            rawRejections.remove(code);
        }
        for (Map.Entry<String, Integer> entry : rawRejections.entrySet()) {
            System.out.println("  " + entry.getKey() + "=" + entry.getValue());
        }
        System.out.println("\nT2C.4 LIVE BATCH size=" + batch.selected().size());
        int userAcceptable = 0;
        int batchHallucination = 0;
        int batchCross = 0;
        int batchRole = 0;
        int batchRequired = 0;
        int batchLowValue = 0;
        int batchDuplicate = 0;
        int batchOutcome = 0;
        int batchLowClarity = 0;
        int batchUnsupportedContext = 0;
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            System.out.println("  " + pick.change().getNodeId()
                    + " intent=" + pick.intent()
                    + " score=" + pick.score().total()
                    + " clarity=" + pick.score().clarity()
                    + " substantive=" + pick.score().substantiveImprovement()
                    + " supporting=" + pick.usedFactNodeIds()
                    + " coverage=" + ConservativeMaterialFacts.coverage(
                    pick.change().getOriginalText(), pick.change().getNewText())
                    + " " + pick.change().getNewText());
            ConservativeCandidateHardGate.Result batchGate = ConservativeCandidateHardGate.evaluate(
                    candidateFrom(pick),
                    nodeOf(nodes, pick.change().getNodeId()),
                    pick.plan(),
                    ConservativeEvidencePacket.build(pick.change().getNodeId(), ledger),
                    ledger,
                    JD);
            String lower = pick.change().getNewText().toLowerCase(Locale.ROOT);
            if (lower.contains("docker") || lower.contains("kubernetes")) {
                batchHallucination++;
            }
            if (!batchGate.passed()) {
                if (RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(batchGate.reasonCode())) {
                    batchRole++;
                } else if (RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(batchGate.reasonCode())) {
                    batchCross++;
                } else if (RejectedTailorChange.INFORMATION_LOSS.equals(batchGate.reasonCode())) {
                    batchRequired++;
                } else if (RejectedTailorChange.UNSUPPORTED_CLAIM.equals(batchGate.reasonCode())) {
                    batchHallucination++;
                }
            }
            if (ConservativeCandidateHardGate.inventsUnsupportedOutcome(
                    pick.change().getOriginalText(),
                    pick.change().getNewText(),
                    ConservativeEvidencePacket.build(pick.change().getNodeId(), ledger),
                    ledger)) {
                batchOutcome++;
            }
            if (ConservativeCandidateHardGate.inventsUnsupportedContext(
                    pick.change().getOriginalText(),
                    pick.change().getNewText(),
                    ConservativeEvidencePacket.build(pick.change().getNodeId(), ledger),
                    ledger)) {
                batchUnsupportedContext++;
            }
            if (pick.score().clarity() < ConservativeCandidateLocalScorer.MIN_CLARITY) {
                batchLowClarity++;
            }
            if (RewriteIntent.LOW_VALUE_REWRITE.equals(pick.intent())
                    || pick.score().substantiveImprovement() < ConservativeCandidateLocalScorer.MIN_SUBSTANTIVE) {
                batchLowValue++;
            }
            if (RewriteIntent.SUMMARY_REWRITE.equals(pick.intent())
                    && ConservativeCandidateIntent.isSoftSkillSummary(pick.change().getNewText())) {
                batchLowValue++;
            }
            if (pick.score().admitted()
                    && pick.score().total() >= ConservativeCandidateLocalScorer.ADMIT_TOTAL
                    && pick.score().clarity() >= ConservativeCandidateLocalScorer.MIN_CLARITY
                    && pick.score().substantiveImprovement() >= ConservativeCandidateLocalScorer.MIN_SUBSTANTIVE
                    && pick.score().factualPreservation() == 100
                    && !RewriteIntent.LOW_VALUE_REWRITE.equals(pick.intent())
                    && !RewriteIntent.SUMMARY_REWRITE.equals(pick.intent())) {
                userAcceptable++;
            }
        }
        batchDuplicate = countDuplicateSupportingTargets(batch.selected());
        int circularSupporting = ConservativeBatchSelector.circularSupportingCount(batch.selected());
        int semanticOverlap = ConservativeBatchSelector.semanticOverlapCount(batch.selected());
            double materialRetention = ConservativeMaterialFacts.batchRetention(
                batch.selected().stream().map(pick -> pick.change().getOriginalText()).toList(),
                batch.selected().stream().map(pick -> pick.change().getNewText()).toList());
        List<String> missingMaterial = new ArrayList<>();
        String union = String.join(" ", batch.selected().stream().map(pick -> pick.change().getNewText()).toList());
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            for (ConservativeMaterialFacts.Fact fact : ConservativeMaterialFacts.distinctive(
                    ConservativeMaterialFacts.extract(pick.change().getOriginalText()))) {
                if (!ConservativeMaterialFacts.covers(fact, union)) {
                    missingMaterial.add(pick.change().getNodeId() + ":" + fact.key());
                }
            }
        }
        int genericSupporting = 0;
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            List<String> supporting = pick.usedFactNodeIds() == null ? List.of() : pick.usedFactNodeIds();
            for (String supportingId : supporting) {
                if (supportingId.equals(pick.change().getNodeId())) {
                    continue;
                }
                ResumeDocumentNode supportingNode = nodeOf(nodes, supportingId);
                if (supportingNode == null) {
                    continue;
                }
                if (!ConservativeMaterialFacts.contributesConcreteFact(
                        pick.change().getOriginalText(),
                        supportingNode.getText(),
                        pick.change().getNewText())) {
                    genericSupporting++;
                }
            }
        }
        System.out.println("\nT2C.4 FINAL NEWTEXT");
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            System.out.println(pick.change().getNodeId() + "=" + pick.change().getNewText());
        }
        System.out.println("\nfinalBatchViolations:");
        System.out.println("  hallucination=" + batchHallucination);
        System.out.println("  crossEntry=" + batchCross);
        System.out.println("  roleEscalation=" + batchRole);
        System.out.println("  requiredLoss=" + batchRequired);
        System.out.println("  duplicateFacts=" + batchDuplicate);
        System.out.println("  lowValue=" + batchLowValue);
        System.out.println("  unsupportedOutcome=" + batchOutcome);
        System.out.println("  lowClarity=" + batchLowClarity);
        System.out.println("  circularSupporting=" + circularSupporting);
        System.out.println("  semanticOverlap=" + semanticOverlap);
        System.out.println("  unsupportedContext=" + batchUnsupportedContext);
        System.out.println("  genericSupporting=" + genericSupporting);
        System.out.println("  materialRetention=" + materialRetention);
        System.out.println("  missingMaterial=" + missingMaterial);
        System.out.println("  userAcceptable=" + userAcceptable);

        assertEquals(0, batchHallucination, "final batch hallucination");
        assertEquals(0, batchCross, "final batch cross-entry");
        assertEquals(0, batchRole, "final batch role escalation");
        assertEquals(0, batchRequired, "final batch required-fact loss");
        assertEquals(0, batchDuplicate, "final batch duplicate supporting facts");
        assertEquals(0, batchLowValue, "final batch low-value");
        assertEquals(0, batchOutcome, "final batch unsupported outcome");
        assertEquals(0, batchLowClarity, "clarity below 60 must not be admitted");
        assertEquals(0, circularSupporting, "circularSupporting");
        assertEquals(0, semanticOverlap, "semanticOverlap");
        assertEquals(0, batchUnsupportedContext, "unsupportedContext");
        assertEquals(0, genericSupporting, "genericSupporting");
        assertEquals(1.0, materialRetention, 0.001, "materialFacts retention " + missingMaterial);
        assertTrue(batch.selected().size() <= ConservativeBatchSelector.MAX_SUGGESTIONS);
        assertTrue(batch.selected().size() >= 2, "final batch too small: " + batch.selected().size());
        assertTrue(
                batch.selected().stream().noneMatch(pick ->
                        RewriteIntent.SUMMARY_REWRITE.equals(pick.intent())
                                && ConservativeCandidateIntent.isSoftSkillSummary(pick.change().getNewText())),
                "low-value Summary must not enter the final batch");
        assertTrue(userAcceptable >= 2, "userAcceptable=" + userAcceptable);
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            assertFalse(pick.score().clarity() < 60, pick.change().getNodeId());
            assertEquals(100, pick.score().factualPreservation());
        }
    }

    private static ConservativeRewriteCandidate candidateFrom(ConservativeBatchSelector.PlanPick pick) {
        ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
        candidate.setNodeId(pick.change().getNodeId());
        candidate.setNewText(pick.change().getNewText());
        candidate.setUsedSupportingNodeIds(pick.usedFactNodeIds());
        candidate.setUsedFactNodeIds(pick.usedFactNodeIds());
        return candidate;
    }

    private static ResumeDocumentNode nodeOf(List<ResumeDocumentNode> nodes, String nodeId) {
        return nodes.stream()
                .filter(item -> nodeId.equals(item.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    private static int countDuplicateSupportingTargets(List<ConservativeBatchSelector.PlanPick> selected) {
        int duplicates = 0;
        for (int i = 0; i < selected.size(); i++) {
            ConservativeBatchSelector.PlanPick left = selected.get(i);
            List<String> supporting = left.usedFactNodeIds() == null ? List.of() : left.usedFactNodeIds();
            for (int j = 0; j < selected.size(); j++) {
                if (i == j) {
                    continue;
                }
                ConservativeBatchSelector.PlanPick right = selected.get(j);
                if (supporting.contains(right.change().getNodeId())
                        && ConservativeMaterialFacts.fullySubsumes(
                        right.change().getNewText(), left.change().getNewText())) {
                    duplicates++;
                }
            }
        }
        return duplicates;
    }
}
