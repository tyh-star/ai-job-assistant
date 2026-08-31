package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a candidate actually resolves the Planner-marked opportunity
 * after fact hard gates and before local scoring / Judge.
 */
public final class OpportunityResolutionGate {

    private static final Pattern MANAGEMENTS = Pattern.compile("(?i)\\bmanagements\\b");
    private static final Pattern JAVA_DOC_STRING = Pattern.compile("(?i)\\bjava doc string\\b");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");
    private static final Pattern COMPLETED_OUTCOME = Pattern.compile(
            "(?i)\\b(?:successfully|delivered|launched|released|shipped|completed|"
                    + "deployed|resulting|reduced|increased|achieved)\\b"
    );
    private static final Pattern COLLAB_PARAPHRASE = Pattern.compile(
            "(?i)\\b(?:teamed(?:\\s+up)?|worked with|in collaboration)\\b"
    );
    private static final Pattern ORIGINAL_COLLAB = Pattern.compile(
            "(?i)\\b(?:lead|led|collaborate|collaborated|friend)\\b"
    );
    private static final Pattern COMMUNICATION = Pattern.compile("(?i)\\bcommunicat");
    private static final Set<String> ADVERB_POLISH = Set.of(
            "effectively", "clearly", "professionally", "concisely", "strongly", "successfully"
    );

    private static final Set<String> PRESENT_DUTY = Set.of(
            "design", "implement", "develop", "build", "integrate", "create", "deploy",
            "lead", "collaborate", "define", "maintain", "manage", "write", "support", "work"
    );
    private static final Set<String> PAST_DUTY = Set.of(
            "designed", "implemented", "developed", "built", "integrated", "created", "deployed",
            "led", "collaborated", "defined", "maintained", "managed", "wrote", "supported", "worked"
    );
    private static final Map<String, String> TENSE_STEM = Map.ofEntries(
            Map.entry("design", "design"),
            Map.entry("designed", "design"),
            Map.entry("implement", "implement"),
            Map.entry("implemented", "implement"),
            Map.entry("develop", "develop"),
            Map.entry("developed", "develop"),
            Map.entry("build", "build"),
            Map.entry("built", "build"),
            Map.entry("integrate", "integrate"),
            Map.entry("integrated", "integrate"),
            Map.entry("create", "create"),
            Map.entry("created", "create"),
            Map.entry("deploy", "deploy"),
            Map.entry("deployed", "deploy"),
            Map.entry("lead", "lead"),
            Map.entry("led", "lead"),
            Map.entry("collaborate", "collaborate"),
            Map.entry("collaborated", "collaborate"),
            Map.entry("define", "define"),
            Map.entry("defined", "define"),
            Map.entry("work", "work"),
            Map.entry("worked", "work"),
            Map.entry("team", "team"),
            Map.entry("teamed", "team")
    );
    private static final Map<String, String> SYNONYM_STEM = Map.ofEntries(
            Map.entry("communication", "communicate"),
            Map.entry("communications", "communicate"),
            Map.entry("communicated", "communicate"),
            Map.entry("communicating", "communicate"),
            Map.entry("interpersonal", "communicate"),
            Map.entry("verbal", "communicate"),
            Map.entry("help", "help"),
            Map.entry("helps", "help"),
            Map.entry("helping", "help"),
            Map.entry("assist", "help"),
            Map.entry("assists", "help"),
            Map.entry("assisting", "help"),
            Map.entry("assisted", "help"),
            Map.entry("enable", "help"),
            Map.entry("enables", "help"),
            Map.entry("enabling", "help"),
            Map.entry("enabled", "help"),
            Map.entry("demonstrate", "show"),
            Map.entry("demonstrated", "show"),
            Map.entry("showcase", "show"),
            Map.entry("showcased", "show"),
            Map.entry("collaborate", "collaborate"),
            Map.entry("collaborated", "collaborate"),
            Map.entry("collaboration", "collaborate"),
            Map.entry("team", "collaborate"),
            Map.entry("teamed", "collaborate")
    );
    private static final Set<String> FILLER = Set.of(
            "a", "an", "the", "and", "to", "of", "for", "with", "in", "on", "that", "which"
    );
    private static final List<String[]> CARDINALITY = List.of(
            new String[] {"application", "applications"},
            new String[] {"api", "apis"},
            new String[] {"project", "projects"}
    );

    private OpportunityResolutionGate() {
    }

    public static String rejectReason(
            String originalText,
            String newText,
            ConservativeRewritePlan plan,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        if (originalText == null || newText == null || originalText.isBlank() || newText.isBlank()) {
            return null;
        }
        String opportunity = plan == null || plan.rankingReason() == null
                ? ""
                : plan.rankingReason().strip();
        String nodeId = plan == null ? null : plan.targetNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = plan == null ? null : plan.nodeId();
        }

        String grammarReject = grammarOpportunity(originalText, newText, opportunity);
        if (grammarReject != null) {
            return grammarReject;
        }
        if (cardinalityChanged(originalText, newText, packet, ledger)) {
            return RejectedTailorChange.FACT_CARDINALITY_CHANGED;
        }
        String tenseReject = tenseOpportunity(originalText, newText, opportunity, nodeId, ledger);
        if (tenseReject != null) {
            return tenseReject;
        }
        if (isClarityOrProfessional(opportunity) && isLowValueClarity(originalText, newText, packet, ledger)) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        return null;
    }

    private static String grammarOpportunity(String original, String newText, String opportunity) {
        boolean originalManagements = MANAGEMENTS.matcher(original).find();
        boolean newManagements = MANAGEMENTS.matcher(newText).find();
        if (originalManagements && newManagements) {
            return RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED;
        }
        if (OpportunityDetector.GRAMMAR.equals(opportunity)
                && JAVA_DOC_STRING.matcher(original).find()
                && JAVA_DOC_STRING.matcher(newText).find()) {
            return RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED;
        }
        return null;
    }

    private static String tenseOpportunity(
            String original,
            String newText,
            String opportunity,
            String nodeId,
            ClaimLedger ledger) {

        boolean ongoing = ledger != null && ledger.isOngoing(nodeId);
        boolean ended = ledger != null && ledger.isEnded(nodeId);
        boolean originalDuty = isOngoingDuty(original);
        boolean rewritePast = startsWithPastDuty(newText);
        boolean rewritePresent = startsWithPresentDuty(newText);
        boolean rewriteCompleted = isCompletedOutcome(newText);

        if (ongoing && originalDuty && rewritePast && !rewriteCompleted) {
            if (OpportunityDetector.TENSE.equals(opportunity)) {
                return RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED;
            }
            if (isClarityOrProfessional(opportunity) && !hasMaterialImprovement(original, newText)) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
        }
        if (ended && OpportunityDetector.TENSE.equals(opportunity) && rewritePresent) {
            return RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED;
        }
        return null;
    }

    private static boolean cardinalityChanged(
            String original,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        String evidence = evidenceText(original, packet, ledger);
        for (String[] pair : CARDINALITY) {
            int evidencePlural = countWord(evidence, pair[1]);
            int evidenceSingular = countWord(evidence, pair[0]);
            int newPlural = countWord(newText, pair[1]);
            if (evidencePlural == 0 && evidenceSingular > 0 && newPlural > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLowValueClarity(
            String original,
            String newText,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        if (normalize(original).equals(normalize(newText))) {
            return false;
        }
        if (hasGrammarFix(original, newText) || hasMaterialImprovement(original, newText)) {
            return false;
        }
        if (SurfaceRewriteEquivalence.isLowValueSurface(original, newText)
                || ConservativeCandidateLocalScorer.isLowValueSynonymOnly(original, newText)
                || ConservativeCandidateIntent.isTenseOnly(original, newText)
                || isTensePairOnly(original, newText)) {
            return true;
        }
        if (ORIGINAL_COLLAB.matcher(original).find()
                && COLLAB_PARAPHRASE.matcher(newText).find()
                && ConservativeEvidencePacket.extractNormalizedTech(newText)
                .equals(ConservativeEvidencePacket.extractNormalizedTech(original))) {
            return true;
        }
        if (COMMUNICATION.matcher(original).find()
                && COMMUNICATION.matcher(newText).find()
                && (adverbPolishOnly(original, newText)
                || ConservativeCandidateLocalScorer.tokenJaccard(original, newText) >= 0.55)) {
            return true;
        }
        return synonymOrWordOrderOnly(original, newText);
    }

    private static boolean hasGrammarFix(String original, String newText) {
        if (MANAGEMENTS.matcher(original).find() && !MANAGEMENTS.matcher(newText).find()) {
            return true;
        }
        return JAVA_DOC_STRING.matcher(original).find() && !JAVA_DOC_STRING.matcher(newText).find();
    }

    private static boolean hasMaterialImprovement(String original, String newText) {
        Set<String> added = ConservativeEvidencePacket.extractNormalizedTech(newText);
        added.removeAll(ConservativeEvidencePacket.extractNormalizedTech(original));
        return !added.isEmpty();
    }

    private static boolean isTensePairOnly(String original, String newText) {
        return stemmedBag(original, TENSE_STEM).equals(stemmedBag(newText, TENSE_STEM))
                && !normalize(original).equals(normalize(newText));
    }

    private static boolean synonymOrWordOrderOnly(String original, String newText) {
        List<String> left = stemmedTokens(original);
        List<String> right = stemmedTokens(newText);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> leftSet = new LinkedHashSet<>(left);
        Set<String> rightSet = new LinkedHashSet<>(right);
        boolean sameBag = leftSet.equals(rightSet);
        double jaccard = ConservativeCandidateLocalScorer.tokenJaccard(original, newText);
        return (sameBag && !left.equals(right)) || jaccard >= 0.86;
    }

    private static boolean adverbPolishOnly(String original, String newText) {
        Set<String> left = new LinkedHashSet<>(tokens(original));
        Set<String> right = new LinkedHashSet<>(tokens(newText));
        Set<String> onlyLeft = new LinkedHashSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new LinkedHashSet<>(right);
        onlyRight.removeAll(left);
        if (onlyLeft.isEmpty() && onlyRight.isEmpty()) {
            return false;
        }
        return ADVERB_POLISH.containsAll(onlyLeft) && ADVERB_POLISH.containsAll(onlyRight);
    }

    private static boolean isOngoingDuty(String text) {
        return startsWithPresentDuty(text) && !isCompletedOutcome(text);
    }

    private static boolean startsWithPresentDuty(String text) {
        for (String token : leadingActions(text)) {
            if (PRESENT_DUTY.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithPastDuty(String text) {
        List<String> actions = leadingActions(text);
        return !actions.isEmpty() && PAST_DUTY.contains(actions.get(0));
    }

    private static boolean isCompletedOutcome(String text) {
        return COMPLETED_OUTCOME.matcher(text == null ? "" : text).find();
    }

    private static List<String> leadingActions(String text) {
        List<String> actions = new ArrayList<>();
        for (String token : tokens(text)) {
            if (FILLER.contains(token) || token.length() < 3) {
                continue;
            }
            if (PRESENT_DUTY.contains(token) || PAST_DUTY.contains(token)) {
                actions.add(token);
            }
            if (actions.size() >= 3) {
                break;
            }
        }
        return actions;
    }

    private static boolean isClarityOrProfessional(String opportunity) {
        return OpportunityDetector.CLARITY.equals(opportunity)
                || OpportunityDetector.PROFESSIONAL_LANGUAGE.equals(opportunity);
    }

    private static String evidenceText(
            String original,
            ConservativeEvidencePacket packet,
            ClaimLedger ledger) {

        StringBuilder text = new StringBuilder(original == null ? "" : original);
        if (packet != null && packet.supportingNodeIds() != null && ledger != null) {
            for (String supportingId : packet.supportingNodeIds()) {
                text.append(' ').append(ConservativeCandidateHardGate.textOf(ledger, supportingId));
            }
        }
        return text.toString();
    }

    private static int countWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(normalize(text));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static List<String> stemmedTokens(String text) {
        List<String> stems = new ArrayList<>();
        for (String token : tokens(text)) {
            if (FILLER.contains(token) || token.length() < 3) {
                continue;
            }
            stems.add(SYNONYM_STEM.getOrDefault(TENSE_STEM.getOrDefault(token, token), token));
        }
        return stems;
    }

    private static Set<String> stemmedBag(String text, Map<String, String> mapping) {
        LinkedHashSet<String> bag = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            if (FILLER.contains(token) || token.length() < 3) {
                continue;
            }
            bag.add(mapping.getOrDefault(token, token));
        }
        return bag;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#/\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
