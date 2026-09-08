package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorAnalysisRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsibilityEvidenceMatcherTest {

    @Test
    void deployAndMaintainExtractsActionsAndAws() {
        ResponsibilityConcept concept = ResponsibilityConceptExtractor.extract(
                "Deploy and maintain applications on AWS",
                List.of("deploy", "maintain", "AWS")
        );
        assertEquals(List.of("deploy", "maintain"), concept.requiredActions());
        assertTrue(concept.requiredTechnicalConcepts().isEmpty());
        assertTrue(concept.requiredObjectOrDomain().contains(ObjectDomainRegistry.DEPLOYMENT));
    }

    @Test
    void backendServicesExtractsBackendDomainNotIgnoredText() {
        ResponsibilityConcept concept = ResponsibilityConceptExtractor.extract(
                "Design and implement backend services",
                List.of("Design", "implement")
        );
        assertEquals(List.of("design", "implement"), concept.requiredActions());
        assertTrue(concept.requiredObjectOrDomain().contains(ObjectDomainRegistry.BACKEND));
        assertFalse(concept.requiredTechnicalConcepts().stream().anyMatch(term ->
                term.toLowerCase().contains("backend")));
    }

    @Test
    void vueLandingPageDoesNotSupportBackendServices() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Marketing Site | 2024"),
                        node("b-1", "Designed and implemented a Vue landing page")
                )
        );
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, result.getStatus());
    }

    @Test
    void genericServiceWithoutDomainIsPartialNotSupported() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Internal Tool | 2024"),
                        node("b-1", "Designed and implemented a service for internal users.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
    }

    @Test
    void restKeywordsWithoutActionsAreMissingForBackendDesign() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Billing Platform | 2023"),
                        node("b-1", "RESTful APIs and Spring Boot controllers.")
                )
        );
        assertEquals(CoverageStatus.MISSING_EVIDENCE, result.getStatus());
        assertTrue(result.getEvidenceNodeIds().isEmpty());
    }

    @Test
    void springBootAndRestInSameEntrySupportsBackendServices() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Store App | 2024"),
                        node("b-1", "Designed and implemented a Spring Boot application using MVC"),
                        node("b-2", "Built REST APIs with a controller and data-access layer")
                )
        );
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertTrue(result.getEvidenceNodeIds().contains("b-1"));
        assertTrue(result.getEvidenceNodeIds().contains("b-2"));
        assertFalse(result.getEvidenceNodeIds().contains("h-2"));
    }

    @Test
    void skillsSpringBootCannotCompleteFrontendProject() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "TECHNICAL SKILLS"),
                        node("s-1", "Java, Spring Boot, SQL"),
                        node("h-2", "RELEVANT PROJECTS"),
                        node("h-3", "Marketing Site | 2024"),
                        node("b-1", "Designed and implemented a Vue landing page")
                )
        );
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(CoverageStatus.MISSING_EVIDENCE, result.getStatus());
        assertFalse(result.getEvidenceNodeIds().contains("s-1"));
        assertTrue(result.getEvidenceNodeIds().isEmpty());
    }

    @Test
    void designImplementAndRestAcrossProjectsIsPartial() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Catalog Service | 2024"),
                        node("b-1", "Designed and implemented the application."),
                        node("h-3", "Billing Platform | 2023"),
                        node("b-2", "Integrated RESTful APIs for checkout.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
    }

    @Test
    void maintainedUiAndDeployedBackendAcrossEntriesIsPartial() {
        TailorAnalysisRequirement result = match(
                responsibility("Deploy and maintain applications on AWS", List.of("AWS")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Design System | 2024"),
                        node("b-1", "Maintained UI components for the design system."),
                        node("h-3", "Catalog Service | 2023"),
                        node("b-2", "Deployed backend to AWS.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
    }

    @Test
    void optimizeBackendPerformanceInSummaryIsAtMostPartial() {
        TailorAnalysisRequirement result = match(
                responsibility("Optimize backend application performance", List.of("optimize")),
                List.of(
                        node("h-1", "SUMMARY"),
                        node("s-1", "Optimized program functionalities across releases."),
                        node("h-2", "RELEVANT PROJECTS"),
                        node("h-3", "Store App | 2024"),
                        node("b-1", "Designed and implemented a Vue landing page")
                )
        );
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
    }

    @Test
    void alexmorganPrimeDealsBackendDomainCanSupportDesignAndImplement() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                ResumeRequirementMatcherTest.alexMorganNodes()
        );
        System.out.println("S2.1 Prime Deals evidenceNodeIds=" + result.getEvidenceNodeIds());
        System.out.println("S2.1 Prime Deals evidenceQuotes=" + result.getEvidenceQuotes());
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertTrue(result.getEvidenceNodeIds().contains("p-16"));
        assertTrue(result.getEvidenceNodeIds().contains("p-17"));
        assertFalse(result.getEvidenceNodeIds().contains("p-11"));
        assertTrue(result.getEvidenceQuotes().stream().anyMatch(quote -> quote.contains("Spring Boot")));
        assertTrue(result.getEvidenceQuotes().stream().anyMatch(quote ->
                quote.contains("RESTful APIs") || quote.contains("MVC")));
        assertTrue(ClaimLedger.from(ResumeRequirementMatcherTest.alexMorganNodes()).sameEntry("p-16", "p-17"));
        assertFalse(ClaimLedger.from(ResumeRequirementMatcherTest.alexMorganNodes()).sameEntry("p-16", "p-11"));
    }

    @Test
    void supportedEvidenceNodeIdsStayInsideTheSameEntryAndNeverUseSkills() {
        TailorAnalysisRequirement result = match(
                responsibility("Design and implement backend services", List.of("Design", "implement")),
                ResumeRequirementMatcherTest.alexMorganNodes()
        );
        ClaimLedger ledger = ClaimLedger.from(ResumeRequirementMatcherTest.alexMorganNodes());
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertFalse(result.getEvidenceNodeIds().isEmpty());
        for (String nodeId : result.getEvidenceNodeIds()) {
            assertTrue(ledger.sameEntry("p-16", nodeId), nodeId);
            assertNotEquals("p-11", nodeId);
        }
        assertFalse(result.getEvidenceNodeIds().contains("p-11"));
        assertFalse(result.getEvidenceQuotes().stream().anyMatch(quote ->
                quote != null && quote.contains("Vue")));
    }

    @Test
    void mvcBulletDoesNotSupportDebug() {
        TailorAnalysisRequirement result = match(
                responsibility("Debug, test and document backend applications", List.of("debug", "test", "document")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Store App | 2024"),
                        node("b-1", "Design and implement the application using Java Spring Boot with an MVC pattern.")
                )
        );
        assertEquals(CoverageStatus.MISSING_EVIDENCE, result.getStatus());
        assertTrue(result.getEvidenceNodeIds().isEmpty());
    }

    @Test
    void deployedToAwsOnlyIsPartialForDeployAndMaintain() {
        TailorAnalysisRequirement result = match(
                responsibility("Deploy and maintain applications on AWS", List.of("AWS")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Catalog Service | 2024"),
                        node("b-1", "Deployed application to AWS")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
        assertTrue(result.getEvidenceQuotes().get(0).toLowerCase().contains("deployed"));
        assertFalse(ActionConceptRegistry.contains(result.getEvidenceQuotes().get(0), "maintain"));
    }

    @Test
    void deployedAndMaintainedInSameProjectIsSupported() {
        TailorAnalysisRequirement result = match(
                responsibility("Deploy and maintain applications on AWS", List.of("AWS")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Catalog Service | 2024"),
                        node("b-1", "Successfully deployed the service onto AWS."),
                        node("b-2", "Maintained production workloads and patched runtime issues.")
                )
        );
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertTrue(result.getEvidenceNodeIds().contains("b-1"));
        assertTrue(result.getEvidenceNodeIds().contains("b-2"));
        assertFalse(result.getEvidenceNodeIds().contains("h-2"));
    }

    @Test
    void deployAndMaintainAcrossProjectsIsPartialNotSupported() {
        TailorAnalysisRequirement result = match(
                responsibility("Deploy and maintain applications on AWS", List.of("AWS")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Catalog Service | 2024"),
                        node("b-1", "Successfully deployed the service onto AWS."),
                        node("h-3", "Billing Platform | 2023"),
                        node("b-2", "Maintained production workloads and patched runtime issues.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
    }

    @Test
    void understandingOfAgileIsPartialForCollaborationResponsibility() {
        TailorAnalysisRequirement result = match(
                responsibility("Collaborate through an Agile development process", List.of("Agile")),
                List.of(
                        node("h-1", "TECHNICAL SKILLS"),
                        node("s-1", "Java, SQL, Understanding of Agile"),
                        node("h-2", "RELEVANT PROJECTS"),
                        node("h-3", "Store App | 2024"),
                        node("b-1", "Design and implement the application using Java Spring Boot with an MVC pattern.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertEquals(EvidenceKind.SKILLS_ONLY, result.getEvidenceKind());
    }

    @Test
    void collaboratedInAgileProcessIsSupported() {
        TailorAnalysisRequirement result = match(
                responsibility("Collaborate through an Agile development process", List.of("Agile")),
                List.of(
                        node("h-1", "EXPERIENCE"),
                        node("h-2", "Backend Intern | Acme | Jan 2024 – Present"),
                        node("b-1", "Collaborated with a team through an Agile development process.")
                )
        );
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
        assertTrue(result.getEvidenceQuotes().get(0).contains("Collaborated"));
    }

    @Test
    void documentedAndTestedCodeSupportsWriteCleanDocumentedTestedCode() {
        TailorAnalysisRequirement result = match(
                responsibility(
                        "Write clean, documented and tested code",
                        List.of("code")
                ),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Component Advisor | 2023"),
                        node("b-1", "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality")
                )
        );
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
    }

    @Test
    void optimizingInSummaryIsAtMostPartial() {
        TailorAnalysisRequirement result = match(
                responsibility("Optimize application performance", List.of("optimize")),
                List.of(
                        node("h-1", "SUMMARY"),
                        node("s-1", "Exceptional analytical and problem-solving skills with continuous thrive in optimizing program functionalities"),
                        node("h-2", "RELEVANT PROJECTS"),
                        node("h-3", "Store App | 2024"),
                        node("b-1", "Design and implement the application using Java Spring Boot with an MVC pattern.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertNotEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(EvidenceKind.SUMMARY, result.getEvidenceKind());
        assertEquals(List.of("s-1"), result.getEvidenceNodeIds());
    }

    @Test
    void integratedRestApisIsPartialWhenBuildIsAbsent() {
        TailorAnalysisRequirement result = match(
                responsibility("Build and integrate RESTful APIs", List.of("REST")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Store App | 2024"),
                        node("b-1", "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
    }

    @Test
    void mvcPatternRemainsValidAliasForMvcArchitectureResponsibility() {
        TailorAnalysisRequirement result = match(
                responsibility("Implement features using MVC architecture", List.of("MVC architecture")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Store App | 2024"),
                        node("b-1", "Design and implement the application using Java Spring Boot with an MVC pattern.")
                )
        );
        assertEquals(CoverageStatus.SUPPORTED, result.getStatus());
        assertEquals(EvidenceKind.PROJECT_BULLET, result.getEvidenceKind());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
        assertTrue(result.getEvidenceQuotes().get(0).contains("MVC pattern"));
    }

    @Test
    void unknownAliasCannotSupportAResponsibility() {
        TailorAnalysisRequirement result = match(
                responsibility("Debug, test and document backend applications", List.of("debug")),
                List.of(
                        node("h-1", "RELEVANT PROJECTS"),
                        node("h-2", "Store App | 2024"),
                        node("b-1", "Design and implement the application using Java Spring Boot with an MVC pattern.")
                )
        );
        assertEquals(CoverageStatus.MISSING_EVIDENCE, result.getStatus());
    }

    @Test
    void evidenceNodeIsActionMatchNotUnrelatedSkillsNode() {
        TailorAnalysisRequirement result = match(
                responsibility("Deploy and maintain applications on AWS", List.of("AWS")),
                List.of(
                        node("h-1", "TECHNICAL SKILLS"),
                        node("s-1", "AWS, Docker, Kubernetes"),
                        node("h-2", "RELEVANT PROJECTS"),
                        node("h-3", "Catalog Service | 2024"),
                        node("b-1", "Successfully deployed the service onto AWS.")
                )
        );
        assertEquals(CoverageStatus.PARTIALLY_SUPPORTED, result.getStatus());
        assertEquals(List.of("b-1"), result.getEvidenceNodeIds());
        assertFalse(result.getEvidenceNodeIds().contains("s-1"));
        assertTrue(result.getEvidenceQuotes().get(0).toLowerCase().contains("deployed"));
    }

    @Test
    void agileKnowledgeAndCollaborationKeepDistinctIds() {
        List<JobRequirementDraft> drafts = RequirementAtomizer.atomize(List.of(
                draft(
                        "Knowledge of Agile development",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED,
                        List.of("Agile")
                ),
                draft(
                        "Collaborate through an Agile development process",
                        RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE,
                        List.of("Agile")
                )
        ));
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(
                drafts,
                List.of(
                        node("h-1", "TECHNICAL SKILLS"),
                        node("s-1", "Understanding of Agile"),
                        node("h-2", "EXPERIENCE"),
                        node("h-3", "Backend Intern | Acme | Jan 2024 – Present"),
                        node("b-1", "Collaborated with a team through an Agile development process.")
                )
        );
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).getId(), results.get(1).getId());
        assertEquals(CoverageStatus.SUPPORTED, results.get(0).getStatus());
        assertEquals(CoverageStatus.SUPPORTED, results.get(1).getStatus());
        assertEquals(RequirementCategory.HARD_SKILL, results.get(0).getCategory());
        assertEquals(RequirementCategory.RESPONSIBILITY, results.get(1).getCategory());
    }

    @Test
    void latestDoesNotCountAsTestAndTestimonyDoesNotCountAsTest() {
        assertFalse(ActionConceptRegistry.contains("Shipped the latest release notes.", "test"));
        assertFalse(ActionConceptRegistry.contains("Provided expert testimony.", "test"));
        assertTrue(ActionConceptRegistry.contains("Wrote thoroughly tested code.", "test"));
        assertTrue(ActionConceptRegistry.contains("Successfully deployed onto AWS.", "deploy"));
        assertFalse(ActionConceptRegistry.contains("Implemented an MVC pattern.", "debug"));
    }

    private static TailorAnalysisRequirement match(JobRequirementDraft draft, List<ResumeDocumentNode> nodes) {
        List<TailorAnalysisRequirement> results = ResumeRequirementMatcher.match(List.of(draft), nodes);
        assertEquals(1, results.size());
        assertNotNull(results.get(0));
        return results.get(0);
    }

    private static JobRequirementDraft responsibility(String requirement, List<String> terms) {
        return draft(requirement, RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE, terms);
    }

    private static JobRequirementDraft draft(
            String requirement,
            String category,
            String priority,
            List<String> terms) {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement(requirement);
        draft.setCategory(category);
        draft.setPriority(priority);
        draft.setExactTerms(terms);
        return draft;
    }

    private static ResumeDocumentNode node(String id, String text) {
        return new ResumeDocumentNode(id, "PARAGRAPH", text);
    }
}
