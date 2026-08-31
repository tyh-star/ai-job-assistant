package aijob.util;

import java.util.Locale;

/**
 * Same whitespace/case folding as JobServiceImpl.normalizeNodeText.
 * Kept separate so Tailor Analysis does not change the P2 filter.
 */
public final class EvidenceText {

    private EvidenceText() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
