package aijob.tailor.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record V3EntryRewriteVariant(
        V3VariantType variantType,
        V3RewriteDecision decision,
        List<V3RewrittenNode> rewrittenNodes,
        String entrySummary,
        List<String> factsMoved,
        List<String> factsRetained,
        List<String> factsOmitted,
        V3SelfCheck selfCheck
) {
    public V3EntryRewriteVariant {
        variantType = variantType == null ? V3VariantType.CLARITY_FIRST : variantType;
        decision = decision == null ? V3RewriteDecision.NO_CHANGE : decision;
        rewrittenNodes = rewrittenNodes == null ? List.of() : List.copyOf(rewrittenNodes);
        entrySummary = entrySummary == null ? "" : entrySummary;
        factsMoved = factsMoved == null ? List.of() : List.copyOf(factsMoved);
        factsRetained = factsRetained == null ? List.of() : List.copyOf(factsRetained);
        factsOmitted = factsOmitted == null ? List.of() : List.copyOf(factsOmitted);
        selfCheck = selfCheck == null ? V3SelfCheck.empty() : selfCheck;
    }

    public static V3EntryRewriteVariant noChange(V3VariantType variantType) {
        return new V3EntryRewriteVariant(
                variantType,
                V3RewriteDecision.NO_CHANGE,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                V3SelfCheck.empty());
    }

    public List<String> bulletsInNodeOrder(List<V3WritableNode> originalOrder) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (V3RewrittenNode node : rewrittenNodes) {
            byId.put(node.nodeId(), node.newText());
        }
        List<String> bullets = new ArrayList<>();
        for (V3WritableNode original : originalOrder) {
            String text = byId.get(original.nodeId());
            bullets.add(text == null ? original.originalText() : text);
        }
        return bullets;
    }
}
