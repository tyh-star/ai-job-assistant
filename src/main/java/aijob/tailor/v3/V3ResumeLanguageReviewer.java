package aijob.tailor.v3;

import java.util.List;

/**
 * One resume-level comparison call. Selects ORIGINAL, CLARITY_FIRST,
 * JD_TARGETED, HYBRID_1, HYBRID_2, or REJECT_ALL_AI. Must not write a third version.
 */
public interface V3ResumeLanguageReviewer {

    List<Choice> review(String jobDescription, List<EntryComparison> entries);

    record EntryComparison(
            String canonicalEntryKey,
            List<String> originalBullets,
            String originalEntryText,
            V3QualityScore originalScore,
            V3EntryRewriteVariant clarityFirst,
            V3QualityScore clarityScore,
            V3EntryRewriteVariant jdTargeted,
            V3QualityScore targetedScore,
            List<V3RankedRequirement> requirements,
            V3EntryRewriteVariant hybrid1,
            V3QualityScore hybrid1Score,
            List<V3NodeSource> hybrid1Sources,
            V3EntryRewriteVariant hybrid2,
            V3QualityScore hybrid2Score,
            List<V3NodeSource> hybrid2Sources
    ) {
        public EntryComparison {
            canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
            originalBullets = originalBullets == null ? List.of() : List.copyOf(originalBullets);
            originalEntryText = originalEntryText == null ? "" : originalEntryText;
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            hybrid1Sources = hybrid1Sources == null ? List.of() : List.copyOf(hybrid1Sources);
            hybrid2Sources = hybrid2Sources == null ? List.of() : List.copyOf(hybrid2Sources);
        }
    }

    record Choice(String canonicalEntryKey, V3SelectionChoice selection, String reason) {
        public Choice {
            canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
            selection = selection == null ? V3SelectionChoice.ORIGINAL : selection;
            reason = reason == null ? "" : reason;
        }
    }
}
