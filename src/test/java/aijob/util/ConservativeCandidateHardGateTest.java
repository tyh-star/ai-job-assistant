package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import aijob.service.impl.ResumeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeCandidateHardGateTest {

    private static final String JD =
            "Junior Java Backend Developer using Spring Boot, Maven, and REST APIs.";
    static final String PRIME_GOLD =
            "Designed and implemented a Java Spring Boot application using the MVC pattern, integrating RESTful APIs for authentication, product updates, and shopping cart workflows.";
    static final String SMART_GOLD =
            "Developed a Java Maven application that recommends budget-aligned CPU, GPU, and RAM configurations using object-oriented design.";
    static final String P16_HOME =
            "Designed and implemented a Java Spring Boot application using the MVC architecture.";
    static final String P17_HOME =
            "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.";
    static final String P21_HOME =
            "Developed a Maven-based Java project to help users select optimal components within a specified budget.";

    @TempDir
    Path tempDir;

    private List<ResumeDocumentNode> nodes;
    private ClaimLedger ledger;

    @BeforeEach
    void parseRealCanonicalDocx() throws Exception {
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path docx = tempDir.resolve("alexmorgan-canonical.docx");
        GeneratedResumePdfFixture.writeCanonicalDocx(pdf, docx);
        nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        ledger = ClaimLedger.from(nodes);
    }

    @Test
    void primeDealsHomeRewritePassesHardGateAndLocalScore() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate candidate = candidate(spring.getNodeId(), P16_HOME, List.of());

        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, spring, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                spring, plan, packet, ledger, JD, P16_HOME, List.of(), gate.quality());
        assertTrue(score.admitted(), score.toString());
        assertTrue(score.total() >= ConservativeCandidateLocalScorer.ADMIT_TOTAL, String.valueOf(score.total()));
        assertTrue(score.improvementMargin() >= ConservativeCandidateLocalScorer.ADMIT_MARGIN);
    }

    @Test
    void primeDealsMergedRestFactsAreSourceDuplicates() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate candidate = candidate(
                spring.getNodeId(), PRIME_GOLD, List.of(rest.getNodeId()));
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, spring, plan, packet, ledger, JD);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
    }

    @Test
    void smartPcHomeRewritePassesHardGateAndLocalScore() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate candidate = candidate(maven.getNodeId(), P21_HOME, List.of());

        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, maven, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                maven, plan, packet, ledger, JD, P21_HOME, List.of(), gate.quality());
        assertTrue(score.admitted(), score.toString());
    }

    @Test
    void smartPcCopiedOopAndCpuAreSourceDuplicates() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode oop = requireNode("Object-Oriented Programming");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate candidate = candidate(
                maven.getNodeId(), SMART_GOLD, List.of(oop.getNodeId(), algorithm.getNodeId()));
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, maven, plan, packet, ledger, JD);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
    }

    @Test
    void directedTheCreationIsRoleSemanticEscalation() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION,
                reject(spring, "Directed the creation of a Java Spring Boot application using the MVC pattern."));
    }

    @Test
    void createdDroppingDesignImplementIsInformationLoss() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.INFORMATION_LOSS,
                reject(spring, "Created an application using the MVC architecture."));
    }

    @Test
    void inventedDockerKubernetesMetricsIsUnsupportedClaim() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented a Java Spring Boot application using Docker, Kubernetes, and 99.9% uptime metrics."));
    }

    @Test
    void primeDealsUsingSmartPcFactsIsCrossEntry() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate candidate = candidate(
                spring.getNodeId(),
                "Designed and implemented a Java Spring Boot application using object-oriented CPU and GPU selection.",
                List.of(algorithm.getNodeId()));
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, spring, plan, packet, ledger, JD);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED, gate.reasonCode());
    }

    @Test
    void smartPcUsingPrimeDealsRestAwsIsCrossEntry() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode aws = requireNode("Successfully deployed", "AWS");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate candidate = candidate(
                maven.getNodeId(),
                "Developed a Java Maven project integrating RESTful APIs and AWS.",
                List.of(rest.getNodeId(), aws.getNodeId()));
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, maven, plan, packet, ledger, JD);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED, gate.reasonCode());
    }

    @Test
    void leadFromSameEntryDoesNotAutoUpgradeUnlessCited() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode lead = requireNode("Lead and collaborate");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate uncited = candidate(
                spring.getNodeId(),
                "Led the design and implementation of a Java Spring Boot application using the MVC architecture.",
                List.of());
        assertEquals(
                RejectedTailorChange.ROLE_SEMANTIC_ESCALATION,
                ConservativeCandidateHardGate.evaluate(uncited, spring, plan, packet, ledger, JD).reasonCode());

        ConservativeRewriteCandidate cited = candidate(
                spring.getNodeId(),
                "Led the design and implementation of a Java Spring Boot application using the MVC architecture.",
                List.of(lead.getNodeId()));
        ConservativeCandidateHardGate.Result citedGate =
                ConservativeCandidateHardGate.evaluate(cited, spring, plan, packet, ledger, JD);
        assertTrue(
                citedGate.passed()
                        || RejectedTailorChange.LOW_VALUE_REWRITE.equals(citedGate.reasonCode()),
                citedGate.reasonCode());
        assertFalse(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(citedGate.reasonCode()));
    }

    @Test
    void emptySupportingIsNotEnrichmentIntent() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        String clarity = "Designed and implemented a Java Spring Boot application using the MVC architecture.";
        ConservativeRewriteCandidate candidate = candidate(spring.getNodeId(), clarity, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, spring, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        assertEquals(RewriteIntent.CLARITY_IMPROVEMENT, gate.classifiedIntent());
        assertTrue(gate.usedSupportingNodeIds().isEmpty(), gate.usedSupportingNodeIds().toString());
    }

    @Test
    void p21CopyingOopIsSourceDuplicateEvenIfProgrammingIsMentioned() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate candidate = candidate(
                maven.getNodeId(),
                "Developed a Java Maven project that helps users select optimal components within a budget, applying Object-Oriented Programming principles for modularity.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, maven, plan, packet, ledger, JD);
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
        assertFalse(gate.usedSupportingNodeIds().contains(algorithm.getNodeId()), gate.usedSupportingNodeIds().toString());
    }

    @Test
    void p21HomeDoesNotResolveRamSupportingNode() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate candidate = candidate(maven.getNodeId(), P21_HOME, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, maven, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        assertFalse(gate.usedSupportingNodeIds().contains(algorithm.getNodeId()), gate.usedSupportingNodeIds().toString());
        assertTrue(gate.usedSupportingNodeIds().isEmpty(), gate.usedSupportingNodeIds().toString());
    }

    @Test
    void algorithmHomeRewritePassesHardGateAndReservedPolicy() {
        ResumeDocumentNode algorithm = requireNode("Implemented an algorithm", "CPU");
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        String home = "Implemented a dynamic recommendation algorithm for CPU, GPU, and RAM components.";
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(algorithm.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(algorithm, packet);
        ConservativeRewriteCandidate candidate = candidate(algorithm.getNodeId(), home, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, algorithm, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode() + " required=" + plan.requiredFacts());
        assertFalse(home.toLowerCase(Locale.ROOT).contains("designed"));
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                algorithm, plan, packet, ledger, JD, home, gate.usedSupportingNodeIds(), gate.quality());
        assertFalse(
                RejectedTailorChange.UNSUPPORTED_ACTION.equals(score.rejectReason()),
                score.toString());
        assertNull(ConservativeReservedTargetPolicy.absorbReason(
                algorithm.getNodeId(),
                algorithm.getText(),
                home,
                Set.of(algorithm.getNodeId(), maven.getNodeId(), spring.getNodeId(), rest.getNodeId()),
                nodes));
    }

    @Test
    void umlCartRelationIsRejectedBeforeLocalScore() {
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode planning = requireNode("UML Class Diagram");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(rest.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(rest, packet);
        ConservativeRewriteCandidate candidate = candidate(
                rest.getNodeId(),
                "Integrated RESTful APIs for managing user authentication and product updates, "
                        + "using UML Class Diagram for planning cart functionalities.",
                List.of(planning.getNodeId()));
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, rest, plan, packet, ledger, JD);
        assertFalse(gate.passed(), gate.toString());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
    }

    @Test
    void restAuthProductCartHomeRewritePassesRelationGate() {
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(rest.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(rest, packet);
        ConservativeRewriteCandidate candidate = candidate(
                rest.getNodeId(),
                "Integrated RESTful APIs for user authentication, product updates, and shopping cart operations.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, rest, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        ConservativeCandidateLocalScorer.Score score = ConservativeCandidateLocalScorer.score(
                rest, plan, packet, ledger, JD, candidate.getNewText(),
                gate.usedSupportingNodeIds(), gate.quality());
        assertTrue(score.admitted() || RewriteIntent.GRAMMAR_FIX.equals(score.classifiedIntent())
                        || RewriteIntent.CLARITY_IMPROVEMENT.equals(score.classifiedIntent()),
                score.toString());
    }

    @Test
    void sameNodeHandleToManagingPassesHardGate() {
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(rest.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(rest, packet);
        ConservativeRewriteCandidate candidate = candidate(
                rest.getNodeId(),
                "Integrated RESTful APIs for managing user authentication, product updates, and shopping cart operations.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, rest, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
    }

    @Test
    void p21SynonymOnlyIsLowValueRewrite() {
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        assertEquals(
                RejectedTailorChange.LOW_VALUE_REWRITE,
                reject(maven,
                        "Developed a Java Maven project aiding users in selecting the best components within a specified budget."));
    }

    @Test
    void genericProjectWordDoesNotResolvePlanningSupportingNode() {
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ResumeDocumentNode planning = requireNode("UML Class Diagram");
        ResumeDocumentNode aws = requireNode("Successfully deployed", "AWS");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(rest.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(rest, packet);
        ConservativeRewriteCandidate candidate = candidate(
                rest.getNodeId(),
                "Integrated RESTful APIs to handle user authentication, product updates, and shopping cart management, successfully deploying the project on AWS.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, rest, plan, packet, ledger, JD);
        assertFalse(gate.passed());
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, gate.reasonCode());
        assertFalse(gate.usedSupportingNodeIds().contains(planning.getNodeId()), gate.usedSupportingNodeIds().toString());
    }

    @Test
    void goldPrimeDealsHomeIsClarityNotCopiedRest() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate candidate = candidate(spring.getNodeId(), P16_HOME, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, spring, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        assertFalse(gate.usedSupportingNodeIds().contains(rest.getNodeId()), gate.usedSupportingNodeIds().toString());
        assertTrue(RewriteIntent.CLARITY_IMPROVEMENT.equals(gate.classifiedIntent())
                || RewriteIntent.GRAMMAR_FIX.equals(gate.classifiedIntent()), gate.classifiedIntent());
    }

    @Test
    void unsupportedOutcomesAreRejectedWithoutTreatingOptimalAsAMetric() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented a Java Spring Boot application using the MVC pattern for enhanced system efficiency."));
        ResumeDocumentNode maven = requireNode("Developed a Java Maven project");
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(maven,
                        "Developed a Java Maven project optimizing component selection for performance."));
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(maven.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(maven, packet);
        ConservativeRewriteCandidate keepOptimal = candidate(
                maven.getNodeId(),
                "Developed a Java Maven project that helps users select optimal components within a given budget.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                keepOptimal, maven, plan, packet, ledger, JD);
        assertFalse(RejectedTailorChange.UNSUPPORTED_CLAIM.equals(gate.reasonCode()), gate.reasonCode());
    }

    @Test
    void purposeResultOrConditionPhrasesNeedPacketEvidence() {
        ResumeDocumentNode spring = requireNode("Design and implement", "Spring Boot", "MVC");
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented a Java Spring Boot application using the MVC architecture based on user needs."));
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented a Java Spring Boot application using the MVC architecture to meet business needs."));
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented a Java Spring Boot application using the MVC architecture, ensuring improved outcomes."));
        assertEquals(
                RejectedTailorChange.UNSUPPORTED_CLAIM,
                reject(spring,
                        "Designed and implemented an application leveraging Java Spring Boot with an MVC pattern for structured architecture."));
        String clarity = "Designed and implemented a Java Spring Boot application using the MVC architecture.";
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(spring.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(spring, packet);
        ConservativeRewriteCandidate grounded = candidate(spring.getNodeId(), clarity, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                grounded, spring, plan, packet, ledger, JD);
        assertTrue(gate.passed(), gate.reasonCode());
        assertFalse(ConservativeCandidateHardGate.inventsUnsupportedContext(
                spring.getText(), clarity, packet, ledger));
    }

    @Test
    void manageShoppingCartIsNotRoleEscalation() {
        ResumeDocumentNode rest = requireNode("Integrated RESTful APIs");
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(rest.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(rest, packet);
        ConservativeRewriteCandidate candidate = candidate(
                rest.getNodeId(),
                "Integrated RESTful APIs to manage user authentication, product updates, and shopping cart operations.",
                List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, rest, plan, packet, ledger, JD);
        assertFalse(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(gate.reasonCode()), gate.reasonCode());
    }

    private String reject(ResumeDocumentNode node, String newText) {
        ConservativeEvidencePacket packet = ConservativeEvidencePacket.build(node.getNodeId(), ledger);
        ConservativeRewritePlan plan = planFor(node, packet);
        ConservativeRewriteCandidate candidate = candidate(node.getNodeId(), newText, List.of());
        ConservativeCandidateHardGate.Result gate = ConservativeCandidateHardGate.evaluate(
                candidate, node, plan, packet, ledger, JD);
        assertFalse(gate.passed(), newText);
        return gate.reasonCode();
    }

    static ConservativeRewritePlan planFor(ResumeDocumentNode node, ConservativeEvidencePacket packet) {
        return new ConservativeRewritePlan(
                node.getNodeId(),
                List.of("java", "spring", "boot", "maven", "rest"),
                "Rewrite this bullet",
                packet.maxCharacters(),
                "BULLET"
        ).withPacket(packet);
    }

    static ConservativeRewriteCandidate candidate(String nodeId, String newText, List<String> supporting) {
        ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
        candidate.setNodeId(nodeId);
        candidate.setNewText(newText);
        candidate.setReason("Same-entry rewrite for the job description.");
        candidate.setUsedSupportingNodeIds(supporting);
        candidate.setUsedFactNodeIds(supporting);
        candidate.setImprovementIntent("SAME_ENTRY_EVIDENCE_ENRICHMENT");
        candidate.setClaimedFacts(List.of("model-claimed-docker"));
        return candidate;
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
