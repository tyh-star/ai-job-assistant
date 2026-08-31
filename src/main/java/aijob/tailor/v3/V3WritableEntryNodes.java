package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.ClaimLedger;
import aijob.util.ResumeTailorNodeGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writable PROJECT/EXPERIENCE bullets for an entry. Headings, dates, links,
 * contact, and education nodes are excluded.
 */
public final class V3WritableEntryNodes {

    private static final Pattern URL = Pattern.compile("(?i)https?://|www\\.|github\\.com|linkedin\\.com");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern MONTH = Pattern.compile(
            "(?i)\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                    + "jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|"
                    + "nov(?:ember)?|dec(?:ember)?)\\b");
    private static final Pattern PRESENT = Pattern.compile("(?i)\\b(?:present|current)\\b");

    private V3WritableEntryNodes() {
    }

    public static List<V3WritableNode> of(ClaimLedger.Entry entry, List<ResumeDocumentNode> allNodes) {
        Set<String> allowed = ResumeTailorNodeGuard.allowedNodeIds(allNodes);
        List<V3WritableNode> writable = new ArrayList<>();
        if (entry == null || entry.nodes() == null) {
            return writable;
        }
        int i = 0;
        boolean bulletsStarted = false;
        for (ResumeDocumentNode node : entry.nodes()) {
            String text = node.getText() == null ? "" : node.getText();
            String id = node.getNodeId();
            if (i == 0) {
                i++;
                continue;
            }
            if (looksLikeUrl(text)) {
                i++;
                continue;
            }
            if (!bulletsStarted && looksLikeDateLine(text)) {
                i++;
                continue;
            }
            bulletsStarted = true;
            if (id != null && allowed.contains(id) && !text.isBlank()) {
                writable.add(new V3WritableNode(id, text));
            }
            i++;
        }
        return writable;
    }

    public static int characterBudget(List<V3WritableNode> nodes) {
        int total = 0;
        for (V3WritableNode node : nodes) {
            total += node.originalText().length();
        }
        return Math.max(120, (int) (total * 1.25) + 40);
    }

    private static boolean looksLikeUrl(String text) {
        return text != null && URL.matcher(text).find() && text.length() < 180;
    }

    private static boolean looksLikeDateLine(String text) {
        if (text == null || text.isBlank() || text.length() > 80) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return YEAR.matcher(text).find() || MONTH.matcher(text).find() || PRESENT.matcher(lower).find();
    }
}
