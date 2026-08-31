package aijob.tailor.v3;

import aijob.util.EvidenceTopics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Enhanced-only required-evidence gate. Does not change BASIC validation.
 */
final class V3EnhancedEvidenceUtilization {

    private V3EnhancedEvidenceUtilization() {
    }

    static List<V3UserEvidence> required(
            String entryKey,
            List<V3UserEvidence> evidence,
            String jobDescription,
            V3EntryFactGraph originalGraph,
            String skillsText) {
        List<V3UserEvidence> required = new ArrayList<>();
        if (entryKey == null || entryKey.isBlank() || originalGraph == null) {
            return required;
        }
        LinkedHashSet<String> internTopics = internTopics(originalGraph, jobDescription);
        List<String> requirements = V3EnhancedAtomicCoverage.requirementTexts(jobDescription);
        for (V3UserEvidence item : evidence == null ? List.<V3UserEvidence>of() : evidence) {
            if (item == null || !item.confirmed() || item.rejected()) {
                continue;
            }
            if (!entryKey.equals(item.storedEntryKey())) {
                continue;
            }
            if (relatedToJdGap(item, requirements, originalGraph, skillsText, internTopics)) {
                required.add(item);
            }
        }
        return required;
    }

    static String repairError(List<V3UserEvidence> missing, String originalText) {
        StringBuilder text = new StringBuilder();
        text.append(V3ValidationDecision.MISSING_CONFIRMED_EVIDENCE);
        text.append(": the rewrite omitted confirmed facts this job needs.\n");
        text.append("Unused confirmed facts (natural language only; do not invent fact keys):\n");
        for (V3UserEvidence item : missing == null ? List.<V3UserEvidence>of() : missing) {
            text.append("- ").append(item.displayText()).append('\n');
        }
        text.append("Allowed facts: the original entry plus the unused confirmed facts above.\n");
        text.append("Original entry:\n").append(originalText == null ? "" : originalText).append('\n');
        text.append("Missing reason: proposed text did not add the distinctive facts from those items.\n");
        text.append("Keep original material facts. Do not invent NestJS, metrics, SmartPC facts, or fact keys.\n");
        return text.toString();
    }

    static boolean missingRequired(List<V3UserEvidence> required, String originalText, List<String> bullets) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        String proposed = String.join("\n", bullets == null ? List.of() : bullets);
        return !V3FinalEvidenceAttributor.missingRequired(required, originalText, proposed).isEmpty();
    }

    private static boolean relatedToJdGap(
            V3UserEvidence item,
            List<String> requirements,
            V3EntryFactGraph originalGraph,
            String skillsText,
            LinkedHashSet<String> internTopics) {
        String topic = item.topicKey();
        if (V3EnhancedAnswerBinder.skillGapTopic(topic)) {
            for (String requirement : requirements) {
                if (!topic.equals(V3EnhancedAtomicCoverage.skillGapTopic(requirement))) {
                    continue;
                }
                V3RequirementCoverage coverage = V3EnhancedAtomicCoverage.coverage(
                        requirement, originalGraph, skillsText);
                if (coverage == V3RequirementCoverage.PARTIALLY_COVERED
                        || coverage == V3RequirementCoverage.UNSUPPORTED) {
                    return true;
                }
            }
            return false;
        }
        if (EvidenceTopics.isEnhancedCatalogTopic(topic)) {
            return internTopics.contains(topic);
        }
        return false;
    }

    private static LinkedHashSet<String> internTopics(V3EntryFactGraph originalGraph, String jobDescription) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (V3EnhancedQuestionPlanner.Candidate candidate
                : V3EnhancedQuestionPlanner.candidates(originalGraph, jobDescription, List.of())) {
            if (candidate != null && candidate.question() != null) {
                topics.add(candidate.question().topic());
            }
        }
        return topics;
    }
}
