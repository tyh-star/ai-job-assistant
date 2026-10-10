package aijob.rewrite;

import aijob.dto.ResumeDocumentNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chooses which parsed DOCX paragraphs may be rewritten. Header lines that carry a job
 * title, company, school or dates (a date range, a "|" separator), section headings,
 * contact lines and very short lines are never sent as editable, so the model cannot
 * touch them at all; the fact guard still checks everything that is sent.
 */
public final class RewriteNodes {

    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?i)((19|20)\\d{2}|present|current|now|至今|现在)\\s*(年|\\.|/|-|–|—|to|至|~)?\\s*\\d{0,2}\\s*[-–—~至to]+\\s*"
                    + "((19|20)\\d{2}|present|current|now|至今|现在)");
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(19|20)\\d{2}\\b");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private RewriteNodes() {
    }

    public record Editable(String nodeId, String originalText, String redactedText) {
    }

    public static List<Editable> editable(List<ResumeDocumentNode> nodes, RewritePii pii) {
        List<Editable> result = new ArrayList<>();
        for (ResumeDocumentNode node : nodes) {
            if (node == null || node.getNodeId() == null || node.getText() == null) {
                continue;
            }
            String text = node.getText().trim();
            if (!isEditable(text)) {
                continue;
            }
            String redacted = pii.redact(node.getText());
            if (RewritePii.onlyPersonalDetails(redacted)) {
                continue;
            }
            result.add(new Editable(node.getNodeId(), node.getText(), redacted));
        }
        return result;
    }

    static boolean isEditable(String text) {
        if (text.isEmpty()) {
            return false;
        }
        long cjk = CJK.matcher(text).results().count();
        boolean longEnough = cjk >= 10 || (cjk == 0 && text.length() >= 30 && text.split("\\s+").length >= 5);
        if (!longEnough) {
            return false;
        }
        if (text.contains("|") || text.contains("\t")) {
            return false;
        }
        if (DATE_RANGE.matcher(text).find() || (MONTH_YEAR.matcher(text).find() && text.length() < 80)) {
            return false;
        }
        boolean heading = text.equals(text.toUpperCase()) && text.chars().anyMatch(Character::isLetter);
        return !heading;
    }

    /** Every node's text, joined -- the evidence a Pro rewrite may draw new facts from. */
    public static String allText(List<ResumeDocumentNode> nodes, RewritePii pii) {
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : nodes) {
            if (node != null && node.getText() != null) {
                text.append(pii.redact(node.getText())).append('\n');
            }
        }
        return text.toString();
    }
}
