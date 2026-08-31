package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.TailorAnalysisExplanation;
import aijob.dto.TailorAnalysisRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorAnalysisExplainerTest {

    @Test
    void explanationIsBuiltOnlyFromExistingRequirements() {
        TailorAnalysisRequirement java = req("Java", CoverageStatus.SUPPORTED, EvidenceKind.PROJECT_BULLET);
        TailorAnalysisRequirement docker = req("Docker", CoverageStatus.MISSING_EVIDENCE, null);
        TailorAnalysisRequirement kubernetes = req("Kubernetes", CoverageStatus.MISSING_EVIDENCE, null);

        TailorAnalysisExplanation explanation = TailorAnalysisExplainer.explain(List.of(java, docker, kubernetes));

        String strengths = String.join(" ", explanation.getStrengths());
        String gaps = String.join(" ", explanation.getGaps());
        String actions = String.join(" ", explanation.getNextActions());
        assertTrue(strengths.contains("Java"));
        assertTrue(strengths.contains("project evidence"));
        assertTrue(gaps.contains("Docker"));
        assertTrue(gaps.contains("Kubernetes"));
        assertTrue(actions.contains("Docker"));
        assertFalse(strengths.contains("Terraform"));
        assertFalse(gaps.contains("Terraform"));
        assertFalse(actions.contains("Terraform"));
        assertFalse(actions.contains("AWS"));
    }

    @Test
    void inProgressEducationUsesCurrentlyPursuingNotGraduation() {
        TailorAnalysisRequirement cs = req("Computer Science", CoverageStatus.PARTIALLY_SUPPORTED, EvidenceKind.EDUCATION);
        cs.setCategory(RequirementCategory.QUALIFICATION);
        cs.setCoverageReason(QualificationMatcher.CURRENTLY_PURSUING);
        TailorAnalysisExplanation explanation = TailorAnalysisExplainer.explain(List.of(cs));
        String strengths = String.join(" ", explanation.getStrengths());
        assertTrue(strengths.contains("currently pursuing"));
        assertFalse(strengths.toLowerCase().contains("graduat"));
        assertFalse(strengths.toLowerCase().contains("completed a degree"));
    }

    private TailorAnalysisRequirement req(String name, String status, String kind) {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setRequirement(name);
        requirement.setExactTerms(List.of(name));
        requirement.setStatus(status);
        requirement.setEvidenceKind(kind);
        requirement.setPriority(RequirementPriority.MUST_HAVE);
        requirement.setCategory(RequirementCategory.HARD_SKILL);
        return requirement;
    }
}
