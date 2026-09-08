package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.TailorProposalState;
import aijob.service.impl.ResumeDocumentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorV2T2D2A2CoherenceTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";
    private static final String P16_HOME = ConservativeCandidateHardGateTest.P16_HOME;
    private static final String P17_HOME = ConservativeCandidateHardGateTest.P17_HOME;
    private static final String P21_HOME = ConservativeCandidateHardGateTest.P21_HOME;
    private static final String P17_AWS =
            "Integrated RESTful APIs for user authentication, product updates, and cart management, deploying to AWS.";
    private static final String P21_OOP =
            "Developed a Maven-based Java project using OOP principles to help users select optimal components within a specified budget.";
    private static final String P21_CPU =
            "Developed a Java Maven project that helps users select optimal CPU, GPU, and RAM components within a given budget.";
    private static final String P23_DESIGNED =
            "Designed and implemented an algorithm to dynamically recommend CPU, GPU, and RAM components.";
    private static final String P23_SURFACE =
            "Implemented an algorithm to dynamically suggest components such as CPU, GPU, and RAM.";

    @TempDir
    Path tempDir;

    private List<ResumeDocumentNode> nodes;
    private ClaimLedger ledger;
    private ResumeDocumentNode p16;
    private ResumeDocumentNode p17;
    private ResumeDocumentNode p21;
    private ResumeDocumentNode p23;

    @BeforeEach
    void parseCanonical() throws Exception {
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path docx = tempDir.resolve("alexmorgan-canonical.docx");
        GeneratedResumePdfFixture.writeCanonicalDocx(pdf, docx);
        nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ledger = ClaimLedger.from(nodes);
        p16 = require("Design and implement", "Spring Boot", "MVC");
        p17 = require("Integrated RESTful APIs");
        p21 = require("Developed a Java Maven project");
        p23 = require("Implemented an algorithm", "CPU");
    }

    @Test
    void p17MustNotCopyP18Aws() {
        ConservativeCandidateHardGate.Result gate = gate(p17, P17_AWS);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
    }

    @Test
    void p21MustNotCopyOopOrCpu() {
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate(p21, P21_OOP).reasonCode());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate(p21, P21_CPU).reasonCode());
    }

    @Test
    void p23MustNotAddDesigned() {
        ConservativeCandidateHardGate.Result gate = gate(p23, P23_DESIGNED);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.UNSUPPORTED_ACTION, gate.reasonCode());
    }

    @Test
    void p23SurfaceRewriteIsLowValue() {
        assertTrue(SurfaceRewriteEquivalence.isLowValueSurface(p23.getText(), P23_SURFACE));
        ConservativeCandidateHardGate.Result gate = gate(p23, P23_SURFACE);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.LOW_VALUE_REWRITE, gate.reasonCode());
    }

    @Test
    void homeRewritesPassAndFinalResumeHasNoCopiedFacts() {
        assertTrue(gate(p16, P16_HOME).passed(), gate(p16, P16_HOME).reasonCode());
        assertTrue(gate(p17, P17_HOME).passed(), gate(p17, P17_HOME).reasonCode());
        assertTrue(gate(p21, P21_HOME).passed(), gate(p21, P21_HOME).reasonCode());
        List<TailorChange> changes = List.of(
                change(p16.getNodeId(), p16.getText(), P16_HOME),
                change(p17.getNodeId(), p17.getText(), P17_HOME),
                change(p21.getNodeId(), p21.getText(), P21_HOME)
        );
        assertEquals(0, FinalResumeCoherenceValidator.copiedSupportingFactCount(nodes, changes));
        assertTrue(FinalResumeCoherenceValidator.evaluate(nodes, changes).passed());
    }

    @Test
    void copiedAwsBatchIsPrunedFromFinalResume() {
        ConservativeBatchSelector.PlanPick copied = pick(
                p17.getNodeId(), "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                p17.getText(), P17_AWS, List.of(p17.getNodeId(), "p-18"));
        ConservativeBatchSelector.PlanPick home = pick(
                p16.getNodeId(), "PROJECT:prime", RewriteIntent.CLARITY_IMPROVEMENT, 86,
                p16.getText(), P16_HOME, List.of(p16.getNodeId()));
        ConservativeBatchSelector.Selection batch = ConservativeBatchSelector.select(List.of(copied, home));
        ConservativeBatchSelector.Selection pruned =
                FinalResumeCoherenceValidator.prune(nodes, batch, List.of());
        assertTrue(pruned.selected().stream().noneMatch(item ->
                item.change().getNewText() != null
                        && item.change().getNewText().toLowerCase(Locale.ROOT).contains("aws")));
        assertEquals(
                0,
                FinalResumeCoherenceValidator.copiedSupportingFactCount(
                        nodes,
                        pruned.selected().stream().map(ConservativeBatchSelector.PlanPick::change).toList()));
    }

    @Test
    void continueAlsoPrunesCopiedSupportingFacts() {
        TailorChange locked = change(p16.getNodeId(), p16.getText(), P16_HOME);
        ConservativeBatchSelector.PlanPick copied = pick(
                p17.getNodeId(), "PROJECT:prime", RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT, 90,
                p17.getText(), P17_AWS, List.of(p17.getNodeId(), "p-18"));
        ConservativeBatchSelector.Selection batch = new ConservativeBatchSelector.Selection(
                List.of(copied), List.of());
        ConservativeBatchSelector.Selection pruned =
                FinalResumeCoherenceValidator.prune(nodes, batch, List.of(locked));
        assertTrue(pruned.selected().isEmpty(), pruned.selected().toString());
        assertTrue(pruned.dropped().stream().anyMatch(item ->
                p17.getNodeId().equals(item.getNodeId())
                        && RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE.equals(item.getReasonCode())));
        assertEquals(
                0,
                FinalResumeCoherenceValidator.copiedSupportingFactCount(
                        nodes,
                        List.of(locked)));
    }

    @Test
    void developedToLedRemainsRoleEscalation() {
        assertEquals(
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION,
                gate(p21, "Led a Java Maven project that helps users select optimal components within a given budget")
                        .reasonCode());
    }

    @Test
    void legacyProposalJsonStillParses() throws Exception {
        TailorChange stored = change(p16.getNodeId(), p16.getText(), P16_HOME);
        ObjectMapper mapper = new ObjectMapper();
        TailorProposalState fromArray = TailorProposalState.parse(mapper, mapper.writeValueAsString(List.of(stored)));
        assertEquals(p16.getNodeId(), fromArray.getChanges().get(0).getNodeId());
        TailorProposalState envelope = TailorProposalState.fromChanges(List.of(stored), List.of(p16.getNodeId(), p21.getNodeId()));
        TailorProposalState fromObject = TailorProposalState.parse(mapper, mapper.writeValueAsString(envelope));
        assertTrue(fromObject.getExploredNodeIds().contains(p16.getNodeId()));
        assertTrue(fromObject.getExploredNodeIds().contains(p21.getNodeId()));
    }

    private ConservativeCandidateHardGate.Result gate(ResumeDocumentNode node, String rewritten) {
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(node.getNodeId(), ledger);
        ConservativeRewritePlan plan = ConservativeCandidateHardGateTest.planFor(node, packet);
        ConservativeRewriteCandidate candidate =
                ConservativeCandidateHardGateTest.candidate(node.getNodeId(), rewritten, List.of());
        return ConservativeCandidateHardGate.evaluate(candidate, node, plan, packet, ledger, JD);
    }

    private ResumeDocumentNode require(String... needles) {
        for (ResumeDocumentNode node : nodes) {
            String text = node.getText() == null ? "" : node.getText();
            boolean all = true;
            for (String needle : needles) {
                if (!text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return node;
            }
        }
        throw new AssertionError("Missing node " + String.join(" + ", needles));
    }

    private static TailorChange change(String nodeId, String original, String rewritten) {
        TailorChange change = new TailorChange();
        change.setNodeId(nodeId);
        change.setOriginalText(original);
        change.setNewText(rewritten);
        return change;
    }

    private static ConservativeBatchSelector.PlanPick pick(
            String nodeId,
            String entryKey,
            String intent,
            int total,
            String original,
            String rewritten,
            List<String> supporting) {

        ConservativeRewritePlan plan = new ConservativeRewritePlan(
                nodeId,
                nodeId,
                entryKey,
                List.of(),
                intent,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("java"),
                "rewrite",
                170,
                "BULLET",
                List.of(nodeId),
                "",
                total,
                ""
        );
        TailorChange change = change(nodeId, original, rewritten);
        change.setEvidenceNodeIds(supporting);
        ConservativeCandidateLocalScorer.Score score = new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, total, 60, total - 60, true, true,
                ConservativeRewriteReasons.upliftTypeFor(intent), false, intent, null);
        return new ConservativeBatchSelector.PlanPick(plan, change, score, supporting);
    }
}
