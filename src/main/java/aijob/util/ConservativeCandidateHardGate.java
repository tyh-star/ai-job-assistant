package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ordered T2C hard gates. Failed candidates never enter local ranking.
 * Model-supplied evidence fields are re-checked against the EvidencePacket.
 */
public final class ConservativeCandidateHardGate {

    private static final Pattern LEADERSHIP = Pattern.compile(
            "(?i)\\b(?:led|lead|directed|direct|managed|manage|oversaw|oversee|owned|own|headed|head|supervised|supervise)\\b"
    );

    private ConservativeCandidateHardGate() {
    }

    public static Result evaluate(
            ConservativeRewriteCandidate candidate,
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger,
            String jobDescription) {

        if (candidate == null || node == null || plan == null) {
            return Result.reject(RejectedTailorChange.EMPTY_REQUIRED_FIELD);
        }
        String newText = candidate.getNewText();
        if (newText == null || newText.isBlank()) {
            return Result.reject(RejectedTailorChange.EMPTY_REQUIRED_FIELD);
        }

        String targetId = plan.targetNodeId() == null || plan.targetNodeId().isBlank()
                ? plan.nodeId()
                : plan.targetNodeId();
        String claimedNodeId = candidate.getNodeId();
        if (claimedNodeId != null && !claimedNodeId.isBlank() && !targetId.equals(claimedNodeId)) {
            return Result.reject(RejectedTailorChange.UNKNOWN_NODE_ID);
        }

        ConservativeEvidencePacket resolvedPacket = packet == null
                ? ConservativeEvidencePacket.build(targetId, ledger)
                : packet;
        List<String> supporting = supportingIds(candidate);
        Set<String> packetSupporting = new LinkedHashSet<>(
                resolvedPacket.supportingNodeIds() == null ? List.of() : resolvedPacket.supportingNodeIds());
        for (String supportingId : supporting) {
            if (targetId.equals(supportingId)) {
                continue;
            }
            if (ledger == null || ledger.entryOf(supportingId) == null) {
                return Result.reject(RejectedTailorChange.UNKNOWN_EVIDENCE_NODE_ID);
            }
            if (!ledger.sameEntry(targetId, supportingId)) {
                return Result.reject(RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED);
            }
            if (!packetSupporting.contains(supportingId)) {
                return Result.reject(RejectedTailorChange.UNKNOWN_EVIDENCE_NODE_ID);
            }
        }

        int maxCharacters = plan.maxCharacters() > 0
                ? Math.min(plan.maxCharacters(), ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS)
                : ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS;
        if (newText.strip().length() > maxCharacters
                || newText.strip().length() > ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS) {
            return Result.reject(RejectedTailorChange.TEXT_TOO_LONG);
        }

        if (!factsCoveredByPacket(newText, node.getText(), resolvedPacket, ledger)
                || inventsUnsupportedClaim(node.getText(), newText, resolvedPacket, ledger)
                || inventsUnsupportedOutcome(node.getText(), newText, resolvedPacket, ledger)
                || inventsUnsupportedContext(node.getText(), newText, resolvedPacket, ledger)) {
            return Result.reject(RejectedTailorChange.UNSUPPORTED_CLAIM);
        }

        String copiedFact = SourceFactDuplicationGate.rejectReason(
                targetId, node.getText(), newText, ledger);
        if (copiedFact != null) {
            return Result.reject(copiedFact, supporting);
        }

        String relationReject = EvidenceRelationValidator.rejectReason(
                newText, node.getText(), evidenceTexts(node.getText(), resolvedPacket, ledger));
        if (relationReject != null) {
            return Result.reject(relationReject, supporting);
        }

        boolean leadershipCited = citedSameEntryLeadership(supporting, targetId, ledger);
        if (!leadershipCited && ConservativeRewriteQualityEvaluator.hasRoleEscalation(node.getText(), newText)) {
            return Result.reject(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, supporting);
        }
        String unsupportedAction = ActionProvenanceGate.rejectReason(node.getText(), newText);
        if (unsupportedAction != null) {
            return Result.reject(unsupportedAction, supporting);
        }
        if (!ConservativeEvidencePacket.coversRequiredFacts(newText, plan.requiredFacts())) {
            return Result.reject(RejectedTailorChange.INFORMATION_LOSS, supporting);
        }
        ConservativeRewriteQualityEvaluator.Verdict quality =
                ConservativeRewriteQualityEvaluator.evaluate(
                        node.getText(), newText, jobDescription, leadershipCited);
        if (!quality.accepted()) {
            return Result.reject(quality.reasonCode(), supporting);
        }
        List<String> resolvedSupporting = ConservativeCandidateIntent.resolveSupporting(
                node.getText(), newText, resolvedPacket, ledger);
        String intent = ConservativeCandidateIntent.classify(
                node.getText(),
                newText,
                resolvedSupporting,
                plan.rewriteIntent(),
                quality);
        if (RewriteIntent.LOW_VALUE_REWRITE.equals(intent)) {
            return Result.reject(RejectedTailorChange.LOW_VALUE_REWRITE, resolvedSupporting, quality, intent);
        }
        return Result.pass(resolvedSupporting, quality, leadershipCited, intent);
    }

    static List<String> supportingIds(ConservativeRewriteCandidate candidate) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> preferred = candidate.getUsedSupportingNodeIds();
        List<String> fallback = candidate.getUsedFactNodeIds();
        List<String> source = preferred != null && !preferred.isEmpty() ? preferred : fallback;
        if (source == null) {
            return List.of();
        }
        for (String nodeId : source) {
            if (nodeId != null && !nodeId.isBlank()) {
                ids.add(nodeId.strip());
            }
        }
        return List.copyOf(ids);
    }

    static boolean citedSameEntryLeadership(
            List<String> supportingIds,
            String targetId,
            ClaimLedger ledger) {

        if (supportingIds == null || ledger == null || targetId == null) {
            return false;
        }
        for (String supportingId : supportingIds) {
            if (supportingId == null || supportingId.equals(targetId) || !ledger.sameEntry(targetId, supportingId)) {
                continue;
            }
            String text = textOf(ledger, supportingId);
            if (LEADERSHIP.matcher(text == null ? "" : text).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean factsCoveredByPacket(
            String newText,
            String originalText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        StringBuilder allowed = new StringBuilder();
        if (originalText != null) {
            allowed.append(' ').append(originalText);
        }
        if (packet != null && packet.supportingNodeIds() != null && ledger != null) {
            for (String supportingId : packet.supportingNodeIds()) {
                allowed.append(' ').append(textOf(ledger, supportingId));
            }
        }
        Set<String> allowedTech = ClaimLedger.techTerms(allowed.toString());
        for (String term : ClaimLedger.techTerms(newText)) {
            if (!allowedTech.contains(term)) {
                return false;
            }
        }
        Set<String> allowedNumbers = ClaimLedger.numbers(allowed.toString());
        for (String number : ClaimLedger.numbers(newText)) {
            if (!allowedNumbers.contains(number)) {
                return false;
            }
        }
        return true;
    }

    private static final List<String> UNSUPPORTED_CLAIM_PHRASES = List.of(
            "high availability",
            "highly available",
            "scalability",
            "resilient infrastructure",
            "docker",
            "kubernetes",
            "ci/cd",
            "cicd"
    );

    static boolean inventsUnsupportedClaim(
            String originalText,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        String allowed = (originalText == null ? "" : originalText) + " "
                + allowedSupportingText(packet, ledger);
        String allowedLower = allowed.toLowerCase(Locale.ROOT);
        String rewritten = newText == null ? "" : newText.toLowerCase(Locale.ROOT);
        for (String phrase : UNSUPPORTED_CLAIM_PHRASES) {
            if (rewritten.contains(phrase) && !allowedLower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> UNSUPPORTED_OUTCOME_PHRASES = List.of(
            "quality improvement"
    );
    private static final Pattern UNSUPPORTED_OUTCOME_WORDS = Pattern.compile(
            "(?i)\\b(?:enhanc(?:e|ed|ing)|improv(?:e|ed|ing)|increas(?:e|ed|ing)|"
                    + "reduc(?:e|ed|ing)|strengthened|efficient(?:cy)?|"
                    + "optimiz(?:e|ed|ing|ation)|scalable|reliable|performance)\\b"
    );

    static boolean inventsUnsupportedOutcome(
            String originalText,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        String allowed = (originalText == null ? "" : originalText) + " "
                + allowedSupportingText(packet, ledger);
        String allowedLower = allowed.toLowerCase(Locale.ROOT);
        String rewritten = newText == null ? "" : newText.toLowerCase(Locale.ROOT);
        for (String phrase : UNSUPPORTED_OUTCOME_PHRASES) {
            if (rewritten.contains(phrase) && !allowedLower.contains(phrase)) {
                return true;
            }
        }
        var matcher = UNSUPPORTED_OUTCOME_WORDS.matcher(rewritten);
        while (matcher.find()) {
            String hit = matcher.group().toLowerCase(Locale.ROOT);
            if (!outcomeSupported(hit, allowedLower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean outcomeSupported(String hit, String allowedLower) {
        if (allowedLower.contains(hit)) {
            return true;
        }
        if (hit.startsWith("enhanc") && allowedLower.contains("enhance")) {
            return true;
        }
        if (hit.startsWith("improv") && allowedLower.contains("improv")) {
            return true;
        }
        if (hit.startsWith("increas") && allowedLower.contains("increas")) {
            return true;
        }
        if (hit.startsWith("reduc") && allowedLower.contains("reduc")) {
            return true;
        }
        if (hit.startsWith("strengthen") && allowedLower.contains("strengthen")) {
            return true;
        }
        if (hit.startsWith("efficient") && allowedLower.contains("efficient")) {
            return true;
        }
        if (hit.startsWith("optimiz") && allowedLower.contains("optimiz")) {
            return true;
        }
        return (hit.equals("scalable") && allowedLower.contains("scalab"))
                || (hit.equals("reliable") && allowedLower.contains("reliab"))
                || (hit.equals("performance") && allowedLower.contains("performance"));
    }

    private static final Pattern NOVEL_CONTEXT_CLAUSE = Pattern.compile(
            "(?i)\\b(?:based on|according to|in order to|to meet|ensuring|so that|in response to|aimed at)\\b[\\s\\-]*([\\w\\s'/-]{1,60})"
    );
    private static final Pattern VAGUE_PURPOSE_TAIL = Pattern.compile(
            "(?i)\\b(?:for\\s+structured\\s+architecture|for\\s+enhanced\\s+clarity|"
                    + "for\\s+improved\\s+efficiency|to\\s+ensure\\s+quality|"
                    + "to\\s+improve\\s+performance|enabling\\s+better\\b)"
    );
    private static final Set<String> CONTEXT_STOPWORDS = Set.of(
            "based", "on", "according", "to", "in", "order", "meet", "ensuring", "ensure",
            "so", "that", "response", "aimed", "at", "the", "a", "an", "and", "or", "for",
            "of", "with", "by", "from", "into", "their", "its"
    );

    static boolean inventsUnsupportedContext(
            String originalText,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        String rewritten = newText == null ? "" : newText;
        if (rewritten.isBlank()) {
            return false;
        }
        String originalLower = originalText == null ? "" : originalText.toLowerCase(Locale.ROOT);
        var vagueTail = VAGUE_PURPOSE_TAIL.matcher(rewritten);
        while (vagueTail.find()) {
            String span = vagueTail.group().toLowerCase(Locale.ROOT).strip();
            if (!originalLower.contains(span)) {
                return true;
            }
        }
        String allowed = ((originalText == null ? "" : originalText) + " "
                + allowedSupportingText(packet, ledger)).toLowerCase(Locale.ROOT);
        var matcher = NOVEL_CONTEXT_CLAUSE.matcher(rewritten);
        while (matcher.find()) {
            String clause = matcher.group().toLowerCase(Locale.ROOT);
            if (allowed.contains(clause.strip())) {
                continue;
            }
            List<String> distinctive = distinctiveContextTokens(matcher.group(1));
            if (distinctive.isEmpty()) {
                return true;
            }
            for (String token : distinctive) {
                if (!contextTokenSupported(token, allowed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> distinctiveContextTokens(String clauseObject) {
        List<String> tokens = new ArrayList<>();
        if (clauseObject == null) {
            return tokens;
        }
        for (String raw : clauseObject.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 3 || CONTEXT_STOPWORDS.contains(raw)) {
                continue;
            }
            tokens.add(raw);
        }
        return tokens;
    }

    private static boolean contextTokenSupported(String token, String allowedLower) {
        if (allowedLower.contains(token)) {
            return true;
        }
        if (token.endsWith("s") && token.length() > 3 && allowedLower.contains(token.substring(0, token.length() - 1))) {
            return true;
        }
        return allowedLower.contains(token + "s");
    }

    private static List<String> evidenceTexts(
            String targetText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        List<String> texts = new ArrayList<>();
        if (targetText != null && !targetText.isBlank()) {
            texts.add(targetText);
        }
        if (packet == null || packet.supportingNodeIds() == null || ledger == null) {
            return texts;
        }
        for (String supportingId : packet.supportingNodeIds()) {
            String supportingText = textOf(ledger, supportingId);
            if (supportingText != null && !supportingText.isBlank()) {
                texts.add(supportingText);
            }
        }
        return texts;
    }

    private static String allowedSupportingText(ConservativeEvidencePacket packet, ClaimLedger ledger) {
        if (packet == null || packet.supportingNodeIds() == null || ledger == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String supportingId : packet.supportingNodeIds()) {
            text.append(' ').append(textOf(ledger, supportingId));
        }
        return text.toString();
    }

    public static String textOf(ClaimLedger ledger, String nodeId) {
        if (ledger == null || nodeId == null) {
            return "";
        }
        for (ResumeDocumentNode node : ledger.nodesInEntry(nodeId)) {
            if (nodeId.equals(node.getNodeId()) && node.getText() != null) {
                return node.getText();
            }
        }
        return "";
    }

    public record Result(
            boolean passed,
            String reasonCode,
            List<String> usedSupportingNodeIds,
            ConservativeRewriteQualityEvaluator.Verdict quality,
            boolean leadershipCited,
            String classifiedIntent
    ) {
        static Result pass(
                List<String> usedSupportingNodeIds,
                ConservativeRewriteQualityEvaluator.Verdict quality,
                boolean leadershipCited,
                String classifiedIntent) {
            return new Result(true, null, usedSupportingNodeIds, quality, leadershipCited, classifiedIntent);
        }

        static Result reject(String reasonCode) {
            return new Result(false, reasonCode, List.of(), null, false, "");
        }

        static Result reject(String reasonCode, List<String> usedSupportingNodeIds) {
            return new Result(false, reasonCode, usedSupportingNodeIds, null, false, "");
        }

        static Result reject(
                String reasonCode,
                List<String> usedSupportingNodeIds,
                ConservativeRewriteQualityEvaluator.Verdict quality,
                String classifiedIntent) {
            return new Result(false, reasonCode, usedSupportingNodeIds, quality, false, classifiedIntent);
        }
    }
}
