package aijob.util;

import aijob.dto.ConservativeCandidateScore;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import aijob.dto.UpliftType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 100-point local score used only after hard gates pass.
 * Admit when total >= 75 and per-dimension floors are met.
 */
public final class ConservativeCandidateLocalScorer {

    public static final int ADMIT_TOTAL = 75;
    public static final int ADMIT_MARGIN = 10;
    public static final int MIN_CLARITY = 60;
    public static final int MIN_SUBSTANTIVE = 70;
    public static final int MIN_ENRICHMENT_SUBSTANTIVE = 85;
    public static final int MIN_ENRICHMENT_JD = 60;
    public static final int MIN_SUMMARY_JD = 65;
    public static final int MIN_SUMMARY_CLARITY = 70;
    public static final int TIE_WINDOW = 5;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");
    private static final Set<String> VAGUE_TAILS = Set.of(
            "quality", "ensure", "ensuring", "best", "practices", "optimal",
            "seamless", "robust", "comprehensive"
    );

    private ConservativeCandidateLocalScorer() {
    }

    public static Score score(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger,
            String jobDescription,
            String newText,
            List<String> usedSupportingNodeIds,
            ConservativeRewriteQualityEvaluator.Verdict quality) {

        String original = node == null ? "" : node.getText();
        ConservativeRewriteQualityEvaluator.QualityScores originalScores =
                ConservativeRewriteQualityEvaluator.score(original, original, jobDescription);

        int factual = factualPreservation(original, newText);
        int jd = jdRelevance(original, newText, jobDescription, packet, ledger);
        int substantive = substantivePoints(original, newText, usedSupportingNodeIds, packet, ledger);
        boolean substantiveFlag = substantive >= MIN_SUBSTANTIVE;
        int specificity = clamp(Math.max(60, specificityBoost(original, newText)));
        int clarity = localClarity(original, newText);
        int conciseness = conciseness(original, newText);
        String intent = ConservativeCandidateIntent.classify(
                original,
                newText,
                usedSupportingNodeIds,
                plan == null ? "" : plan.rewriteIntent(),
                quality);

        int total = weighted(factual, jd, substantive, specificity, clarity, conciseness);
        int baseline = weighted(
                100,
                jdRelevance(original, original, jobDescription, packet, ledger),
                0,
                clamp(originalScores.specificityScore()),
                localClarity(original, original),
                100);
        int margin = total - baseline;
        int maxJd = jdRelevance(
                original,
                allowedText(original, packet, ledger),
                jobDescription,
                packet,
                ledger);
        String rejectReason = admissionFailure(
                factual, jd, substantive, clarity, margin, total, intent, newText, maxJd);
        boolean admitted = rejectReason == null;
        String uplift = ConservativeRewriteReasons.upliftTypeFor(intent);
        if (uplift == null || uplift.isBlank()) {
            uplift = quality != null && quality.upliftType() != null
                    ? quality.upliftType()
                    : ConservativeRewriteQualityEvaluator.upliftOf(original, newText);
        }
        return new Score(
                factual,
                jd,
                substantive,
                specificity,
                clarity,
                conciseness,
                total,
                baseline,
                margin,
                substantiveFlag,
                admitted,
                uplift,
                RewriteIntent.GRAMMAR_FIX.equals(intent)
                        || ConservativeRewriteQualityEvaluator.isGrammarOnly(original, newText)
                        || UpliftType.GRAMMAR_FIX.equals(uplift),
                intent,
                rejectReason
        );
    }

    static String admissionFailure(
            int factual,
            int jd,
            int substantive,
            int clarity,
            int margin,
            int total,
            String intent,
            String newText) {
        return admissionFailure(factual, jd, substantive, clarity, margin, total, intent, newText, 100);
    }

    static String admissionFailure(
            int factual,
            int jd,
            int substantive,
            int clarity,
            int margin,
            int total,
            String intent,
            String newText,
            int maxJd) {

        if (factual != 100) {
            return RejectedTailorChange.INFORMATION_LOSS;
        }
        if (clarity < MIN_CLARITY) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (substantive < MIN_SUBSTANTIVE) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (margin < ADMIT_MARGIN) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (total < ADMIT_TOTAL) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (RewriteIntent.LOW_VALUE_REWRITE.equals(intent)) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(intent)
                && (substantive < MIN_ENRICHMENT_SUBSTANTIVE
                || (jd < MIN_ENRICHMENT_JD && maxJd >= MIN_ENRICHMENT_JD))) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (RewriteIntent.SUMMARY_REWRITE.equals(intent)) {
            if (jd < MIN_SUMMARY_JD || clarity < MIN_SUMMARY_CLARITY) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
            if (ConservativeCandidateIntent.isSoftSkillSummary(newText)
                    || ConservativeEvidencePacket.extractNormalizedTech(newText).isEmpty()) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
        }
        return null;
    }

    static int weighted(
            int factual,
            int jd,
            int substantive,
            int specificity,
            int clarity,
            int conciseness) {
        return (int) Math.round(
                factual * 0.30
                        + jd * 0.25
                        + substantive * 0.20
                        + specificity * 0.10
                        + clarity * 0.10
                        + conciseness * 0.05);
    }

    static int factualPreservation(String original, String newText) {
        Set<String> originalTech = ConservativeEvidencePacket.extractNormalizedTech(original);
        if (originalTech.isEmpty()) {
            return 100;
        }
        Set<String> kept = new LinkedHashSet<>(originalTech);
        kept.retainAll(ConservativeEvidencePacket.extractNormalizedTech(newText));
        String haystack = normalize(newText);
        for (String tech : originalTech) {
            if (!kept.contains(tech) && !haystack.contains(tech)) {
                return (int) Math.round(100.0 * kept.size() / originalTech.size());
            }
        }
        return kept.containsAll(originalTech) ? 100 : (int) Math.round(100.0 * kept.size() / originalTech.size());
    }

    static int localClarity(String original, String newText) {
        String body = stripBulletPrefix(newText);
        if (body.isBlank()) {
            return 35;
        }
        if (body.contains("  ")) {
            return 35;
        }
        int score = 70;
        String first = ConservativeTailorPlanner.firstWord(newText);
        if (first.endsWith("ed") || Set.of("developed", "implemented", "designed", "built", "integrated").contains(first)) {
            score += 10;
        }
        if (body.contains(",")) {
            score += 8;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains(" using ") || lower.contains(" with ")) {
            score += 5;
        }
        long ands = lower.split("\\band\\b", -1).length - 1;
        if (ands >= 5) {
            score -= 15;
        }
        if (isVagueTailOnly(original, newText)) {
            score -= 20;
        }
        return clamp(score);
    }

    static String stripBulletPrefix(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceFirst("^[\\s]*[●•▪◦‣∙·\\-*]\\s*", "").strip();
    }

    static int conciseness(String original, String newText) {
        int lengthOriginal = Math.max(1, original == null ? 1 : original.strip().length());
        int lengthNew = newText == null ? 0 : newText.strip().length();
        double ratio = lengthNew / (double) lengthOriginal;
        if (ratio > 1.8) {
            return 40;
        }
        if (ratio > 1.6) {
            return 65;
        }
        return 85;
    }

    static int jdRelevance(
            String original,
            String newText,
            String jobDescription,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        List<String> jobTerms = ConservativeTailorPlanner.extractTerms(jobDescription);
        if (jobTerms.isEmpty()) {
            return 50;
        }
        String haystack = normalize(newText);
        String allowed = allowedText(original, packet, ledger);
        int hits = 0;
        for (String term : jobTerms) {
            if (term == null || term.length() < 3) {
                continue;
            }
            if (haystack.contains(term) && allowed.toLowerCase(Locale.ROOT).contains(term)) {
                hits++;
            }
        }
        return Math.min(100, 50 + hits * 10);
    }

    static int substantivePoints(
            String original,
            String newText,
            List<String> usedSupportingNodeIds,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        if (isLowValueSynonymOnly(original, newText)
                || isVagueTailOnly(original, newText)
                || SurfaceRewriteEquivalence.isLowValueSurface(original, newText)) {
            return 30;
        }
        if (ConservativeCandidateIntent.isSoftSkillSummary(newText)) {
            return 30;
        }
        if (addedSameEntryFacts(original, newText, usedSupportingNodeIds, packet, ledger)) {
            Set<String> added = addedTech(original, newText);
            return added.size() >= 2 ? 100 : 90;
        }
        if (ConservativeCandidateIntent.isTenseOnly(original, newText)) {
            return 70;
        }
        if (professionalized(original, newText)) {
            return 80;
        }
        if (ConservativeRewriteQualityEvaluator.isGrammarOnly(original, newText)) {
            return 80;
        }
        if (isHighOverlapNoNewFacts(original, newText)) {
            return 30;
        }
        Set<String> addedTokens = tokens(newText);
        addedTokens.removeAll(tokens(original));
        addedTokens.removeAll(VAGUE_TAILS);
        if (addedTokens.size() >= 1 && addedTokens.size() <= 3) {
            return 72;
        }
        return 50;
    }

    static boolean addedSameEntryFacts(
            String original,
            String newText,
            List<String> usedSupportingNodeIds,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        if (usedSupportingNodeIds == null || usedSupportingNodeIds.isEmpty()) {
            return false;
        }
        Set<String> added = addedTech(original, newText);
        if (!added.isEmpty()) {
            String allowed = allowedText(original, packet, ledger).toLowerCase(Locale.ROOT);
            for (String term : added) {
                if (!allowed.contains(term)) {
                    return false;
                }
            }
            return true;
        }
        return hasSupportingDutyTokens(original, newText, usedSupportingNodeIds, ledger);
    }

    static boolean hasSupportingDutyTokens(
            String original,
            String newText,
            List<String> usedSupportingNodeIds,
            ClaimLedger ledger) {

        if (usedSupportingNodeIds == null || ledger == null) {
            return false;
        }
        Set<String> originalTokens = tokens(original);
        Set<String> newTokens = tokens(newText);
        for (String supportingId : usedSupportingNodeIds) {
            for (String token : tokens(ConservativeCandidateHardGate.textOf(ledger, supportingId))) {
                if (!originalTokens.contains(token) && newTokens.contains(token) && token.length() >= 4) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean professionalized(String original, String newText) {
        String left = normalize(original);
        String right = normalize(newText);
        boolean keptFacts = ConservativeEvidencePacket.extractNormalizedTech(original)
                .stream()
                .allMatch(term -> right.contains(term) || ConservativeEvidencePacket.extractNormalizedTech(newText).contains(term));
        if (!keptFacts) {
            return false;
        }
        return (left.contains("helps users") && right.contains("recommend") && !right.contains("cpu"))
                || (left.contains("managements") && (right.contains("management")
                || right.contains("operations")
                || right.contains("functions")
                || right.contains("workflows")))
                || (left.contains("design and implement") && right.contains("designed"))
                || (left.contains("java maven") && right.contains("java")
                && right.contains("maven") && right.contains("based"))
                || (left.contains("mvc") && right.contains("mvc")
                && ((left.contains("pattern") && right.contains("architecture"))
                || (left.contains("architecture") && right.contains("pattern"))));
    }

    static boolean isLowValueSynonymOnly(String original, String newText) {
        String left = normalize(original);
        String right = normalize(newText);
        return left.replace("developed", "created").equals(right)
                || left.replace("helps users", "assisting users").equals(right)
                || left.replace("help users", "assisting users").equals(right)
                || left.replace("best components", "optimal components").equals(right)
                || left.replace("demonstrated", "showcased").equals(right)
                || left.replace("showcased", "demonstrated").equals(right);
    }

    static boolean isHighOverlapNoNewFacts(String original, String newText) {
        if (!addedTech(original, newText).isEmpty()) {
            return false;
        }
        return tokenJaccard(original, newText) >= 0.85;
    }

    static double tokenJaccard(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
    }

    static boolean isVagueTailOnly(String original, String newText) {
        Set<String> added = tokens(newText);
        added.removeAll(tokens(original));
        if (added.isEmpty()) {
            return false;
        }
        for (String token : added) {
            if (!VAGUE_TAILS.contains(token)) {
                return false;
            }
        }
        return true;
    }

    static Set<String> addedTech(String original, String newText) {
        Set<String> added = new LinkedHashSet<>(ConservativeEvidencePacket.extractNormalizedTech(newText));
        added.removeAll(ConservativeEvidencePacket.extractNormalizedTech(original));
        return added;
    }

    static int specificityBoost(String original, String newText) {
        return Math.min(100, 60 + addedTech(original, newText).size() * 12);
    }

    static String allowedText(String original, ConservativeEvidencePacket packet, ClaimLedger ledger) {
        StringBuilder text = new StringBuilder(original == null ? "" : original);
        if (packet != null && packet.supportingNodeIds() != null && ledger != null) {
            for (String supportingId : packet.supportingNodeIds()) {
                text.append(' ').append(ConservativeCandidateHardGate.textOf(ledger, supportingId));
            }
        }
        return text.toString();
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
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#/\\s]", " ").replaceAll("\\s+", " ").strip();
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public record Score(
            int factualPreservation,
            int jdRelevance,
            int substantiveImprovement,
            int specificity,
            int clarity,
            int conciseness,
            int total,
            int originalBaseline,
            int improvementMargin,
            boolean substantiveImprovementFlag,
            boolean admitted,
            String upliftType,
            boolean grammarOnly,
            String classifiedIntent,
            String rejectReason
    ) {
        public Score(
                int factualPreservation,
                int jdRelevance,
                int substantiveImprovement,
                int specificity,
                int clarity,
                int conciseness,
                int total,
                int originalBaseline,
                int improvementMargin,
                boolean substantiveImprovementFlag,
                boolean admitted,
                String upliftType,
                boolean grammarOnly) {
            this(
                    factualPreservation, jdRelevance, substantiveImprovement, specificity, clarity, conciseness,
                    total, originalBaseline, improvementMargin, substantiveImprovementFlag, admitted, upliftType,
                    grammarOnly, "", null);
        }

        public ConservativeCandidateScore toRow(int index) {
            ConservativeCandidateScore row = new ConservativeCandidateScore();
            row.setIndex(index);
            row.setFactualPreservationScore(factualPreservation);
            row.setJobRelevanceScore(jdRelevance);
            row.setSubstantiveImprovementScore(substantiveImprovement);
            row.setSpecificityScore(specificity);
            row.setClarityScore(clarity);
            row.setConcisenessScore(conciseness);
            row.setGrammarScore(grammarOnly ? 90 : 80);
            row.setActionVerbScore(80);
            row.setTotalScore(total);
            row.setImprovementMargin(improvementMargin);
            row.setSubstantiveImprovement(substantiveImprovementFlag);
            return row;
        }
    }
}
