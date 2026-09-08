package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorAnalysisDimensions;
import aijob.dto.TailorAnalysisRequirement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorAnalysisScorerTest {

    @Test
    void overallScoreIsWeightedAndClamped() {
        List<TailorAnalysisRequirement> requirements = List.of(
                req(RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, CoverageStatus.SUPPORTED,
                        EvidenceKind.EXPERIENCE_BULLET, List.of("Java")),
                req(RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE, CoverageStatus.PARTIALLY_SUPPORTED,
                        EvidenceKind.PROJECT_BULLET, List.of("REST API")),
                req(RequirementCategory.QUALIFICATION, RequirementPriority.PREFERRED, CoverageStatus.MISSING_EVIDENCE,
                        null, List.of())
        );
        TailorAnalysisDimensions dimensions = TailorAnalysisScorer.scoreDimensions(requirements, List.of());
        int overall = TailorAnalysisScorer.overallScore(dimensions);
        int expected = (int) Math.round(
                (0.30 * dimensions.getHardSkills()
                        + 0.25 * dimensions.getResponsibilities()
                        + 0.15 * dimensions.getQualifications()
                        + 0.20 * dimensions.getEvidenceStrength()
                        + 0.10 * dimensions.getAtsSearchability())
                        / 1.00
        );
        assertEquals(expected, overall);
        assertTrue(overall >= 0 && overall <= 100);
        assertEquals(100, dimensions.getHardSkills());
        assertEquals(50, dimensions.getResponsibilities());
        assertEquals(0, dimensions.getQualifications());
    }

    @Test
    void emptyQualificationIsNullAndExcludedFromOverallDenominator() {
        TailorAnalysisRequirement java = req(
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.SUPPORTED,
                EvidenceKind.PROJECT_BULLET,
                List.of("Java")
        );
        java.setEvidenceNodeIds(List.of("p-16"));
        List<ResumeDocumentNode> nodes = ResumeRequirementMatcherTest.alexMorganNodes();
        TailorAnalysisDimensions dimensions = TailorAnalysisScorer.scoreDimensions(List.of(java), nodes);

        assertEquals(100, dimensions.getHardSkills());
        assertNull(dimensions.getQualifications());
        assertNull(dimensions.getResponsibilities());
        assertEquals(90, dimensions.getEvidenceStrength());
        assertEquals(100, dimensions.getAtsSearchability());

        int overall = TailorAnalysisScorer.overallScore(dimensions);
        int expected = (int) Math.round(
                (100 * 0.30 + 90 * 0.20 + 100 * 0.10) / 0.60
        );
        assertEquals(expected, overall);
        assertEquals(97, overall);
    }

    @Test
    void inProgressEducationUsesHalfQualificationWeight() {
        TailorAnalysisRequirement cs = req(
                RequirementCategory.QUALIFICATION,
                RequirementPriority.PREFERRED,
                CoverageStatus.PARTIALLY_SUPPORTED,
                EvidenceKind.EDUCATION,
                List.of("Computer Science")
        );
        cs.setCoverageReason(QualificationMatcher.CURRENTLY_PURSUING);
        Integer qualifications = TailorAnalysisScorer.categoryScore(
                List.of(cs), RequirementCategory.QUALIFICATION);
        assertEquals(50, qualifications);
    }

    @Test
    void optionalCannotOutweighMustHave() {
        List<TailorAnalysisRequirement> mustMissing = new ArrayList<>();
        mustMissing.add(req(RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, CoverageStatus.MISSING_EVIDENCE,
                null, List.of("Kubernetes")));
        mustMissing.add(req(RequirementCategory.HARD_SKILL, RequirementPriority.OPTIONAL, CoverageStatus.SUPPORTED,
                EvidenceKind.SKILLS_ONLY, List.of("Java")));

        List<TailorAnalysisRequirement> mustSupported = new ArrayList<>();
        mustSupported.add(req(RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, CoverageStatus.SUPPORTED,
                EvidenceKind.SKILLS_ONLY, List.of("Java")));
        mustSupported.add(req(RequirementCategory.HARD_SKILL, RequirementPriority.OPTIONAL, CoverageStatus.MISSING_EVIDENCE,
                null, List.of("Kubernetes")));

        int missingMust = TailorAnalysisScorer.categoryScore(
                mustMissing, RequirementCategory.HARD_SKILL);
        int supportedMust = TailorAnalysisScorer.categoryScore(
                mustSupported, RequirementCategory.HARD_SKILL);

        assertEquals(25, missingMust);
        assertEquals(75, supportedMust);
        assertTrue(supportedMust > missingMust);
    }

    @Test
    void sameInputsProduceTheSameScore() {
        List<TailorAnalysisRequirement> requirements = List.of(
                req(RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE, CoverageStatus.SUPPORTED,
                        EvidenceKind.PROJECT_BULLET, List.of("Java"))
        );
        TailorAnalysisDimensions first = TailorAnalysisScorer.scoreDimensions(requirements, List.of());
        TailorAnalysisDimensions second = TailorAnalysisScorer.scoreDimensions(requirements, List.of());
        assertEquals(first.getHardSkills(), second.getHardSkills());
        assertEquals(TailorAnalysisScorer.overallScore(first), TailorAnalysisScorer.overallScore(second));
    }

    @Test
    void evidenceStrengthIsLowerWhenOnlySkillsSupportAMustHave() {
        TailorAnalysisRequirement skillsOnly = req(
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.SUPPORTED,
                EvidenceKind.SKILLS_ONLY,
                List.of("Java")
        );
        skillsOnly.setEvidenceNodeIds(List.of("p-11"));
        TailorAnalysisRequirement withProject = req(
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.SUPPORTED,
                EvidenceKind.PROJECT_BULLET,
                List.of("Spring Boot")
        );
        withProject.setEvidenceNodeIds(List.of("p-16"));

        List<ResumeDocumentNode> nodes = ResumeRequirementMatcherTest.alexMorganNodes();
        int skillsScore = TailorAnalysisScorer.scoreDimensions(List.of(skillsOnly), nodes).getEvidenceStrength();
        int projectScore = TailorAnalysisScorer.scoreDimensions(List.of(withProject), nodes).getEvidenceStrength();
        assertEquals(40, skillsScore);
        assertEquals(90, projectScore);
        assertTrue(projectScore > skillsScore);
    }

    @Test
    void designImplementAreExcludedFromAtsWhileSpringBootAndRestApiRemain() {
        TailorAnalysisRequirement requirement = req(
                RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.SUPPORTED,
                EvidenceKind.PROJECT_BULLET,
                List.of("design", "implement", "Spring Boot", "REST API")
        );
        List<ResumeDocumentNode> nodes = List.of(
                new ResumeDocumentNode("p-16", "PARAGRAPH",
                        "Used Spring Boot and REST APIs for checkout.")
        );
        Integer ats = TailorAnalysisScorer.scoreDimensions(List.of(requirement), nodes).getAtsSearchability();
        assertEquals(100, ats);
    }

    private TailorAnalysisRequirement req(
            String category,
            String priority,
            String status,
            String evidenceKind,
            List<String> terms) {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setCategory(category);
        requirement.setPriority(priority);
        requirement.setStatus(status);
        requirement.setEvidenceKind(evidenceKind);
        requirement.setRequirement(category);
        requirement.setExactTerms(terms);
        requirement.setEvidenceNodeIds(List.of());
        requirement.setEvidenceQuotes(List.of());
        return requirement;
    }
}
