package aijob.util;

import aijob.dto.AnswerStatus;
import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceAvailability;
import aijob.dto.RequirementCategory;
import aijob.dto.TailorAnalysisRequirement;
import aijob.entity.ResumeEvidenceItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvidenceAvailabilityMapper {

    private EvidenceAvailabilityMapper() {
    }

    public static void apply(
            List<TailorAnalysisRequirement> requirements,
            List<ResumeEvidenceItem> bank) {

        Map<String, List<ResumeEvidenceItem>> byTopic = index(bank);
        if (requirements == null) {
            return;
        }
        for (TailorAnalysisRequirement requirement : requirements) {
            if (requirement == null) {
                continue;
            }
            String key = EvidenceTopics.topicKey(requirement);
            List<ResumeEvidenceItem> items = byTopic.getOrDefault(key, List.of());
            List<Long> ids = new ArrayList<>();
            boolean confirmedUsed = false;
            boolean confirmedNotUsed = false;
            for (ResumeEvidenceItem item : items) {
                EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateStored(item, requirement);
                if (!result.valid()) {
                    continue;
                }
                if (AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())) {
                    if (coversRequirement(requirement, item, items)) {
                        confirmedUsed = true;
                    }
                } else if (AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus())) {
                    confirmedNotUsed = true;
                }
            }
            String availability;
            if (hasResumeEvidence(requirement)) {
                availability = EvidenceAvailability.IN_RESUME;
            } else if (confirmedUsed) {
                availability = EvidenceAvailability.USER_CONFIRMED;
            } else if (confirmedNotUsed) {
                availability = EvidenceAvailability.USER_REJECTED;
            } else {
                availability = EvidenceAvailability.NONE;
            }
            for (ResumeEvidenceItem item : items) {
                if (item.getId() == null) {
                    continue;
                }
                EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateStored(item, requirement);
                if (!result.valid()) {
                    continue;
                }
                if (EvidenceAvailability.USER_CONFIRMED.equals(availability)
                        && !AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())) {
                    continue;
                }
                if (EvidenceAvailability.USER_REJECTED.equals(availability)
                        && !AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus())) {
                    continue;
                }
                if (EvidenceAvailability.NONE.equals(availability)) {
                    continue;
                }
                if (EvidenceAvailability.IN_RESUME.equals(availability)) {
                    continue;
                }
                ids.add(item.getId());
            }
            requirement.setEvidenceItemIds(ids);
            requirement.setEvidenceAvailability(availability);
        }
    }

    public static boolean topicFullyAnswered(TailorAnalysisRequirement requirement, List<ResumeEvidenceItem> bank) {
        if (requirement == null || bank == null) {
            return false;
        }
        String topicKey = EvidenceTopics.topicKey(requirement);
        boolean rejected = false;
        List<ResumeEvidenceItem> matched = new ArrayList<>();
        for (ResumeEvidenceItem item : bank) {
            if (item == null || !topicKey.equals(item.getTopicKey())) {
                continue;
            }
            EvidenceSemanticValidator.Result result = EvidenceSemanticValidator.validateStored(item, requirement);
            if (!result.valid()) {
                continue;
            }
            if (AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus())) {
                rejected = true;
            }
            if (AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())) {
                matched.add(item);
            }
        }
        if (rejected && matched.isEmpty()) {
            return true;
        }
        if (matched.isEmpty()) {
            return false;
        }
        return coversRequirement(requirement, matched.get(0), matched);
    }

    public static boolean topicAnswered(String topicKey, List<ResumeEvidenceItem> bank) {
        if (topicKey == null || topicKey.isBlank() || bank == null) {
            return false;
        }
        for (ResumeEvidenceItem item : bank) {
            if (item == null || !topicKey.equals(item.getTopicKey())) {
                continue;
            }
            if (AnswerStatus.CONFIRMED_NOT_USED.equals(item.getAnswerStatus())
                    && EvidenceSemanticValidator.isValidRejection(item)) {
                return true;
            }
            TailorAnalysisRequirement stub = new TailorAnalysisRequirement();
            stub.setRequirement(item.getTopicLabel() == null ? item.getTopicKey() : item.getTopicLabel());
            stub.setCategory(item.getCategory());
            stub.setExactTerms(List.of(item.getTopicLabel() == null ? topicKey : item.getTopicLabel()));
            if (EvidenceSemanticValidator.isValidConfirmation(item, stub)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasResumeEvidence(TailorAnalysisRequirement requirement) {
        if (requirement.getEvidenceNodeIds() != null && !requirement.getEvidenceNodeIds().isEmpty()) {
            return true;
        }
        return CoverageStatus.SUPPORTED.equals(requirement.getStatus())
                || CoverageStatus.PARTIALLY_SUPPORTED.equals(requirement.getStatus());
    }

    private static boolean coversRequirement(
            TailorAnalysisRequirement requirement,
            ResumeEvidenceItem item,
            List<ResumeEvidenceItem> items) {
        if (requirement != null && RequirementCategory.RESPONSIBILITY.equals(requirement.getCategory())) {
            return EvidenceSemanticValidator.missingActions(requirement, items).isEmpty();
        }
        return EvidenceSemanticValidator.isValidConfirmation(item, requirement);
    }

    private static Map<String, List<ResumeEvidenceItem>> index(List<ResumeEvidenceItem> bank) {
        Map<String, List<ResumeEvidenceItem>> byTopic = new LinkedHashMap<>();
        if (bank == null) {
            return byTopic;
        }
        for (ResumeEvidenceItem item : bank) {
            if (item == null || item.getTopicKey() == null) {
                continue;
            }
            byTopic.computeIfAbsent(item.getTopicKey(), ignored -> new ArrayList<>()).add(item);
        }
        return byTopic;
    }
}
