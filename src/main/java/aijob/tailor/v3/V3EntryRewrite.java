package aijob.tailor.v3;

import java.util.List;

/**
 * Whole-entry rewrite under test. V3 Writer may only change PROJECT/EXPERIENCE
 * body bullets. Headings, dates, and employment status are identity fields and
 * must stay unchanged so {@link aijob.util.CanonicalEntryKeyResolver} keys do not drift.
 */
public record V3EntryRewrite(
        String canonicalEntryKey,
        String rewrittenHeading,
        String rewrittenDateText,
        List<String> rewrittenBullets
) {
    public V3EntryRewrite {
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        rewrittenHeading = rewrittenHeading == null ? "" : rewrittenHeading;
        rewrittenDateText = rewrittenDateText == null ? "" : rewrittenDateText;
        rewrittenBullets = rewrittenBullets == null ? List.of() : List.copyOf(rewrittenBullets);
    }

    public String rewrittenEntryText() {
        StringBuilder text = new StringBuilder(rewrittenHeading);
        if (!rewrittenDateText.isBlank()) {
            text.append(' ').append(rewrittenDateText);
        }
        for (String bullet : rewrittenBullets) {
            text.append(' ').append(bullet);
        }
        return text.toString();
    }
}
