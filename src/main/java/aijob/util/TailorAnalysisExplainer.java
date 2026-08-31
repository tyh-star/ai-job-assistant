package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.EvidenceQuestion;
import aijob.dto.TailorAnalysisExplanation;
import aijob.dto.TailorAnalysisRequirement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic score explanation from already-matched requirements. No OpenAI.
 */
public final class TailorAnalysisExplainer {

    private TailorAnalysisExplainer() {
    }

    public static TailorAnalysisExplanation explain(List<TailorAnalysisRequirement> requirements) {
        return explain(requirements, List.of());
    }

    public static TailorAnalysisExplanation explain(
            List<TailorAnalysisRequirement> requirements,
            List<EvidenceQuestion> questions) {

        TailorAnalysisExplanation explanation = new TailorAnalysisExplanation();
        explanation.setStrengths(strengths(requirements));
        explanation.setGaps(gaps(requirements));
        explanation.setNextActions(nextActions(requirements, questions));
        return explanation;
    }

    private static List<String> strengths(List<TailorAnalysisRequirement> requirements) {
        List<String> lines = new ArrayList<>();
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.EXPERIENCE_BULLET),
                " have experience evidence");
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.PROJECT_BULLET),
                " have project evidence");
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.SUMMARY),
                " appear in the summary");
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.SKILLS_ONLY),
                " appear only in the skills list");
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.EDUCATION),
                " have education evidence");
        addStrength(lines, names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.OTHER),
                " have supporting resume text");
        List<String> pursuing = currentlyPursuing(requirements);
        if (!pursuing.isEmpty()) {
            lines.add(join(pursuing) + " " + (pursuing.size() == 1 ? "is" : "are")
                    + " currently pursuing");
        }
        List<String> partial = names(requirements, CoverageStatus.PARTIALLY_SUPPORTED, null);
        partial.removeAll(pursuing);
        if (!partial.isEmpty()) {
            lines.add(join(partial) + " " + (partial.size() == 1 ? "is" : "are") + " only partially supported");
        }
        return lines;
    }

    private static List<String> gaps(List<TailorAnalysisRequirement> requirements) {
        List<String> missing = names(requirements, CoverageStatus.MISSING_EVIDENCE, null);
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of("No evidence for " + join(missing));
    }

    private static List<String> nextActions(
            List<TailorAnalysisRequirement> requirements,
            List<EvidenceQuestion> questions) {

        if (questions != null && !questions.isEmpty()) {
            List<String> actions = new ArrayList<>();
            for (EvidenceQuestion question : questions) {
                if (question == null || question.getPromptEn() == null || question.getPromptEn().isBlank()) {
                    continue;
                }
                actions.add(question.getPromptEn());
                if (actions.size() >= 4) {
                    break;
                }
            }
            if (!actions.isEmpty()) {
                return actions;
            }
        }
        return fallbackNextActions(requirements);
    }

    private static List<String> fallbackNextActions(List<TailorAnalysisRequirement> requirements) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (String name : names(requirements, CoverageStatus.MISSING_EVIDENCE, null)) {
            actions.add("Confirm whether " + name + " was actually used in a project");
            if (actions.size() >= 3) {
                break;
            }
        }
        List<String> skillsOnly = names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.SKILLS_ONLY);
        if (!skillsOnly.isEmpty()) {
            actions.add("Describe how " + join(skillsOnly) + " was used in a project or experience bullet");
        }
        if (!names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.PROJECT_BULLET).isEmpty()
                || !names(requirements, CoverageStatus.SUPPORTED, EvidenceKind.EXPERIENCE_BULLET).isEmpty()) {
            actions.add("Add project outcomes or scale already present in your work if they are missing from the resume");
        }
        List<String> limited = new ArrayList<>();
        for (String action : actions) {
            limited.add(action);
            if (limited.size() >= 4) {
                break;
            }
        }
        return limited;
    }

    private static void addStrength(List<String> lines, List<String> names, String suffix) {
        if (!names.isEmpty()) {
            lines.add(join(names) + suffix);
        }
    }

    private static List<String> names(
            List<TailorAnalysisRequirement> requirements,
            String status,
            String evidenceKind) {

        Set<String> names = new LinkedHashSet<>();
        if (requirements == null) {
            return List.of();
        }
        for (TailorAnalysisRequirement requirement : requirements) {
            if (requirement == null
                    || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())
                    || !status.equals(requirement.getStatus())) {
                continue;
            }
            if (evidenceKind != null && !evidenceKind.equals(requirement.getEvidenceKind())) {
                continue;
            }
            String name = displayName(requirement);
            if (name != null) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> currentlyPursuing(List<TailorAnalysisRequirement> requirements) {
        List<String> names = new ArrayList<>();
        if (requirements == null) {
            return names;
        }
        for (TailorAnalysisRequirement requirement : requirements) {
            if (requirement == null
                    || !CoverageStatus.PARTIALLY_SUPPORTED.equals(requirement.getStatus())) {
                continue;
            }
            if (!QualificationMatcher.CURRENTLY_PURSUING.equals(requirement.getCoverageReason())) {
                continue;
            }
            String name = displayName(requirement);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static String displayName(TailorAnalysisRequirement requirement) {
        String topic = EvidenceTopics.topicOf(requirement);
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return topic;
    }

    static String join(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + ", and " + names.get(names.size() - 1);
    }
}
