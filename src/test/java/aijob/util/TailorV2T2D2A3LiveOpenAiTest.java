package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ConservativeRewriteRepair;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Three controlled T2D.2A.3 live runs. Does not start Spring Boot and does not write uploads.
 * Enable with AIJOB_T2C_LIVE=true and OPENAI_API_KEY.
 */
class TailorV2T2D2A3LiveOpenAiTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";

    @TempDir
    Path tempDir;

    @Test
    @EnabledIfEnvironmentVariable(named = "AIJOB_T2C_LIVE", matches = "true")
    void threeControlledLiveRunsPrintFullCandidateAndBatchTraces() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required for T2D.2A.3 live runs");
        assumeTrue(!apiKey.contains("placeholder") && !apiKey.contains("sk-place"),
                "OPENAI_API_KEY is a placeholder");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o");
        Map<String, String> uploadsBefore = snapshotUploadsHashes();

        Path pdf = GeneratedResumePdfFixture.pdf();
        Path docx = tempDir.resolve("alexmorgan-canonical.docx");
        GeneratedResumePdfFixture.writeCanonicalDocx(pdf, docx);
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        OpenAiResumeServiceImpl ai = new OpenAiResumeServiceImpl(
                OpenAIOkHttpClient.builder().apiKey(apiKey).build(),
                model
        );

        for (int run = 1; run <= 3; run++) {
            runOnce(run, nodes, ledger, ai);
        }

        assertEquals(uploadsBefore, snapshotUploadsHashes(), "uploads hash must not change");
    }

    private static void runOnce(
            int run,
            List<ResumeDocumentNode> nodes,
            ClaimLedger ledger,
            OpenAiResumeServiceImpl ai) {

        List<ConservativeRewritePlan> plans = ConservativeTailorPlanner.plan(nodes, JD).stream()
                .map(plan -> ConservativeEvidencePacket.ensurePacket(plan, ledger))
                .toList();
        Set<String> reserved = new LinkedHashSet<>();
        for (ConservativeRewritePlan plan : plans) {
            if (plan != null && plan.nodeId() != null) {
                reserved.add(plan.nodeId());
            }
        }

        System.out.println("\n========== T2D.2A.3 LIVE RUN " + run + " plans=" + plans.size()
                + " reserved=" + reserved + " ==========");
        List<ConservativeBatchSelector.PlanPick> admitted = new ArrayList<>();
        for (ConservativeRewritePlan plan : plans) {
            ResumeDocumentNode node = nodeOf(nodes, plan.nodeId());
            if (node == null) {
                continue;
            }
            ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(plan.nodeId(), ledger);
            System.out.println("\nTARGET " + plan.nodeId() + " planIntent=" + plan.rewriteIntent());
            NodeAdmit first = admitPrintedCandidates(
                    ai.generateConstrainedTailorCandidates(node, plan, JD, null),
                    node, plan, packet, ledger, reserved, nodes, "first");
            if (first.picks().isEmpty() && isRepairable(first.lastReason())) {
                System.out.println("  REPAIR reason=" + first.lastReason());
                NodeAdmit repaired = admitPrintedCandidates(
                        ai.generateConstrainedTailorCandidates(
                                node,
                                plan,
                                JD,
                                new ConservativeRewriteRepair(first.lastReason(), firstCandidateText(first.raw()))),
                        node, plan, packet, ledger, reserved, nodes, "repair");
                admitted.addAll(repaired.picks());
            } else {
                admitted.addAll(first.picks());
            }
        }

        ConservativeBatchSelector.Selection batch = ConservativeBatchSelector.select(
                admitted,
                new ConservativeBatchSelector.Context(List.of(), reserved, Set.of()));
        batch = FinalResumeCoherenceValidator.prune(nodes, batch, List.of());
        batch = ConservativeLanguageJudge.apply(ai, JD, batch);
        System.out.println("\n--- FINAL SUGGESTIONS run=" + run + " size=" + batch.selected().size() + " ---");
        int relationHallucination = 0;
        int crossEntry = 0;
        int roleEscalation = 0;
        int unsupportedAction = 0;
        int copiedSupporting = FinalResumeCoherenceValidator.copiedSupportingFactCount(
                nodes, batch.selected().stream().map(ConservativeBatchSelector.PlanPick::change).toList());
        int lowValueAdmitted = 0;
        int umlCartOnRest = 0;
        int userAcceptable = 0;
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            ResumeDocumentNode node = nodeOf(nodes, pick.change().getNodeId());
            ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(pick.change().getNodeId(), ledger);
            ConservativeCandidateHardGate.Result batchGate = ConservativeCandidateHardGate.evaluate(
                    candidateFrom(pick), node, pick.plan(), packet, ledger, JD);
            String relation = EvidenceRelationValidator.rejectReason(
                    pick.change().getNewText(),
                    pick.change().getOriginalText(),
                    evidenceTexts(node, packet, ledger));
            String lower = pick.change().getNewText() == null ? "" : pick.change().getNewText().toLowerCase(Locale.ROOT);
            boolean restNode = node != null && node.getText() != null
                    && node.getText().toLowerCase(Locale.ROOT).contains("restful");
            if (restNode && lower.contains("uml") && lower.contains("cart")) {
                umlCartOnRest++;
            }
            if (relation != null || RejectedTailorChange.RELATION_NOT_SUPPORTED.equals(batchGate.reasonCode())) {
                relationHallucination++;
            }
            if (RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(batchGate.reasonCode())) {
                crossEntry++;
            }
            if (RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(batchGate.reasonCode())) {
                roleEscalation++;
            }
            if (RejectedTailorChange.UNSUPPORTED_ACTION.equals(batchGate.reasonCode())
                    || ActionProvenanceGate.rejectReason(
                    pick.change().getOriginalText(), pick.change().getNewText()) != null) {
                unsupportedAction++;
            }
            if (RewriteIntent.LOW_VALUE_REWRITE.equals(pick.intent())
                    || SurfaceRewriteEquivalence.isLowValueSurface(
                    pick.change().getOriginalText(), pick.change().getNewText())) {
                lowValueAdmitted++;
            }
            if (pick.score() != null
                    && pick.score().admitted()
                    && pick.score().total() >= ConservativeCandidateLocalScorer.ADMIT_TOTAL
                    && pick.score().clarity() >= ConservativeCandidateLocalScorer.MIN_CLARITY
                    && pick.score().factualPreservation() == 100
                    && !RewriteIntent.LOW_VALUE_REWRITE.equals(pick.intent())
                    && !SurfaceRewriteEquivalence.isLowValueSurface(
                    pick.change().getOriginalText(), pick.change().getNewText())) {
                userAcceptable++;
            }
            System.out.println("FINAL suggestion");
            System.out.println("  originalText=" + pick.change().getOriginalText());
            System.out.println("  newText=" + pick.change().getNewText());
            System.out.println("  reasonCode=" + (batchGate.passed() ? "ACCEPTED" : batchGate.reasonCode()));
            System.out.println("  resolvedSupportingNodeIds=" + pick.usedFactNodeIds());
            System.out.println("  classifiedIntent=" + pick.intent());
            System.out.println("  hardGate passed=" + batchGate.passed() + " reason=" + batchGate.reasonCode());
            System.out.println("  localScore total=" + (pick.score() == null ? "n/a" : pick.score().total())
                    + " admitted=" + (pick.score() != null && pick.score().admitted())
                    + " uplift=" + (pick.score() == null ? "" : pick.score().upliftType()));
            System.out.println("  reasonZh=" + pick.change().getReasonZh());
            System.out.println("  reasonEn=" + pick.change().getReason());
            assertFalse(lower.contains("structured architecture"), "vague purpose tail");
            assertFalse(lower.contains("utilizing java spring boot"), "awkward utilizing");
            assertFalse(lower.contains("updates to products"), "awkward updates to products");
            assertFalse(lower.contains("shopping cart processes"), "awkward cart processes");
            assertFalse(ConservativeRewriteReasons.isBannedTemplate(pick.change().getReasonZh()),
                    "banned reason template");
        }
        System.out.println("run=" + run
                + " unsupportedAction=" + unsupportedAction
                + " copiedSupporting=" + copiedSupporting
                + " relationHallucination=" + relationHallucination
                + " lowValueAdmitted=" + lowValueAdmitted
                + " userAcceptable=" + userAcceptable
                + " finalCount=" + batch.selected().size());

        assertEquals(0, unsupportedAction, "run " + run + " unsupported action");
        assertEquals(0, copiedSupporting, "run " + run + " copied supporting facts");
        assertEquals(0, lowValueAdmitted, "run " + run + " low-value admitted");
        assertEquals(0, relationHallucination, "run " + run + " relation hallucination");
        assertEquals(0, crossEntry, "run " + run + " cross-entry");
        assertEquals(0, roleEscalation, "run " + run + " role escalation");
        assertEquals(0, umlCartOnRest, "run " + run + " p-17 UML+cart");
        assertTrue(batch.selected().size() <= 4, "run " + run + " too many suggestions");
    }

    private static NodeAdmit admitPrintedCandidates(
            ConservativeRewriteCandidatesResponse response,
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger,
            Set<String> reserved,
            List<ResumeDocumentNode> nodes,
            String phase) {

        List<ConservativeRewriteCandidate> candidates =
                response == null || response.getCandidates() == null ? List.of() : response.getCandidates();
        List<ConservativeBatchSelector.PlanPick> picks = new ArrayList<>();
        String lastReason = RejectedTailorChange.EMPTY_REQUIRED_FIELD;
        for (int i = 0; i < candidates.size(); i++) {
            ConservativeRewriteCandidate candidate = candidates.get(i);
            ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                    candidate, node, plan, packet, ledger, JD);
            String reservedReject = gate.passed()
                    ? ConservativeReservedTargetPolicy.absorbReason(
                    node.getNodeId(), node.getText(), candidate.getNewText(), reserved, nodes)
                    : null;
            ConservativeCandidateLocalScorer.Score score = null;
            if (gate.passed() && reservedReject == null) {
                score = ConservativeCandidateLocalScorer.score(
                        node, plan, packet, ledger, JD, candidate.getNewText(),
                        gate.usedSupportingNodeIds(), gate.quality());
            }
            System.out.println("  cand[" + phase + "][" + i + "]");
            System.out.println("    originalText=" + node.getText());
            System.out.println("    newText=" + candidate.getNewText());
            System.out.println("    reasonCode=" + (gate.passed()
                    ? (reservedReject != null ? reservedReject
                    : (score != null && !score.admitted() ? score.rejectReason() : "ACCEPTED"))
                    : gate.reasonCode()));
            System.out.println("    resolvedSupportingNodeIds="
                    + (gate.passed() ? gate.usedSupportingNodeIds() : List.of()));
            System.out.println("    classifiedIntent=" + (gate.passed()
                    ? (score == null ? gate.classifiedIntent() : score.classifiedIntent())
                    : ""));
            System.out.println("    hardGate passed=" + gate.passed()
                    + " reason=" + gate.reasonCode()
                    + " reservedReject=" + reservedReject);
            if (score != null) {
                System.out.println("    localScore total=" + score.total()
                        + " factual=" + score.factualPreservation()
                        + " jd=" + score.jdRelevance()
                        + " substantive=" + score.substantiveImprovement()
                        + " clarity=" + score.clarity()
                        + " margin=" + score.improvementMargin()
                        + " admitted=" + score.admitted()
                        + " uplift=" + score.upliftType()
                        + " classifiedIntent=" + score.classifiedIntent()
                        + " reject=" + score.rejectReason());
            } else {
                System.out.println("    localScore=skipped");
            }
            if (!gate.passed()) {
                lastReason = gate.reasonCode();
                continue;
            }
            if (reservedReject != null) {
                lastReason = reservedReject;
                continue;
            }
            if (score == null || !score.admitted()) {
                lastReason = score == null ? RejectedTailorChange.LOW_VALUE_REWRITE : score.rejectReason();
                continue;
            }
            TailorChange change = new TailorChange();
            change.setNodeId(node.getNodeId());
            change.setOriginalText(node.getText());
            change.setNewText(candidate.getNewText());
            List<String> supportingTexts = new ArrayList<>();
            if (gate.usedSupportingNodeIds() != null) {
                for (String id : gate.usedSupportingNodeIds()) {
                    supportingTexts.add(ConservativeCandidateHardGate.textOf(ledger, id));
                }
            }
            ConservativeRewriteReasons.apply(change, score.classifiedIntent(), supportingTexts);
            picks.add(new ConservativeBatchSelector.PlanPick(
                    plan, change, score, withTarget(node.getNodeId(), gate.usedSupportingNodeIds())));
        }
        return new NodeAdmit(picks, lastReason, candidates);
    }

    private static boolean isRepairable(String reasonCode) {
        return RejectedTailorChange.TEXT_TOO_LONG.equals(reasonCode)
                || RejectedTailorChange.TEXT_UNCHANGED.equals(reasonCode)
                || RejectedTailorChange.LOW_VALUE_REWRITE.equals(reasonCode)
                || RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(reasonCode)
                || RejectedTailorChange.INFORMATION_LOSS.equals(reasonCode)
                || RejectedTailorChange.GRAMMAR_QUALITY_FAILED.equals(reasonCode)
                || RejectedTailorChange.CROSS_NODE_EVIDENCE_NOT_ALLOWED.equals(reasonCode)
                || RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE.equals(reasonCode)
                || RejectedTailorChange.UNSUPPORTED_ACTION.equals(reasonCode)
                || RejectedTailorChange.RELATION_NOT_SUPPORTED.equals(reasonCode);
    }

    private static String firstCandidateText(List<ConservativeRewriteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty() || candidates.get(0) == null) {
            return null;
        }
        return candidates.get(0).getNewText();
    }

    private record NodeAdmit(
            List<ConservativeBatchSelector.PlanPick> picks,
            String lastReason,
            List<ConservativeRewriteCandidate> raw) {
    }

    private static List<String> withTarget(String targetNodeId, List<String> supporting) {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        if (targetNodeId != null) {
            used.add(targetNodeId);
        }
        if (supporting != null) {
            used.addAll(supporting);
        }
        return List.copyOf(used);
    }

    private static List<String> evidenceTexts(
            ResumeDocumentNode node,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        List<String> texts = new ArrayList<>();
        if (node != null && node.getText() != null) {
            texts.add(node.getText());
        }
        if (packet != null && packet.supportingNodeIds() != null) {
            for (String id : packet.supportingNodeIds()) {
                texts.add(ConservativeCandidateHardGate.textOf(ledger, id));
            }
        }
        return texts;
    }

    private static int countDuplicateOptionalSupporting(List<ConservativeBatchSelector.PlanPick> selected) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        for (ConservativeBatchSelector.PlanPick pick : selected) {
            if (pick.change() != null && pick.change().getNodeId() != null) {
                targets.add(pick.change().getNodeId());
            }
        }
        for (ConservativeBatchSelector.PlanPick pick : selected) {
            if (pick.usedFactNodeIds() == null) {
                continue;
            }
            for (String id : pick.usedFactNodeIds()) {
                if (id == null || targets.contains(id) || id.equals(pick.change().getNodeId())) {
                    continue;
                }
                counts.merge(id, 1, Integer::sum);
            }
        }
        int duplicates = 0;
        for (int count : counts.values()) {
            if (count > 1) {
                duplicates += count - 1;
            }
        }
        return duplicates;
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

    private static Map<String, String> snapshotUploadsHashes() throws Exception {
        Path root = Path.of("uploads");
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        TreeMap<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String hash;
                try {
                    hash = java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
                } catch (Exception ex) {
                    hash = "locked";
                }
                snapshot.put(
                        root.relativize(file).toString().replace('\\', '/'),
                        Files.size(file) + "|" + hash
                );
            }
        }
        return snapshot;
    }
}
