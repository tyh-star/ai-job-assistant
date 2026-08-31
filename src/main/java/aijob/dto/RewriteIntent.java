package aijob.dto;

/**
 * Internal CONSERVATIVE rewrite intent. Not a Writer or proposal field.
 */
public final class RewriteIntent {

    public static final String SAME_ENTRY_EVIDENCE_ENRICHMENT = "SAME_ENTRY_EVIDENCE_ENRICHMENT";
    public static final String JD_RELEVANCE = "JD_RELEVANCE";
    public static final String CLARITY_IMPROVEMENT = "CLARITY_IMPROVEMENT";
    public static final String GRAMMAR_FIX = "GRAMMAR_FIX";
    public static final String SUMMARY_REWRITE = "SUMMARY_REWRITE";
    public static final String LOW_VALUE_REWRITE = "LOW_VALUE_REWRITE";

    private RewriteIntent() {
    }

    public static int priority(String intent) {
        if (SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(intent)) {
            return 5;
        }
        if (JD_RELEVANCE.equals(intent)) {
            return 4;
        }
        if (CLARITY_IMPROVEMENT.equals(intent)) {
            return 3;
        }
        if (GRAMMAR_FIX.equals(intent)) {
            return 2;
        }
        if (SUMMARY_REWRITE.equals(intent)) {
            return 1;
        }
        return 0;
    }
}
