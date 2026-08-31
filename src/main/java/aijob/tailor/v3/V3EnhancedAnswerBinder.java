package aijob.tailor.v3;

import aijob.dto.AnswerStatus;
import aijob.dto.RequirementCategory;
import aijob.util.EvidenceTopics;

import java.util.regex.Pattern;

/**
 * Server-side Enhanced answer checks. Question ids and entry keys are never
 * taken from the client as authority.
 */
public final class V3EnhancedAnswerBinder {

    public static final String SKIPPED = "SKIPPED";

    private static final Pattern YES_NO = Pattern.compile(
            "(?i)^(yes|no|y|n|yeah|yep|true|false|ok|有|做过|是|用过|有做过)[.!。！\\s]*$");
    private static final Pattern MAINTAIN_WORDS = Pattern.compile(
            "(?i)\\b(?:maintain|maintained|maintaining|maintenance)\\b");

    private V3EnhancedAnswerBinder() {
    }

    public static boolean skipped(String answerStatus) {
        return SKIPPED.equalsIgnoreCase(answerStatus == null ? "" : answerStatus.strip());
    }

    public static boolean confirmedUsed(String answerStatus) {
        return AnswerStatus.CONFIRMED_USED.equals(answerStatus == null ? "" : answerStatus.strip());
    }

    public static boolean confirmedNotUsed(String answerStatus) {
        return AnswerStatus.CONFIRMED_NOT_USED.equals(answerStatus == null ? "" : answerStatus.strip());
    }

    public static boolean validStatus(String answerStatus) {
        return skipped(answerStatus) || confirmedUsed(answerStatus) || confirmedNotUsed(answerStatus);
    }

    public static boolean specificMaintainAnswer(String answerText) {
        String text = answerText == null ? "" : answerText.strip();
        if (text.length() < 12 || YES_NO.matcher(text).matches()) {
            return false;
        }
        String withoutMaintain = MAINTAIN_WORDS.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return withoutMaintain.length() >= 12;
    }

    public static boolean specificSkillGapAnswer(String answerText) {
        String text = answerText == null ? "" : answerText.strip();
        return text.length() >= 12 && !YES_NO.matcher(text).matches();
    }

    public static boolean skillGapTopic(String topic) {
        String key = EvidenceTopics.topicKey(topic);
        return EvidenceTopics.ENHANCED_API_CONTRACT.equals(key)
                || EvidenceTopics.ENHANCED_NX_MONOREPO.equals(key)
                || EvidenceTopics.ENHANCED_EVENT_DRIVEN.equals(key);
    }

    public static String topicLabel(V3EnhancedQuestion question) {
        String key = canonicalTopicKey(question);
        return key.isBlank() ? "project-detail" : question.topic();
    }

    /**
     * Canonical closed-catalog topic from the replanned question. Never parsed
     * from questionId, English askable copy, or client text.
     */
    public static String canonicalTopicKey(V3EnhancedQuestion question) {
        if (question == null || question.topic() == null || question.topic().isBlank()) {
            return "";
        }
        return EvidenceTopics.topicKey(question.topic());
    }

    public static String evidenceCategory(V3EnhancedQuestion question) {
        if (EvidenceTopics.isEnhancedMetricTopic(canonicalTopicKey(question))) {
            return RequirementCategory.RESPONSIBILITY;
        }
        return RequirementCategory.HARD_SKILL;
    }
}
