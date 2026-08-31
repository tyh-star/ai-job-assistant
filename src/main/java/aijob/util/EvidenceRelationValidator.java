package aijob.util;

import aijob.dto.RejectedTailorChange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Same-entry co-occurrence is not a causal, purpose, means, or result relation.
 * Stitched relations that no single original node states are rejected.
 */
public final class EvidenceRelationValidator {

    static final String FAMILY_MEANS = "MEANS";
    static final String FAMILY_PURPOSE = "PURPOSE";
    static final String FAMILY_RESULT = "RESULT";

    private static final Pattern USING_FOR = Pattern.compile(
            "(?i)\\busing\\s+([^,.;]{1,80}?)\\s+for\\s+([^,.;]{1,100})"
    );
    private static final Pattern USING_TO = Pattern.compile(
            "(?i)\\busing\\s+([^,.;]{1,80}?)\\s+to\\s+(?!enable\\b|allow\\b|ensure\\b)([^,.;]{1,100})"
    );
    private static final Pattern USING_BARE = Pattern.compile(
            "(?i)\\busing\\s+([^,.;]{1,80}?)(?=\\s*(?:,|;|\\.|$|\\band\\b))"
    );
    private static final Pattern BY_THROUGH = Pattern.compile(
            "(?i)\\b(?:by|through)\\s+((?:(?!\\b(?:and|,|;|\\.)).){1,80})"
    );
    private static final Pattern TO_ENABLE = Pattern.compile(
            "(?i)\\bto\\s+(enable|allow|ensure)\\s+([^,.;]{1,100})"
    );
    private static final Pattern RESULTING = Pattern.compile(
            "(?i)\\b(?:resulting in|leading to)\\s+([^,.;]{1,100})"
    );
    private static final Pattern IN_ORDER_TO = Pattern.compile(
            "(?i)\\bin order to\\s+([^,.;]{1,100})"
    );
    private static final Pattern FOR_GERUND = Pattern.compile(
            "(?i)\\bfor\\s+((?:managing|planning|selecting|handling|improving|creating|building|"
                    + "developing|implementing|deploying|recommending|modeling|clarifying|utilizing|"
                    + "enabling|ensuring|allowing|supporting|providing)\\b[^,.;]{0,100})"
    );
    private static final Pattern CONTROLLED_DUTY = Pattern.compile(
            "(?i)\\b(?:to\\s+handle|for\\s+handling|to\\s+manage|for\\s+managing|supporting)\\b"
    );
    private static final Pattern DUTY_PREFIX = Pattern.compile(
            "(?i)^(?:to\\s+)?(?:handle|handling|manage|managing|supporting|support)\\s+"
    );
    private static final Pattern DATE_MEANS = Pattern.compile(
            "(?i)^(?:\\d|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|q[1-4]|early|late|mid)"
    );
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{3,}");
    private static final Set<String> WEAK = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "into", "onto", "within",
            "using", "used", "than", "then", "such", "also", "project", "application",
            "system", "version", "feature", "process", "work", "based", "principles",
            "pattern", "functionalities", "functionality", "operations", "management",
            "diagram", "class", "same", "their"
    );
    private static final Set<String> MEANS_FILLER = Set.of(
            "architecture", "design", "pattern", "approach", "structure",
            "configuration", "configurations", "methodology", "model",
            "specified", "given", "optimal"
    );

    private EvidenceRelationValidator() {
    }

    public static String rejectReason(String newText, String targetOriginal, List<String> evidenceTexts) {
        if (newText == null || newText.isBlank()) {
            return null;
        }
        List<String> evidence = new ArrayList<>();
        addEvidence(evidence, targetOriginal);
        if (evidenceTexts != null) {
            for (String text : evidenceTexts) {
                addEvidence(evidence, text);
            }
        }
        for (Relation relation : extract(newText)) {
            if (!relationSupported(relation, targetOriginal, evidence)) {
                return RejectedTailorChange.RELATION_NOT_SUPPORTED;
            }
        }
        return null;
    }

    static List<Relation> extract(String text) {
        List<Relation> relations = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return relations;
        }
        addUsingRelations(text, relations);
        Matcher by = BY_THROUGH.matcher(text);
        while (by.find()) {
            String means = by.group(1).strip();
            if (means.isBlank() || DATE_MEANS.matcher(means).find()) {
                continue;
            }
            relations.add(new Relation(FAMILY_MEANS, means, "", by.group().strip()));
        }
        Matcher enable = TO_ENABLE.matcher(text);
        while (enable.find()) {
            relations.add(new Relation(
                    FAMILY_PURPOSE,
                    leftClause(text, enable.start()),
                    enable.group(2).strip(),
                    enable.group().strip()));
        }
        Matcher resulting = RESULTING.matcher(text);
        while (resulting.find()) {
            relations.add(new Relation(
                    FAMILY_RESULT,
                    leftClause(text, resulting.start()),
                    resulting.group(1).strip(),
                    resulting.group().strip()));
        }
        Matcher order = IN_ORDER_TO.matcher(text);
        while (order.find()) {
            relations.add(new Relation(
                    FAMILY_PURPOSE,
                    leftClause(text, order.start()),
                    order.group(1).strip(),
                    order.group().strip()));
        }
        Matcher gerund = FOR_GERUND.matcher(text);
        while (gerund.find()) {
            relations.add(new Relation(
                    FAMILY_PURPOSE,
                    leftClause(text, gerund.start()),
                    gerund.group(1).strip(),
                    gerund.group().strip()));
        }
        return relations;
    }

    private static void addUsingRelations(String text, List<Relation> relations) {
        Set<Integer> consumed = new LinkedHashSet<>();
        Matcher usingFor = USING_FOR.matcher(text);
        while (usingFor.find()) {
            consumed.add(usingFor.start());
            relations.add(new Relation(
                    FAMILY_MEANS,
                    usingFor.group(1).strip(),
                    usingFor.group(2).strip(),
                    usingFor.group().strip()));
        }
        Matcher usingTo = USING_TO.matcher(text);
        while (usingTo.find()) {
            consumed.add(usingTo.start());
            relations.add(new Relation(
                    FAMILY_MEANS,
                    usingTo.group(1).strip(),
                    usingTo.group(2).strip(),
                    usingTo.group().strip()));
        }
        Matcher usingBare = USING_BARE.matcher(text);
        while (usingBare.find()) {
            if (consumed.contains(usingBare.start())) {
                continue;
            }
            relations.add(new Relation(
                    FAMILY_MEANS,
                    usingBare.group(1).strip(),
                    "",
                    usingBare.group().strip()));
        }
    }

    static boolean relationSupported(Relation relation, String targetOriginal, List<String> evidence) {
        if (relation == null) {
            return true;
        }
        if (controlledDutySupported(relation, targetOriginal)) {
            return true;
        }
        boolean leftBlank = relation.left() == null || relation.left().isBlank();
        boolean rightBlank = relation.right() == null || relation.right().isBlank();
        if (leftBlank && rightBlank) {
            return true;
        }
        for (String node : evidence) {
            if (!leftBlank && !rightBlank
                    && containedIn(relation.left(), node)
                    && containedIn(relation.right(), node)) {
                return true;
            }
        }
        if (FAMILY_MEANS.equals(relation.family()) && meansAttachment(relation, targetOriginal, evidence)) {
            return true;
        }
        for (String node : evidence) {
            if (hasFamilyConnective(node, relation.family())
                    && (leftBlank || containedIn(relation.left(), node))
                    && (rightBlank || containedIn(relation.right(), node))) {
                return true;
            }
        }
        return false;
    }

    private static boolean meansAttachment(Relation relation, String targetOriginal, List<String> evidence) {
        String right = relation.right() == null ? "" : relation.right();
        if (!right.isBlank() && !containedIn(right, targetOriginal)) {
            return false;
        }
        if (borrowsForeignTokens(right, targetOriginal, evidence)) {
            return false;
        }
        if (containedIn(relation.left(), targetOriginal)) {
            return true;
        }
        for (String node : evidence) {
            if (sameText(node, targetOriginal)) {
                continue;
            }
            if (containedIn(relation.left(), node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same-node duty connectives may be rewritten without inventing a new relation:
     * to handle / for handling / to manage / for managing / supporting.
     * Both ends and the objects must already exist on the target original.
     */
    private static boolean controlledDutySupported(Relation relation, String targetOriginal) {
        if (relation == null || targetOriginal == null || targetOriginal.isBlank()) {
            return false;
        }
        if (!FAMILY_PURPOSE.equals(relation.family()) && !isControlledDutySpan(relation.span())) {
            return false;
        }
        if (!isControlledDutySpan(relation.span()) && !isControlledDutySpan(relation.right())) {
            return false;
        }
        if (!CONTROLLED_DUTY.matcher(targetOriginal).find()) {
            return false;
        }
        if (!containedIn(relation.left(), targetOriginal)) {
            return false;
        }
        return containedIn(dutyObject(relation.right()), targetOriginal)
                || containedIn(dutyObject(relation.span()), targetOriginal);
    }

    private static boolean isControlledDutySpan(String span) {
        return span != null && CONTROLLED_DUTY.matcher(span).find();
    }

    private static String dutyObject(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return "";
        }
        return DUTY_PREFIX.matcher(fragment.strip()).replaceFirst("");
    }

    static boolean containedIn(String fragment, String nodeText) {
        if (fragment == null || fragment.isBlank()) {
            return true;
        }
        if (nodeText == null || nodeText.isBlank()) {
            return false;
        }
        List<ConservativeMaterialFacts.Fact> facts = ConservativeMaterialFacts.distinctive(
                ConservativeMaterialFacts.extract(fragment));
        int checked = 0;
        for (ConservativeMaterialFacts.Fact fact : facts) {
            if (isMeansFiller(fact)) {
                continue;
            }
            checked++;
            if (!ConservativeMaterialFacts.covers(fact, nodeText)) {
                return false;
            }
        }
        if (checked > 0) {
            return true;
        }
        Set<String> tokens = rawTokens(fragment);
        tokens.removeAll(WEAK);
        tokens.removeAll(MEANS_FILLER);
        if (tokens.isEmpty()) {
            return true;
        }
        return rawTokens(nodeText).containsAll(tokens);
    }

    private static boolean isMeansFiller(ConservativeMaterialFacts.Fact fact) {
        if (fact == null || fact.category() != ConservativeMaterialFacts.Category.OBJECT) {
            return false;
        }
        for (String alias : fact.aliases()) {
            if (alias != null && MEANS_FILLER.contains(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean borrowsForeignTokens(String fragment, String targetOriginal, List<String> evidence) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        Set<String> fragmentTokens = rawTokens(fragment);
        Set<String> targetTokens = rawTokens(targetOriginal);
        for (String node : evidence) {
            if (sameText(node, targetOriginal)) {
                continue;
            }
            Set<String> other = rawTokens(node);
            other.retainAll(fragmentTokens);
            other.removeAll(targetTokens);
            other.removeAll(WEAK);
            if (!other.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFamilyConnective(String nodeText, String family) {
        for (Relation relation : extract(nodeText)) {
            if (family.equals(relation.family())) {
                return true;
            }
        }
        String lower = lower(nodeText);
        if (FAMILY_PURPOSE.equals(family)) {
            return lower.contains(" in order to ")
                    || lower.contains(" to handle ")
                    || lower.contains(" to manage ")
                    || lower.contains(" to enable ")
                    || lower.contains(" to allow ")
                    || lower.contains(" to ensure ");
        }
        if (FAMILY_RESULT.equals(family)) {
            return lower.contains("resulting in") || lower.contains("leading to");
        }
        return lower.contains(" using ") || lower.contains(" through ") || lower.matches(".*\\bby\\b.*");
    }

    private static Set<String> rawTokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(lower(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (!WEAK.contains(token) && !ConservativeMaterialFacts.isGenericToken(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String leftClause(String text, int start) {
        if (text == null || start <= 0) {
            return "";
        }
        int from = Math.max(0, start - 90);
        String prefix = text.substring(from, start);
        int cut = Math.max(prefix.lastIndexOf('.'), Math.max(prefix.lastIndexOf(';'), prefix.lastIndexOf(',')));
        if (cut >= 0) {
            prefix = prefix.substring(cut + 1);
        }
        return prefix.strip();
    }

    private static void addEvidence(List<String> evidence, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String existing : evidence) {
            if (sameText(existing, text)) {
                return;
            }
        }
        evidence.add(text);
    }

    private static boolean sameText(String left, String right) {
        return lower(left).equals(lower(right));
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
    }

    record Relation(String family, String left, String right, String span) {
    }
}
