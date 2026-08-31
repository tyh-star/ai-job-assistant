package aijob.util;

import aijob.dto.RewriteIntent;
import aijob.dto.UpliftType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side rewrite intent. Model improvementIntent is ignored.
 */
public final class ConservativeCandidateIntent {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");
    private static final Set<String> SOFT_SKILL = Set.of(
            "communication", "communicated", "teamwork", "passionate", "dedicated",
            "motivated", "learner", "interpersonal", "collaboration", "collaborated"
    );

    private ConservativeCandidateIntent() {
    }

    public static String classify(
            String originalText,
            String newText,
            List<String> resolvedSupportingNodeIds,
            String planIntent,
            ConservativeRewriteQualityEvaluator.Verdict quality) {

        if (resolvedSupportingNodeIds != null && !resolvedSupportingNodeIds.isEmpty()) {
            return RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT;
        }
        if (RewriteIntent.SUMMARY_REWRITE.equals(planIntent)) {
            return RewriteIntent.SUMMARY_REWRITE;
        }
        if (ConservativeCandidateLocalScorer.isLowValueSynonymOnly(originalText, newText)
                || ConservativeCandidateLocalScorer.isVagueTailOnly(originalText, newText)
                || SurfaceRewriteEquivalence.isLowValueSurface(originalText, newText)) {
            return RewriteIntent.LOW_VALUE_REWRITE;
        }
        if (ConservativeRewriteQualityEvaluator.isGrammarOnly(originalText, newText)
                || (quality != null && UpliftType.GRAMMAR_FIX.equals(quality.upliftType()))) {
            return RewriteIntent.GRAMMAR_FIX;
        }
        if (ConservativeCandidateLocalScorer.isHighOverlapNoNewFacts(originalText, newText)
                && !isTenseOnly(originalText, newText)) {
            return RewriteIntent.LOW_VALUE_REWRITE;
        }
        return RewriteIntent.CLARITY_IMPROVEMENT;
    }

    public static List<String> resolveSupporting(
            String originalText,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        LinkedHashSet<String> used = new LinkedHashSet<>();
        if (packet == null || packet.supportingNodeIds() == null || ledger == null) {
            return List.of();
        }
        Set<String> newTokens = tokens(newText);
        Set<String> originalTech = ConservativeEvidencePacket.extractNormalizedTech(originalText);
        for (String supportingId : packet.supportingNodeIds()) {
            String supportingText = ConservativeCandidateHardGate.textOf(ledger, supportingId);
            Set<String> supportingTech = ConservativeEvidencePacket.extractNormalizedTech(supportingText);
            boolean usedTech = false;
            for (String tech : supportingTech) {
                if (!originalTech.contains(tech)
                        && !ConservativeMaterialFacts.isGenericToken(tech)
                        && (newTokens.contains(tech)
                        || ConservativeMaterialFacts.containsAlias(normalize(newText), tech))) {
                    usedTech = true;
                    break;
                }
            }
            boolean usedDuty = ConservativeMaterialFacts.contributesConcreteFact(
                    originalText, supportingText, newText);
            if (usedTech || usedDuty) {
                used.add(supportingId);
            }
        }
        return List.copyOf(used);
    }

    public static boolean isSoftSkillSummary(String newText) {
        Set<String> tokens = tokens(newText);
        int soft = 0;
        for (String token : tokens) {
            if (SOFT_SKILL.contains(token)) {
                soft++;
            }
        }
        Set<String> tech = ConservativeEvidencePacket.extractNormalizedTech(newText);
        return soft >= 2 && tech.isEmpty();
    }

    public static boolean isTenseOnly(String originalText, String newText) {
        List<String> left = stemmedContent(originalText);
        List<String> right = stemmedContent(newText);
        return !left.isEmpty() && left.equals(right)
                && !normalize(originalText).equals(normalize(newText));
    }

    private static List<String> stemmedContent(String text) {
        List<String> stems = new ArrayList<>();
        for (String token : ConservativeCandidateLocalScorer.tokens(text)) {
            if (token.length() < 3) {
                continue;
            }
            stems.add(stem(token));
        }
        return stems;
    }

    private static String stem(String token) {
        if (token.endsWith("ing") && token.length() > 5) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        return token;
    }

    static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        var matcher = TOKEN.matcher(normalize(text));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#/\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
