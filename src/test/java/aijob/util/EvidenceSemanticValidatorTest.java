package aijob.util;

import aijob.dto.AnswerStatus;
import aijob.dto.EntryOption;
import aijob.dto.EvidenceReasonCode;
import aijob.dto.EvidenceValidationStatus;
import aijob.dto.RequirementCategory;
import aijob.dto.ResumeDocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSemanticValidatorTest {

    @Test
    void debugMvcArchitectureIsTopicMismatch() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "debug",
                RequirementCategory.RESPONSIBILITY,
                "Debug, test and document backend applications",
                "Designed Spring Boot application using MVC architecture"
        ));
        assertFalse(result.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH, result.reasonCode());
        assertEquals(EvidenceValidationStatus.NEEDS_REVIEW, result.validationStatus());
    }

    @Test
    void debugFixedAuthenticationBugIsValid() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "debug",
                RequirementCategory.RESPONSIBILITY,
                "Debug backend applications",
                "Fixed an authentication bug in Spring Boot"
        ));
        assertTrue(result.valid());
        assertTrue(result.missingActions().isEmpty());
    }

    @Test
    void dockerMvcLayersIsRejected() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "Docker",
                RequirementCategory.HARD_SKILL,
                "Docker",
                "Designed MVC layers"
        ));
        assertFalse(result.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH, result.reasonCode());
    }

    @Test
    void dockerContainerizedIsValid() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "Docker",
                RequirementCategory.HARD_SKILL,
                "Docker",
                "Containerized Spring Boot app with Docker"
        ));
        assertTrue(result.valid());
    }

    @Test
    void enhancedMetricCatalogTopicDoesNotRequireTheWordMetric() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "metric",
                RequirementCategory.HARD_SKILL,
                "metric",
                "handled about 2000 daily checkout users after launch"
        ));
        assertTrue(result.valid());
    }

    @Test
    void englishMetricAskableCopyStillMismatches() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "A confirmable performance or volume figure",
                RequirementCategory.HARD_SKILL,
                "A confirmable performance or volume figure",
                "handled about 2000 daily checkout users after launch"
        ));
        assertFalse(result.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH, result.reasonCode());
    }

    @Test
    void mysqlJavaOnlyIsRejected() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "MySQL",
                RequirementCategory.HARD_SKILL,
                "MySQL",
                "Implemented Java services for the store"
        ));
        assertFalse(result.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH, result.reasonCode());
    }

    @Test
    void mysqlQueryPersistenceIsValid() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "MySQL",
                RequirementCategory.HARD_SKILL,
                "MySQL",
                "Wrote SQL queries and a persistence layer against MySQL"
        ));
        assertTrue(result.valid());
    }

    @Test
    void deployOnlyDoesNotCoverMaintain() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "Deploy and maintain applications on AWS",
                RequirementCategory.RESPONSIBILITY,
                "Deploy and maintain applications on AWS",
                "Deployed the application to AWS"
        ));
        assertTrue(result.valid());
        assertTrue(result.missingActions().contains("maintain"));
        assertFalse(result.missingActions().contains("deploy"));
    }

    @Test
    void agileKnowledgeDoesNotProveCollaborate() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(input(
                "Collaborate through an Agile development process",
                RequirementCategory.RESPONSIBILITY,
                "Collaborate through an Agile development process",
                "I understand Agile and have knowledge of Scrum"
        ));
        assertFalse(result.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_ACTION_MISSING, result.reasonCode());
    }

    @Test
    void generalWithoutNodesStillRequiresTopicSemantics() {
        EvidenceSemanticValidator.Result ok = EvidenceSemanticValidator.validate(new EvidenceSemanticValidator.Input(
                "Docker",
                RequirementCategory.HARD_SKILL,
                "Docker",
                AnswerStatus.CONFIRMED_USED,
                "Side project",
                "Containerized the API with Docker",
                "Local deploys were repeatable",
                null,
                ResumeEntryCatalog.GENERAL,
                List.of(),
                ResumeEntryCatalog.generalOption(),
                ClaimLedger.from(ResumeRequirementMatcherTest.yaohangNodes()),
                ResumeAnalysisInputFactory.matchingNodeSources(ResumeRequirementMatcherTest.yaohangNodes())
        ));
        assertTrue(ok.valid());
        EvidenceSemanticValidator.Result bad = EvidenceSemanticValidator.validate(new EvidenceSemanticValidator.Input(
                "Docker",
                RequirementCategory.HARD_SKILL,
                "Docker",
                AnswerStatus.CONFIRMED_USED,
                null,
                "Designed MVC layers",
                null,
                null,
                ResumeEntryCatalog.GENERAL,
                List.of(),
                ResumeEntryCatalog.generalOption(),
                ClaimLedger.from(ResumeRequirementMatcherTest.yaohangNodes()),
                ResumeAnalysisInputFactory.matchingNodeSources(ResumeRequirementMatcherTest.yaohangNodes())
        ));
        assertFalse(bad.valid());
        assertEquals(EvidenceReasonCode.EVIDENCE_TOPIC_MISMATCH, bad.reasonCode());
    }

    @Test
    void crossEntryNodesAreRejected() {
        List<ResumeDocumentNode> nodes = ResumeRequirementMatcherTest.yaohangNodes();
        ClaimLedger ledger = ClaimLedger.from(nodes);
        Map<String, AnalysisEvidenceSource> registry = ResumeAnalysisInputFactory.matchingNodeSources(nodes);
        EntryOption prime = ResumeEntryCatalog.options(nodes, null).stream()
                .filter(option -> "PROJECT:prime-deals".equals(option.getEntryKey()))
                .findFirst()
                .orElseThrow();
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateNodes(
                new EvidenceSemanticValidator.Input(
                        "Docker", RequirementCategory.HARD_SKILL, "Docker", AnswerStatus.CONFIRMED_USED,
                        "x", "Containerized with Docker", "y", null,
                        "PROJECT:prime-deals", List.of("p-16", "p-19"), prime, ledger, registry
                )
        );
        assertEquals(EvidenceReasonCode.EVIDENCE_CROSS_ENTRY, result.reasonCode());
    }

    @Test
    void unknownPrefixedNodesAreRejected() {
        List<ResumeDocumentNode> nodes = ResumeRequirementMatcherTest.yaohangNodes();
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateNodes(
                new EvidenceSemanticValidator.Input(
                        "Docker", RequirementCategory.HARD_SKILL, "Docker", AnswerStatus.CONFIRMED_USED,
                        "x", "Containerized with Docker", "y", null,
                        ResumeEntryCatalog.GENERAL, List.of("p-999", "b-p-not-real", "t-99"),
                        ResumeEntryCatalog.generalOption(),
                        ClaimLedger.from(nodes),
                        ResumeAnalysisInputFactory.matchingNodeSources(nodes)
                )
        );
        assertEquals(EvidenceReasonCode.EVIDENCE_NODE_NOT_FOUND, result.reasonCode());
    }

    @Test
    void usedItIsTooVague() {
        EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateAnswer(
                new EvidenceSemanticValidator.Input(
                        "Docker", RequirementCategory.HARD_SKILL, "Docker", AnswerStatus.CONFIRMED_USED,
                        null, "used it", null, null,
                        ResumeEntryCatalog.GENERAL, List.of(), ResumeEntryCatalog.generalOption(),
                        null, Map.of()
                )
        );
        assertEquals(EvidenceReasonCode.EVIDENCE_TOO_VAGUE, result.reasonCode());
    }

    private static EvidenceSemanticValidator.Input input(
            String topic, String category, String requirement, String action) {
        return new EvidenceSemanticValidator.Input(
                topic, category, requirement, AnswerStatus.CONFIRMED_USED,
                "Prime Deals", action, "Shipped internally", null,
                ResumeEntryCatalog.GENERAL, List.of(), ResumeEntryCatalog.generalOption(),
                null, Map.of()
        );
    }
}
