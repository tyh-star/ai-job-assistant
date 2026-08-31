package aijob.tailor.v3;

import aijob.dto.TailorChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Equal-count nodeId adapter for the existing Writer. Does not add, delete, or
 * reorder paragraphs. Headings and dates are never emitted.
 */
public final class V3EqualCountChangeAdapter {

    private V3EqualCountChangeAdapter() {
    }

    public static List<TailorChange> toChanges(
            V3EntryRewriteVariant variant,
            List<V3WritableNode> writableNodes) {
        if (variant == null || variant.decision() != V3RewriteDecision.REWRITE) {
            return List.of();
        }
        return toChanges(variant.rewrittenNodes(), writableNodes);
    }

    public static List<TailorChange> toChanges(
            List<V3RewrittenNode> rewrittenNodes,
            List<V3WritableNode> writableNodes) {
        if (rewrittenNodes == null || writableNodes == null || writableNodes.isEmpty()) {
            return List.of();
        }
        Map<String, String> rewritten = new LinkedHashMap<>();
        for (V3RewrittenNode node : rewrittenNodes) {
            if (node != null && !node.nodeId().isBlank()) {
                rewritten.put(node.nodeId(), node.newText() == null ? "" : node.newText().strip());
            }
        }
        List<TailorChange> changes = new ArrayList<>();
        for (V3WritableNode original : writableNodes) {
            String next = rewritten.get(original.nodeId());
            if (next == null || next.isBlank()) {
                continue;
            }
            if (next.equals(original.originalText())) {
                continue;
            }
            TailorChange change = new TailorChange();
            change.setNodeId(original.nodeId());
            change.setOriginalText(original.originalText());
            change.setNewText(next);
            change.setApplied(true);
            change.setRequiresConfirmation(false);
            change.setEvidenceType(TailorChange.EVIDENCE_VERIFIED);
            change.setEvidenceNodeIds(List.of(original.nodeId()));
            change.setEvidenceQuote(trimQuote(original.originalText()));
            change.setUpliftType("SUBSTANTIVE_REWRITE");
            change.setOpportunityType("CLARITY");
            change.setReason("V3 BASIC entry rewrite");
            change.setReasonZh("V3 BASIC 条目改写");
            change.setImprovementSummaryEn("Coordinated entry rewrite for the job description");
            change.setImprovementSummaryZh("按职位描述协调改写整条经历");
            changes.add(change);
        }
        return changes;
    }

    private static String trimQuote(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80);
    }
}
