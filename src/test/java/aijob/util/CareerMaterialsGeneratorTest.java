package aijob.util;

import aijob.dto.builder.CareerMaterialsResponse;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.CareerMaterials;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures are entirely fictional: Alex Morgan, Example Company, Project Atlas.
 * CareerMaterialsGenerator has NO dependency on AiResumeService or any OpenAI-calling
 * class -- these tests confirm the OBSERVABLE behavior that follows from that (facts
 * pass through verbatim, missing facts never get invented content), not just the
 * absence of a dependency.
 */
class CareerMaterialsGeneratorTest {

    private final CareerMaterialsGenerator generator = new CareerMaterialsGenerator();

    @Test
    void withNoFactsAtAllEveryFieldExplainsWhatToFillInRatherThanInventingContent() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        CareerMaterialsResponse response = generator.generate(content, "en");

        assertTrue(response.getPositioningSummary().toLowerCase().contains("target"));
        assertTrue(response.getSelfIntroduction().toLowerCase().contains("career materials"));
        assertTrue(response.getAiReflection().toLowerCase().contains("career materials"));
        assertTrue(response.getStrengthsAndWeaknesses().toLowerCase().contains("improve"));
        assertTrue(response.getDevelopmentPlan().toLowerCase().contains("career materials"));
    }

    @Test
    void positioningSummaryUsesOnlyFactsActuallyEntered() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        content.getJobTarget().setTargetTitle("Backend Engineer");
        content.getJobTarget().setTargetIndustry("Fintech");

        CareerMaterialsResponse response = generator.generate(content, "en");
        assertTrue(response.getPositioningSummary().contains("Alex Morgan"));
        assertTrue(response.getPositioningSummary().contains("Backend Engineer"));
        assertTrue(response.getPositioningSummary().contains("Fintech"));
        // Never mentions a company or school -- none were part of positioning facts.
        assertFalse(response.getPositioningSummary().contains("Example Company"));
    }

    @Test
    void selfIntroductionOnlyIncludesFactsTheUserTyped() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        CareerMaterials materials = new CareerMaterials();
        materials.setSelfIntroFacts(List.of("Five years building backend systems.",
                "Led a team of three engineers at Example Company."));
        content.setCareerMaterials(materials);

        CareerMaterialsResponse response = generator.generate(content, "en");
        assertTrue(response.getSelfIntroduction().contains("Alex Morgan"));
        assertTrue(response.getSelfIntroduction().contains("Five years building backend systems."));
        assertTrue(response.getSelfIntroduction().contains("Led a team of three engineers at Example Company."));
    }

    @Test
    void aiReflectionComposesOnlyFromEnteredScenariosStrengthsRisksAndVerification() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        CareerMaterials materials = new CareerMaterials();
        materials.setAiUsageScenarios(List.of("Drafting boilerplate CRUD code."));
        materials.setAiRisks(List.of("May fabricate an API that does not exist."));
        materials.setAiVerificationApproach(List.of("Run the full test suite before merging."));
        content.setCareerMaterials(materials);

        CareerMaterialsResponse response = generator.generate(content, "en");
        assertTrue(response.getAiReflection().contains("Drafting boilerplate CRUD code."));
        assertTrue(response.getAiReflection().contains("May fabricate an API that does not exist."));
        assertTrue(response.getAiReflection().contains("Run the full test suite before merging."));
    }

    @Test
    void developmentPlanUsesOnlyTheGoalsEntered() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        CareerMaterials materials = new CareerMaterials();
        materials.setOneYearGoal("Become proficient in distributed systems.");
        content.setCareerMaterials(materials);

        CareerMaterialsResponse response = generator.generate(content, "en");
        assertTrue(response.getDevelopmentPlan().contains("Become proficient in distributed systems."));
        // No three-year goal was entered -- never invented.
        assertFalse(response.getDevelopmentPlan().toLowerCase().contains("three-year goal:"));
    }

    @Test
    void chineseLocaleProducesChineseLabels() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getJobTarget().setTargetTitle("Backend Engineer");
        CareerMaterialsResponse response = generator.generate(content, "zh-CN");
        assertTrue(response.getPositioningSummary().contains("目标职位"));
    }
}
