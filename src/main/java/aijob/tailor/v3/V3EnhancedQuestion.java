package aijob.tailor.v3;

import java.util.List;

/**
 * One Enhanced question bound to an exact canonicalEntryKey. Always skippable.
 */
public record V3EnhancedQuestion(
        String id,
        String canonicalEntryKey,
        String entryTitle,
        String topic,
        String promptEn,
        String promptZh,
        boolean skipAllowed,
        String requirementId,
        String requirementText,
        List<String> allowedEntryKeys
) {
    public V3EnhancedQuestion(
            String id,
            String canonicalEntryKey,
            String entryTitle,
            String topic,
            String promptEn,
            String promptZh,
            boolean skipAllowed) {
        this(id, canonicalEntryKey, entryTitle, topic, promptEn, promptZh, skipAllowed, "", "", List.of());
    }

    public V3EnhancedQuestion {
        id = id == null ? "" : id;
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        entryTitle = entryTitle == null ? "" : entryTitle;
        topic = topic == null ? "" : topic;
        promptEn = promptEn == null ? "" : promptEn;
        promptZh = promptZh == null ? "" : promptZh;
        requirementId = requirementId == null ? "" : requirementId;
        requirementText = requirementText == null ? "" : requirementText;
        List<String> allowed = allowedEntryKeys == null ? List.of() : List.copyOf(allowedEntryKeys);
        if (allowed.isEmpty() && !canonicalEntryKey.isBlank()) {
            allowed = List.of(canonicalEntryKey);
        }
        allowedEntryKeys = allowed;
    }

    public boolean allowsEntry(String entryKey) {
        if (entryKey == null || entryKey.isBlank()) {
            return false;
        }
        return allowedEntryKeys.contains(entryKey);
    }
}
