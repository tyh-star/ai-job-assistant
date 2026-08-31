package aijob.util;

/**
 * Quote and identity checks against the analysis evidence registry.
 * Layout block ids are accepted only when registered from parent layout_json.
 */
public final class AnalysisEvidenceValidator {

    private AnalysisEvidenceValidator() {
    }

    public static boolean quoteMatches(AnalysisEvidenceSource source, String quote) {
        if (source == null) {
            return false;
        }
        String haystack = EvidenceText.normalize(source.text());
        if (haystack.isEmpty()) {
            return false;
        }
        if (quote == null || quote.isBlank()) {
            return true;
        }
        return haystack.contains(EvidenceText.normalize(quote));
    }

    public static boolean isUsableMatchingNode(ResumeNodeRole role) {
        return role != null
                && role != ResumeNodeRole.HEADING
                && role != ResumeNodeRole.DATE
                && role != ResumeNodeRole.CONTACT
                && role != ResumeNodeRole.LINK;
    }
}
