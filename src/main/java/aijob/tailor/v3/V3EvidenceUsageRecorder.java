package aijob.tailor.v3;

import aijob.dto.V3EntryReview;
import aijob.dto.V3EvidenceUsage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explains how each saved evidence item was used. Never deletes unused evidence
 * and never admits a weak rewrite just to mark an item used.
 */
public final class V3EvidenceUsageRecorder {

    private V3EvidenceUsageRecorder() {
    }

    public static List<V3EvidenceUsage> record(
            List<V3UserEvidence> evidence,
            List<V3EntryReview> entryReviews,
            V3SkillsProjector.Result skills,
            String originalResumeText,
            List<Long> unusedRequiredIds) {
        List<V3EvidenceUsage> usages = new ArrayList<>();
        if (evidence == null) {
            return usages;
        }
        Map<Long, List<String>> skillKeys = skills == null ? Map.of() : skills.skillKeysByEvidenceId();
        boolean skillsWritable = skills != null && skills.writablePresent();
        boolean skillsPresent = skills != null && skills.sectionPresent();
        String original = originalResumeText == null ? "" : originalResumeText.toLowerCase(Locale.ROOT);
        LinkedHashSet<Long> unusedRequired = new LinkedHashSet<>();
        if (unusedRequiredIds != null) {
            unusedRequired.addAll(unusedRequiredIds);
        }
        for (V3UserEvidence item : evidence) {
            if (item == null || item.evidenceItemId() == null) {
                continue;
            }
            usages.add(usageOf(
                    item, entryReviews, skillKeys, skillsPresent, skillsWritable, original, unusedRequired));
        }
        return usages;
    }

    private static V3EvidenceUsage usageOf(
            V3UserEvidence item,
            List<V3EntryReview> entryReviews,
            Map<Long, List<String>> skillKeys,
            boolean skillsPresent,
            boolean skillsWritable,
            String originalResumeText,
            LinkedHashSet<Long> unusedRequired) {
        V3EvidenceUsage usage = new V3EvidenceUsage();
        usage.setEvidenceItemId(item.evidenceItemId());
        usage.setCanonicalEntryKey(item.storedEntryKey());
        usage.setTopicKey(item.topicKey());
        if (item.rejected()) {
            usage.setUsageStatus(V3EvidenceUsage.USER_REJECTED);
            usage.setReasonCode("");
            return usage;
        }
        List<String> usedEntries = usedEntryKeys(item.evidenceItemId(), entryReviews);
        List<String> usedSkills = skillKeys.getOrDefault(item.evidenceItemId(), List.of());
        boolean inEntry = !usedEntries.isEmpty();
        boolean inSkills = skillsWritable && !usedSkills.isEmpty();
        usage.setUsedInEntryKeys(usedEntries);
        usage.setUsedInSkillKeys(usedSkills);
        if (inEntry && inSkills) {
            usage.setUsageStatus(V3EvidenceUsage.USED_IN_ENTRY_AND_SKILLS);
            return usage;
        }
        if (inEntry) {
            usage.setUsageStatus(V3EvidenceUsage.USED_IN_ENTRY);
            return usage;
        }
        if (inSkills) {
            usage.setUsageStatus(V3EvidenceUsage.USED_IN_SKILLS);
            return usage;
        }
        usage.setUsageStatus(V3EvidenceUsage.SAVED_NOT_USED);
        if (unusedRequired.contains(item.evidenceItemId())) {
            usage.setReasonCode(V3EvidenceUsage.CONFIRMED_FACT_NOT_USED);
        } else {
            usage.setReasonCode(unusedReason(item, skillsPresent, skillsWritable, originalResumeText));
        }
        return usage;
    }

    private static String unusedReason(
            V3UserEvidence item,
            boolean skillsPresent,
            boolean skillsWritable,
            String originalResumeText) {
        boolean skillCandidate = V3SkillsProjector.isProjectableSkillTopic(item);
        if (skillCandidate && (!skillsPresent || !skillsWritable)) {
            return V3EvidenceUsage.NO_SKILLS_SECTION;
        }
        String surface = item.displayText().toLowerCase(Locale.ROOT);
        if (!surface.isBlank() && originalResumeText.contains(surface)) {
            return V3EvidenceUsage.FACT_ALREADY_PRESENT;
        }
        if (skillCandidate && skillAlreadyOnResume(item, originalResumeText)) {
            return V3EvidenceUsage.FACT_ALREADY_PRESENT;
        }
        if (!skillCandidate && V3SkillsProjector.looksLikeMetricEvidence(item)) {
            return V3EvidenceUsage.NOT_A_SKILL;
        }
        return V3EvidenceUsage.NO_ADMITTED_REWRITE;
    }

    private static boolean skillAlreadyOnResume(V3UserEvidence item, String originalResumeText) {
        for (V3SkillsProjector.Extracted extracted : V3SkillsProjector.extract(item)) {
            if (originalResumeText.contains(extracted.display().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> usedEntryKeys(Long evidenceItemId, List<V3EntryReview> entryReviews) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (entryReviews == null) {
            return List.of();
        }
        for (V3EntryReview review : entryReviews) {
            if (review == null || review.getUsedEvidenceItemIds() == null) {
                continue;
            }
            if (review.getUsedEvidenceItemIds().contains(evidenceItemId)) {
                keys.add(review.getCanonicalEntryKey());
            }
        }
        return new ArrayList<>(keys);
    }
}
