package aijob.tailor.v3;

import aijob.entity.ResumeEvidenceItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps stored evidence rows onto the Fact Graph adapter. The evidence table
 * remains the source of truth.
 */
public final class V3UserEvidenceAdapter {

    private V3UserEvidenceAdapter() {
    }

    public static V3UserEvidence from(ResumeEvidenceItem item) {
        if (item == null) {
            return null;
        }
        return new V3UserEvidence(
                item.getId(),
                item.getEntryKey(),
                item.getAnswerStatus(),
                item.getActionText(),
                item.getResultText(),
                item.getMetricText(),
                item.getContextText(),
                item.getTopicLabel(),
                item.getTopicKey());
    }

    public static List<V3UserEvidence> from(List<ResumeEvidenceItem> items) {
        List<V3UserEvidence> evidence = new ArrayList<>();
        if (items == null) {
            return evidence;
        }
        for (ResumeEvidenceItem item : items) {
            V3UserEvidence mapped = from(item);
            if (mapped != null) {
                evidence.add(mapped);
            }
        }
        return evidence;
    }
}
