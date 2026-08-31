package aijob.util;

/**
 * One verifiable evidence source for Tailor Analysis. IDs are trusted only
 * after they are registered from matching nodes, parent layout blocks, or
 * structured education records.
 */
public record AnalysisEvidenceSource(
        String id,
        String text,
        Kind kind
) {

    public enum Kind {
        MATCHING_NODE,
        LAYOUT_BLOCK,
        STRUCTURED_EDUCATION
    }
}
