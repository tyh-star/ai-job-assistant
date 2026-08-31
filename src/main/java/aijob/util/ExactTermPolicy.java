package aijob.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic filter for ATS / exact-term matching. Low-information verbs and
 * nouns cannot stand alone as ATS keywords.
 */
public final class ExactTermPolicy {

    private static final Set<String> LOW_INFORMATION = Set.of(
            "design",
            "develop",
            "implement",
            "manage",
            "support",
            "work",
            "build",
            "create",
            "experience",
            "knowledge",
            "ability",
            "responsible",
            "application",
            "service",
            "system",
            "team",
            "implementation",
            "development"
    );

    private static final Set<String> STOPWORDS = Set.of(
            "and", "or", "the", "for", "with", "using", "of", "to", "a", "an",
            "in", "on", "at", "as", "by", "from", "into", "over", "under"
    );

    private ExactTermPolicy() {
    }

    public static boolean isLowInformation(String term) {
        if (term == null || term.isBlank()) {
            return true;
        }
        List<String> tokens = tokens(term);
        if (tokens.isEmpty()) {
            return true;
        }
        for (String token : tokens) {
            if (isMeaningfulToken(token)) {
                return false;
            }
        }
        return true;
    }

    public static List<String> retainMeaningful(List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        for (String term : terms) {
            if (isLowInformation(term)) {
                continue;
            }
            String key = EvidenceText.normalize(term);
            if (unique.add(key)) {
                kept.add(term.strip());
            }
        }
        return kept;
    }

    public static List<String> termsForRequirement(String requirement, List<String> exactTerms) {
        List<String> retained = retainMeaningful(exactTerms);
        if (!retained.isEmpty()) {
            return retained;
        }
        String derived = derivePhrase(requirement);
        if (derived == null || isLowInformation(derived)) {
            return List.of();
        }
        return List.of(derived);
    }

    public static boolean isMeaningfulToken(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        String normalized = EvidenceText.normalize(token);
        if (STOPWORDS.contains(normalized)) {
            return false;
        }
        return !LOW_INFORMATION.contains(stem(normalized));
    }

    static String derivePhrase(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return null;
        }
        List<String> originalTokens = new ArrayList<>();
        for (String part : requirement.strip().split("\\s+")) {
            String cleaned = part.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (!cleaned.isBlank()) {
                originalTokens.add(cleaned);
            }
        }
        int start = 0;
        int end = originalTokens.size();
        while (start < end && !isMeaningfulToken(originalTokens.get(start))) {
            start++;
        }
        if (start >= end) {
            return null;
        }
        return String.join(" ", originalTokens.subList(start, end));
    }

    static String stem(String token) {
        if (token.endsWith("ies") && token.length() > 5) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ing") && token.length() > 6) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 5) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3 && !token.endsWith("ss") && !token.endsWith("us")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static List<String> tokens(String term) {
        List<String> tokens = new ArrayList<>();
        for (String part : EvidenceText.normalize(term).split("[^a-z0-9+#]+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
