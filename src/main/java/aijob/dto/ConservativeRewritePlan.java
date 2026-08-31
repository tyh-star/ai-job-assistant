package aijob.dto;

import aijob.util.ConservativeEvidencePacket;

import java.util.List;

/**
 * Internal CONSERVATIVE rewrite plan. Does not contain newText.
 */
public record ConservativeRewritePlan(
        String nodeId,
        String targetNodeId,
        String entryKey,
        List<String> matchedRequirementIds,
        String rewriteIntent,
        List<String> requiredFacts,
        List<String> availableSupportingFacts,
        List<String> supportingNodeIds,
        List<String> forbiddenClaims,
        List<String> relevantJobTerms,
        String rewriteGoal,
        int maxCharacters,
        String nodeRole,
        List<String> allowedFactNodeIds,
        String allowedFactsText,
        int rankingScore,
        String rankingReason
) {
    public ConservativeRewritePlan {
        targetNodeId = blankTo(targetNodeId, nodeId);
        entryKey = entryKey == null ? "" : entryKey;
        matchedRequirementIds = copy(matchedRequirementIds);
        rewriteIntent = rewriteIntent == null ? RewriteIntent.CLARITY_IMPROVEMENT : rewriteIntent;
        requiredFacts = copy(requiredFacts);
        availableSupportingFacts = copy(availableSupportingFacts);
        supportingNodeIds = copy(supportingNodeIds);
        forbiddenClaims = copy(forbiddenClaims);
        relevantJobTerms = copy(relevantJobTerms);
        rewriteGoal = rewriteGoal == null ? "" : rewriteGoal;
        nodeRole = nodeRole == null ? "" : nodeRole;
        allowedFactNodeIds = copy(allowedFactNodeIds);
        allowedFactsText = allowedFactsText == null ? "" : allowedFactsText;
        rankingReason = rankingReason == null ? "" : rankingReason;
    }

    public ConservativeRewritePlan(
            String nodeId,
            List<String> relevantJobTerms,
            String rewriteGoal,
            int maxCharacters,
            String nodeRole) {
        this(
                nodeId,
                nodeId,
                "",
                List.of(),
                RewriteIntent.CLARITY_IMPROVEMENT,
                List.of(),
                List.of(),
                List.of(),
                ConservativeEvidencePacket.DEFAULT_FORBIDDEN_CLAIMS,
                relevantJobTerms,
                rewriteGoal,
                maxCharacters,
                nodeRole,
                List.of(),
                "",
                0,
                ""
        );
    }

    public ConservativeRewritePlan(
            String nodeId,
            List<String> relevantJobTerms,
            String rewriteGoal,
            int maxCharacters,
            String nodeRole,
            List<String> allowedFactNodeIds,
            String allowedFactsText) {
        this(
                nodeId,
                nodeId,
                "",
                List.of(),
                RewriteIntent.CLARITY_IMPROVEMENT,
                List.of(),
                List.of(),
                allowedFactNodeIds,
                ConservativeEvidencePacket.DEFAULT_FORBIDDEN_CLAIMS,
                relevantJobTerms,
                rewriteGoal,
                maxCharacters,
                nodeRole,
                allowedFactNodeIds,
                allowedFactsText,
                0,
                ""
        );
    }

    public ConservativeRewritePlan withPacket(ConservativeEvidencePacket packet) {
        if (packet == null) {
            return this;
        }
        return new ConservativeRewritePlan(
                nodeId,
                packet.targetNodeId(),
                packet.entryKey() == null || packet.entryKey().isBlank() ? entryKey : packet.entryKey(),
                matchedRequirementIds,
                rewriteIntent,
                packet.requiredFacts(),
                packet.availableSupportingFacts(),
                packet.supportingNodeIds(),
                packet.forbiddenClaims(),
                relevantJobTerms,
                rewriteGoal,
                packet.maxCharacters() > 0 ? packet.maxCharacters() : maxCharacters,
                nodeRole,
                packet.allowedFactNodeIds(),
                packet.toPromptBlock(),
                rankingScore,
                rankingReason
        );
    }

    public ConservativeRewritePlan withRankingReason(String rankingReason) {
        return new ConservativeRewritePlan(
                nodeId,
                targetNodeId,
                entryKey,
                matchedRequirementIds,
                rewriteIntent,
                requiredFacts,
                availableSupportingFacts,
                supportingNodeIds,
                forbiddenClaims,
                relevantJobTerms,
                rewriteGoal,
                maxCharacters,
                nodeRole,
                allowedFactNodeIds,
                allowedFactsText,
                rankingScore,
                rankingReason == null ? "" : rankingReason
        );
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
