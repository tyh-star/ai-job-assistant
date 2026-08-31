package aijob.tailor.v3;

import java.util.List;

public record V3ResumeFactGraphs(
        List<V3EntryFactGraph> entries,
        List<V3UserEvidence> unlinkedEvidence
) {
    public V3ResumeFactGraphs {
        entries = entries == null ? List.of() : List.copyOf(entries);
        unlinkedEvidence = unlinkedEvidence == null ? List.of() : List.copyOf(unlinkedEvidence);
    }

    public V3EntryFactGraph entry(String canonicalEntryKey) {
        if (canonicalEntryKey == null) {
            return null;
        }
        for (V3EntryFactGraph entry : entries) {
            if (canonicalEntryKey.equals(entry.canonicalEntryKey())) {
                return entry;
            }
        }
        return null;
    }
}
