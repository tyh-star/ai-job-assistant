package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ScoreBand;
import aijob.dto.TailorAnalysisDimensions;
import aijob.dto.TailorAnalysisRequirement;
import aijob.dto.TailorAnalysisSummary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic Tailor Analysis scoring. OpenAI never supplies overallScore.
 */
public final class TailorAnalysisScorer {

    public static final double HARD_SKILLS_WEIGHT = 0.30;
    public static final double RESPONSIBILITIES_WEIGHT = 0.25;
    public static final double QUALIFICATIONS_WEIGHT = 0.15;
    public static final double EVIDENCE_STRENGTH_WEIGHT = 0.20;
    public static final double ATS_WEIGHT = 0.10;

    private TailorAnalysisScorer() {
    }

    public static TailorAnalysisDimensions scoreDimensions(
            List<TailorAnalysisRequirement> requirements,
            List<ResumeDocumentNode> nodes) {

        TailorAnalysisDimensions dimensions = new TailorAnalysisDimensions();
        dimensions.setHardSkills(categoryScore(requirements, RequirementCategory.HARD_SKILL, RequirementCategory.DOMAIN));
        dimensions.setResponsibilities(categoryScore(requirements, RequirementCategory.RESPONSIBILITY));
        dimensions.setQualifications(categoryScore(requirements, RequirementCategory.QUALIFICATION));
        dimensions.setEvidenceStrength(evidenceStrength(requirements, nodes));
        dimensions.setAtsSearchability(atsSearchability(requirements, nodes));
        return dimensions;
    }

    public static int overallScore(TailorAnalysisDimensions dimensions) {
        if (dimensions == null) {
            return 0;
        }
        double numerator = 0;
        double denominator = 0;
        numerator += weighted(dimensions.getHardSkills(), HARD_SKILLS_WEIGHT);
        denominator += present(dimensions.getHardSkills(), HARD_SKILLS_WEIGHT);
        numerator += weighted(dimensions.getResponsibilities(), RESPONSIBILITIES_WEIGHT);
        denominator += present(dimensions.getResponsibilities(), RESPONSIBILITIES_WEIGHT);
        numerator += weighted(dimensions.getQualifications(), QUALIFICATIONS_WEIGHT);
        denominator += present(dimensions.getQualifications(), QUALIFICATIONS_WEIGHT);
        numerator += weighted(dimensions.getEvidenceStrength(), EVIDENCE_STRENGTH_WEIGHT);
        denominator += present(dimensions.getEvidenceStrength(), EVIDENCE_STRENGTH_WEIGHT);
        numerator += weighted(dimensions.getAtsSearchability(), ATS_WEIGHT);
        denominator += present(dimensions.getAtsSearchability(), ATS_WEIGHT);
        if (denominator == 0) {
            return 0;
        }
        return clamp((int) Math.round(numerator / denominator));
    }

    public static String scoreBand(int overallScore) {
        return ScoreBand.of(overallScore);
    }

    public static TailorAnalysisSummary summary(List<TailorAnalysisRequirement> requirements) {
        int supportedMust = 0;
        int partialMust = 0;
        int missingMust = 0;
        int totalMust = 0;
        int supportedPreferred = 0;
        int partialPreferred = 0;
        int missingPreferred = 0;
        int totalPreferred = 0;
        int missing = 0;
        if (requirements != null) {
            for (TailorAnalysisRequirement requirement : requirements) {
                if (requirement == null || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())) {
                    continue;
                }
                if (RequirementPriority.MUST_HAVE.equals(requirement.getPriority())) {
                    totalMust++;
                    if (CoverageStatus.SUPPORTED.equals(requirement.getStatus())) {
                        supportedMust++;
                    } else if (CoverageStatus.PARTIALLY_SUPPORTED.equals(requirement.getStatus())) {
                        partialMust++;
                    } else if (CoverageStatus.MISSING_EVIDENCE.equals(requirement.getStatus())) {
                        missingMust++;
                    }
                } else if (RequirementPriority.PREFERRED.equals(requirement.getPriority())) {
                    totalPreferred++;
                    if (CoverageStatus.SUPPORTED.equals(requirement.getStatus())) {
                        supportedPreferred++;
                    } else if (CoverageStatus.PARTIALLY_SUPPORTED.equals(requirement.getStatus())) {
                        partialPreferred++;
                    } else if (CoverageStatus.MISSING_EVIDENCE.equals(requirement.getStatus())) {
                        missingPreferred++;
                    }
                }
                if (CoverageStatus.MISSING_EVIDENCE.equals(requirement.getStatus())) {
                    missing++;
                }
            }
        }
        return new TailorAnalysisSummary(
                supportedMust,
                partialMust,
                missingMust,
                totalMust,
                supportedPreferred,
                partialPreferred,
                missingPreferred,
                totalPreferred,
                missing
        );
    }

    static Integer categoryScore(List<TailorAnalysisRequirement> requirements, String... categories) {
        double numerator = 0;
        int denominator = 0;
        if (requirements != null) {
            for (TailorAnalysisRequirement requirement : requirements) {
                if (requirement == null
                        || !matchesCategory(requirement.getCategory(), categories)
                        || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())) {
                    continue;
                }
                int priority = RequirementPriority.weight(requirement.getPriority());
                if (priority <= 0) {
                    continue;
                }
                numerator += priority * CoverageStatus.weight(requirement.getStatus());
                denominator += priority;
            }
        }
        if (denominator == 0) {
            return null;
        }
        return clamp((int) Math.round(100.0 * numerator / denominator));
    }

    private static Integer evidenceStrength(
            List<TailorAnalysisRequirement> requirements,
            List<ResumeDocumentNode> nodes) {

        double numerator = 0;
        int denominator = 0;
        if (requirements != null) {
            for (TailorAnalysisRequirement requirement : requirements) {
                if (requirement == null || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())) {
                    continue;
                }
                int priority = RequirementPriority.weight(requirement.getPriority());
                if (priority <= 0) {
                    continue;
                }
                double coverage = CoverageStatus.weight(requirement.getStatus());
                double support = 0;
                if (coverage > 0) {
                    String kind = requirement.getEvidenceKind();
                    if (kind == null) {
                        kind = ResumeRequirementMatcher.strongestEvidenceKind(requirement, nodes);
                    }
                    support = EvidenceKind.strength(kind);
                }
                numerator += priority * coverage * support;
                denominator += priority;
            }
        }
        if (denominator == 0) {
            return null;
        }
        return clamp((int) Math.round(100.0 * numerator / denominator));
    }

    private static Integer atsSearchability(
            List<TailorAnalysisRequirement> requirements,
            List<ResumeDocumentNode> nodes) {

        Set<String> terms = new LinkedHashSet<>();
        if (requirements != null) {
            for (TailorAnalysisRequirement requirement : requirements) {
                if (requirement == null
                        || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())
                        || RequirementPriority.OPTIONAL.equals(requirement.getPriority())
                        || requirement.getExactTerms() == null) {
                    continue;
                }
                for (String term : ExactTermPolicy.retainMeaningful(requirement.getExactTerms())) {
                    terms.add(EvidenceText.normalize(term));
                }
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        int found = 0;
        for (String term : terms) {
            boolean present = false;
            if (nodes != null) {
                for (ResumeDocumentNode node : nodes) {
                    if (node != null && ResumeRequirementMatcher.containsTerm(node.getText(), term)) {
                        present = true;
                        break;
                    }
                }
            }
            if (present) {
                found++;
            }
        }
        return clamp((int) Math.round(100.0 * found / terms.size()));
    }

    private static double weighted(Integer value, double weight) {
        return value == null ? 0 : value * weight;
    }

    private static double present(Integer value, double weight) {
        return value == null ? 0 : weight;
    }

    private static boolean matchesCategory(String category, String... categories) {
        for (String expected : categories) {
            if (expected.equals(category)) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
