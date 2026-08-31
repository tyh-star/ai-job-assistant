package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;

import java.util.List;
import java.util.Set;

/**
 * Planner targets that still have independent rewrite value stay reserved.
 * PARTIALLY_USED reserved nodes must not donate optional facts to another suggestion.
 */
public final class ConservativeReservedTargetPolicy {

    private ConservativeReservedTargetPolicy() {
    }

    public static String absorbReason(
            String targetNodeId,
            String targetOriginal,
            String newText,
            Set<String> reservedTargetNodeIds,
            List<ResumeDocumentNode> nodes) {

        if (newText == null || newText.isBlank()
                || reservedTargetNodeIds == null || reservedTargetNodeIds.isEmpty()) {
            return null;
        }
        for (String reservedId : reservedTargetNodeIds) {
            if (reservedId == null || reservedId.isBlank() || reservedId.equals(targetNodeId)) {
                continue;
            }
            String reservedOriginal = textOf(nodes, reservedId);
            if (reservedOriginal == null || reservedOriginal.isBlank()) {
                continue;
            }
            if (!ConservativeMaterialFacts.contributesConcreteFact(targetOriginal, reservedOriginal, newText)) {
                continue;
            }
            if (ConservativeMaterialFacts.fullySubsumes(reservedOriginal, newText)) {
                continue;
            }
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        return null;
    }

    private static String textOf(List<ResumeDocumentNode> nodes, String nodeId) {
        if (nodes == null || nodeId == null) {
            return "";
        }
        for (ResumeDocumentNode node : nodes) {
            if (node != null && nodeId.equals(node.getNodeId())) {
                return node.getText();
            }
        }
        return "";
    }
}
