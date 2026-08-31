package aijob.tailor.v3;

import java.util.List;

public record V3EntryRewriteResult(
        String canonicalEntryKey,
        V3EntryRewriteVariant clarityFirst,
        V3EntryRewriteVariant jdTargeted,
        V3GenerationTelemetry telemetry
) {
    public V3EntryRewriteResult {
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        clarityFirst = clarityFirst == null
                ? V3EntryRewriteVariant.noChange(V3VariantType.CLARITY_FIRST)
                : clarityFirst;
        jdTargeted = jdTargeted == null
                ? V3EntryRewriteVariant.noChange(V3VariantType.JD_TARGETED)
                : jdTargeted;
        telemetry = telemetry == null ? V3GenerationTelemetry.mock() : telemetry;
    }

    public static V3EntryRewriteResult noChange(String canonicalEntryKey) {
        return new V3EntryRewriteResult(
                canonicalEntryKey,
                V3EntryRewriteVariant.noChange(V3VariantType.CLARITY_FIRST),
                V3EntryRewriteVariant.noChange(V3VariantType.JD_TARGETED),
                V3GenerationTelemetry.mock());
    }

    public static V3EntryRewriteResult both(
            String canonicalEntryKey,
            V3EntryRewriteVariant clarityFirst,
            V3EntryRewriteVariant jdTargeted) {
        return new V3EntryRewriteResult(
                canonicalEntryKey, clarityFirst, jdTargeted, V3GenerationTelemetry.mock());
    }

    public List<V3EntryRewriteVariant> variants() {
        return List.of(clarityFirst, jdTargeted);
    }
}
