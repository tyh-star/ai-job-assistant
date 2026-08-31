package aijob.tailor.v3;

import aijob.dto.V3EntryReview;
import aijob.dto.V3EvidenceUsage;
import aijob.dto.V3SkillReview;
import aijob.dto.TailorChange;

import java.util.List;

public record V3EnhancedRunResult(
        List<TailorChange> changes,
        List<V3EntryReview> entryReviews,
        List<Long> evidenceItemIds,
        int rewrittenEntryCount,
        int unchangedEntryCount,
        int generateCalls,
        int repairCalls,
        List<V3BasicRunResult.V3EntryTrace> traces,
        List<V3EvidenceUsage> evidenceUsage,
        List<V3SkillReview> skillReviews
) {
    public V3EnhancedRunResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
        entryReviews = entryReviews == null ? List.of() : List.copyOf(entryReviews);
        evidenceItemIds = evidenceItemIds == null ? List.of() : List.copyOf(evidenceItemIds);
        traces = traces == null ? List.of() : List.copyOf(traces);
        evidenceUsage = evidenceUsage == null ? List.of() : List.copyOf(evidenceUsage);
        skillReviews = skillReviews == null ? List.of() : List.copyOf(skillReviews);
    }

    public int writerCalls() {
        return 0;
    }
}
