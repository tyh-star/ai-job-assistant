package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EntryOption;
import aijob.dto.EvidenceAvailability;
import aijob.dto.EvidenceKind;
import aijob.dto.EvidenceQuestion;
import aijob.dto.QuestionType;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.TailorAnalysisRequirement;
import aijob.entity.ResumeEvidenceItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic Evidence Bank questions. No OpenAI.
 */
public final class EvidenceQuestionGenerator {

    public static final int MAX_QUESTIONS = 5;

    private EvidenceQuestionGenerator() {
    }

    public static List<EvidenceQuestion> generate(
            List<TailorAnalysisRequirement> requirements,
            List<ResumeEvidenceItem> bank,
            List<EntryOption> entryOptions) {

        List<Ranked> ranked = new ArrayList<>();
        if (requirements != null) {
            for (TailorAnalysisRequirement requirement : requirements) {
                if (requirement == null || CoverageStatus.NOT_APPLICABLE.equals(requirement.getStatus())) {
                    continue;
                }
                String topic = EvidenceTopics.topicOf(requirement);
                if (topic.isBlank()) {
                    continue;
                }
                if (EvidenceAvailabilityMapper.topicFullyAnswered(requirement, bank)) {
                    continue;
                }
                if (EvidenceAvailability.IN_RESUME.equals(requirement.getEvidenceAvailability())
                        && CoverageStatus.SUPPORTED.equals(requirement.getStatus())
                        && !EvidenceKind.SKILLS_ONLY.equals(requirement.getEvidenceKind())) {
                    continue;
                }
                if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory())
                        && !CoverageStatus.MISSING_EVIDENCE.equals(requirement.getStatus())) {
                    continue;
                }
                int rank = rank(requirement);
                if (rank <= 0) {
                    continue;
                }
                List<String> missing = EvidenceSemanticValidator.missingActions(requirement, bank);
                ranked.add(new Ranked(rank, requirement, topic, missing));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::rank));
        List<EvidenceQuestion> questions = new ArrayList<>();
        for (Ranked item : ranked) {
            questions.add(toQuestion(item, entryOptionsFor(item.requirement, entryOptions)));
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return questions;
    }

    static int rank(TailorAnalysisRequirement requirement) {
        String status = requirement.getStatus();
        String priority = requirement.getPriority();
        String category = requirement.getCategory();
        if (RequirementPriority.OPTIONAL.equals(priority)) {
            return 0;
        }
        if (RequirementCategory.SOFT_SKILL.equals(category)) {
            if (RequirementPriority.MUST_HAVE.equals(priority)
                    && CoverageStatus.MISSING_EVIDENCE.equals(status)
                    && !hasBulletEvidence(requirement)) {
                return 5;
            }
            return 0;
        }
        if (RequirementCategory.QUALIFICATION.equals(category)) {
            return CoverageStatus.MISSING_EVIDENCE.equals(status) ? 4 : 0;
        }
        boolean must = RequirementPriority.MUST_HAVE.equals(priority);
        boolean preferred = RequirementPriority.PREFERRED.equals(priority);
        if (must && CoverageStatus.MISSING_EVIDENCE.equals(status)
                && (RequirementCategory.HARD_SKILL.equals(category)
                || RequirementCategory.RESPONSIBILITY.equals(category))) {
            return 1;
        }
        if (must && CoverageStatus.PARTIALLY_SUPPORTED.equals(status)
                && (RequirementCategory.HARD_SKILL.equals(category)
                || RequirementCategory.RESPONSIBILITY.equals(category))) {
            return 2;
        }
        if (must && CoverageStatus.SUPPORTED.equals(status) && EvidenceKind.SKILLS_ONLY.equals(requirement.getEvidenceKind())
                && RequirementCategory.HARD_SKILL.equals(category)) {
            return 3;
        }
        if (preferred && (CoverageStatus.MISSING_EVIDENCE.equals(status)
                || CoverageStatus.PARTIALLY_SUPPORTED.equals(status)
                || (CoverageStatus.SUPPORTED.equals(status)
                && EvidenceKind.SKILLS_ONLY.equals(requirement.getEvidenceKind())))) {
            return 5;
        }
        return 0;
    }

    private static EvidenceQuestion toQuestion(Ranked ranked, List<EntryOption> entryOptions) {
        TailorAnalysisRequirement requirement = ranked.requirement;
        String type = questionType(requirement, ranked.rank);
        EvidenceQuestion question = new EvidenceQuestion();
        question.setId("eq-" + requirement.getId());
        question.setRequirementId(requirement.getId());
        question.setQuestionType(type);
        question.setTopic(ranked.topic);
        question.setPriority(requirement.getPriority());
        question.setMissingActions(List.copyOf(ranked.missingActions));
        question.setPromptZh(promptZh(type, ranked.topic, ranked.missingActions));
        question.setPromptEn(promptEn(type, ranked.topic, ranked.missingActions));
        question.setEntryOptions(entryOptions == null ? List.of() : List.copyOf(entryOptions));
        return question;
    }

    static String questionType(TailorAnalysisRequirement requirement, int rank) {
        if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory())) {
            return QuestionType.EDUCATION_CONFIRMATION;
        }
        if (rank == 3 || (CoverageStatus.SUPPORTED.equals(requirement.getStatus())
                && EvidenceKind.SKILLS_ONLY.equals(requirement.getEvidenceKind()))) {
            return QuestionType.SKILL_APPLICATION;
        }
        if (RequirementCategory.RESPONSIBILITY.equals(requirement.getCategory())) {
            return QuestionType.RESPONSIBILITY_EXAMPLE;
        }
        return QuestionType.SKILL_USAGE;
    }

    static String promptZh(String type, String topic) {
        return promptZh(type, topic, List.of());
    }

    static String promptEn(String type, String topic) {
        return promptEn(type, topic, List.of());
    }

    static String promptZh(String type, String topic, List<String> missingActions) {
        if (QuestionType.EDUCATION_CONFIRMATION.equals(type)) {
            return "简历里还没有「" + topic + "」的学历信息。请补充学校、学位和就读时间（在读请写 Present）。";
        }
        String missingHint = missingHintZh(missingActions);
        if (QuestionType.RESPONSIBILITY_EXAMPLE.equals(type)) {
            return missingHint + "你是否在某个项目或工作中实际完成过「" + topic + "」？如果有，请选择对应项目并说明你做了什么。";
        }
        if (QuestionType.SKILL_APPLICATION.equals(type)) {
            return "简历技能列表里有「" + topic + "」。你是否在某个项目或经历中实际使用过它？请选择条目并说明你用它做了什么。";
        }
        return missingHint + "你是否在某个项目或工作中实际使用过 " + topic + "？如果使用过，请选择对应项目并说明你用它做了什么。";
    }

    static String promptEn(String type, String topic, List<String> missingActions) {
        if (QuestionType.EDUCATION_CONFIRMATION.equals(type)) {
            return "Your resume does not yet include education for \"" + topic
                    + "\". Add the school, degree, and dates. If you are still enrolled, use Present.";
        }
        String missingHint = missingHintEn(missingActions);
        if (QuestionType.RESPONSIBILITY_EXAMPLE.equals(type)) {
            return missingHint + "Have you actually done \"" + topic
                    + "\" in a project or job? If yes, select the relevant entry and describe what you did.";
        }
        if (QuestionType.SKILL_APPLICATION.equals(type)) {
            return "\"" + topic
                    + "\" appears only in your skills list. Have you used it in a project or job? If yes, select the relevant entry and describe what you did.";
        }
        return missingHint + "Have you used " + topic
                + " in a project or job? If yes, select the relevant entry and describe what you did.";
    }

    private static String missingHintZh(List<String> missingActions) {
        if (missingActions == null || missingActions.isEmpty()) {
            return "";
        }
        return "简历已覆盖部分职责，但仍缺少「" + String.join("、", missingActions) + "」经历。";
    }

    private static String missingHintEn(List<String> missingActions) {
        if (missingActions == null || missingActions.isEmpty()) {
            return "";
        }
        return "Your resume already covers part of this duty, but not "
                + String.join(" and ", missingActions) + ". ";
    }

    private static List<EntryOption> entryOptionsFor(
            TailorAnalysisRequirement requirement,
            List<EntryOption> entryOptions) {
        if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory())) {
            return List.of();
        }
        return entryOptions == null ? List.of() : entryOptions;
    }

    private static boolean hasBulletEvidence(TailorAnalysisRequirement requirement) {
        return EvidenceKind.PROJECT_BULLET.equals(requirement.getEvidenceKind())
                || EvidenceKind.EXPERIENCE_BULLET.equals(requirement.getEvidenceKind());
    }

    private record Ranked(
            int rank,
            TailorAnalysisRequirement requirement,
            String topic,
            List<String> missingActions
    ) {
    }
}
