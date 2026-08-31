package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityResolutionGateTest {

    private static final String P16_ORIGINAL =
            "Design and implement the application using Java Spring Boot with an MVC pattern.";
    private static final String P16_APPLICATIONS =
            "Design and implement applications using Java Spring Boot with an MVC pattern.";
    private static final String P16_PAST =
            "Designed and implemented a Java Spring Boot application using the MVC architecture.";
    private static final String P17_GOLD =
            "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.";
    private static final String P17_STILL_MANAGEMENTS =
            "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart managements.";
    private static final String P14_PAST =
            "Led and collaborated with a friend to develop a shopping website";

    @TempDir
    Path tempDir;

    private List<ResumeDocumentNode> nodes;
    private ClaimLedger ledger;

    @BeforeEach
    void parseRealCanonicalDocx() throws Exception {
        Path pdf = YaohangCanonicalDocxFixture.pdf();
        Path docx = tempDir.resolve("yaohang-canonical.docx");
        YaohangCanonicalDocxFixture.writeCanonicalDocx(pdf, docx);
        nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ledger = ClaimLedger.from(nodes);
    }

    @Test
    void primeDealsPresentIsOngoingAndSmartPcIsEnded() {
        ResumeDocumentNode p16 = requireNode("Design and implement", "Spring Boot", "MVC");
        assertTrue(ledger.isOngoing(p16.getNodeId()), ledger.entryText(p16.getNodeId()));
        assertFalse(ledger.isEnded(p16.getNodeId()));
        ResumeDocumentNode smart = requireNode("Java Maven project");
        assertFalse(ledger.isOngoing(smart.getNodeId()), ledger.entryText(smart.getNodeId()));
        assertTrue(ledger.isEnded(smart.getNodeId()));
    }

    @Test
    void presentTenseOnCurrentPrimeDealsDutyIsNotAnAutomaticTenseFailure() {
        ResumeDocumentNode p16 = requireNode("Design and implement", "Spring Boot", "MVC");
        assertNull(reject(p16, P16_ORIGINAL.replace(".", ""), OpportunityDetector.TENSE));
    }

    @Test
    void applicationsPluralWithoutEvidenceIsCardinalityChange() {
        ResumeDocumentNode p16 = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.FACT_CARDINALITY_CHANGED,
                reject(p16, P16_APPLICATIONS, OpportunityDetector.TENSE));
    }

    @Test
    void pastTenseDutyOnPresentEntryIsNotResolvedUnlessCompletedOutcome() {
        ResumeDocumentNode p16 = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED,
                reject(p16, P16_PAST, OpportunityDetector.TENSE));
        assertNull(reject(
                p16,
                "Successfully deployed a Java Spring Boot application using the MVC pattern.",
                OpportunityDetector.TENSE));
    }

    @Test
    void p14PresentLeadIsAllowedAndPastOnlyIsLowValue() {
        ResumeDocumentNode p14 = requireNode("Lead and collaborate");
        assertTrue(ledger.isOngoing(p14.getNodeId()), ledger.entryText(p14.getNodeId()));
        assertNull(reject(p14, p14.getText(), OpportunityDetector.CLARITY));
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                reject(p14, P14_PAST, OpportunityDetector.CLARITY));
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                reject(p14, "Teamed up with a friend to develop a shopping website", OpportunityDetector.CLARITY));
    }

    @Test
    void p17MustFixManagementsAndGoldOperationsPasses() {
        ResumeDocumentNode p17 = requireNode("Integrated RESTful APIs");
        assertEquals(
                RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED,
                reject(p17, P17_STILL_MANAGEMENTS, OpportunityDetector.GRAMMAR));
        assertNull(reject(p17, P17_GOLD, OpportunityDetector.GRAMMAR));
        assertNull(reject(
                p17,
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart management.",
                OpportunityDetector.GRAMMAR));
    }

    @Test
    void p25CommunicationSynonymPolishIsLowValue() {
        ResumeDocumentNode p25 = requireNode("Communicated effectively");
        String original = p25.getText();
        String polished = original.replace("effectively", "clearly")
                .replace("Effectively", "Clearly");
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                reject(p25, polished, OpportunityDetector.PROFESSIONAL_LANGUAGE));
    }

    @Test
    void endedEntryStillUsingPresentTenseDoesNotResolveTense() {
        ResumeDocumentNode smart = requireNode("Java Maven project");
        String present = "Develop a Java Maven project that helps users select optimal components within a given budget";
        assertEquals(
                RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED,
                reject(smart, present, OpportunityDetector.TENSE));
    }

    private String reject(ResumeDocumentNode node, String newText, String opportunity) {
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(node.getNodeId(), ledger);
        ConservativeRewritePlan plan = ConservativeCandidateHardGateTest.planFor(node, packet)
                .withRankingReason(opportunity);
        return OpportunityResolutionGate.rejectReason(node.getText(), newText, plan, packet, ledger);
    }

    private ResumeDocumentNode requireNode(String... needles) {
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
}
