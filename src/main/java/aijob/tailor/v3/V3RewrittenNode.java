package aijob.tailor.v3;

import java.util.List;

public record V3RewrittenNode(
        String nodeId,
        String newText,
        List<String> usedFactKeys,
        List<String> alignedRequirementIds,
        String improvementType,
        String explanation,
        List<String> citedSourceNodeIds
) {
    public V3RewrittenNode {
        nodeId = nodeId == null ? "" : nodeId;
        newText = newText == null ? "" : newText;
        usedFactKeys = List.of();
        alignedRequirementIds = alignedRequirementIds == null ? List.of() : List.copyOf(alignedRequirementIds);
        improvementType = improvementType == null ? "" : improvementType;
        explanation = explanation == null ? "" : explanation;
        citedSourceNodeIds = citedSourceNodeIds == null ? List.of() : List.copyOf(citedSourceNodeIds);
    }

    public V3RewrittenNode(
            String nodeId,
            String newText,
            List<String> usedFactKeys,
            List<String> alignedRequirementIds,
            String improvementType,
            String explanation) {
        this(nodeId, newText, usedFactKeys, alignedRequirementIds, improvementType, explanation, List.of());
    }

    public List<String> declaredFactKeys() {
        return List.of();
    }

    public List<String> improvementReasons() {
        if (explanation.isBlank() && improvementType.isBlank()) {
            return List.of();
        }
        if (explanation.isBlank()) {
            return List.of(improvementType);
        }
        if (improvementType.isBlank()) {
            return List.of(explanation);
        }
        return List.of(improvementType, explanation);
    }
}
