package aijob.rewrite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fact check for one rewritten node. It runs after every AI call, never
 * inside the model, so a rewrite that changes protected facts is rejected no matter
 * what the model claims.
 *
 * <p>Protected facts: numbers (metrics, dates, years), personal-detail placeholders,
 * and proper terms -- capitalised mid-sentence words, acronyms and tech tokens such as
 * names, companies, schools, job titles, months and skills. A rewrite must keep every
 * one of them. It may only add a number or proper term that already appears in the
 * allowed evidence (the whole resume, plus answers the user confirmed in the Pro flow;
 * nothing extra in the Free flow). Terms judged UNSUPPORTED are rejected outright.
 */
public final class RewriteFactGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:/]\\d+)*%?");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9+#.&'/-]*[A-Za-z0-9+#]|[A-Za-z]");
    private static final int MIN_LENGTH_PERCENT = 50;
    private static final int MAX_LENGTH_PERCENT = 220;

    private RewriteFactGuard() {
    }

    /**
     * @param original        the node's text before rewriting (redacted form)
     * @param revised         the model's text (redacted form)
     * @param allowedEvidence text a new number or proper term may come from; empty = none
     * @param forbiddenTerms  UNSUPPORTED terms that must never appear unless already evidenced
     * @return human-readable violations; empty when the rewrite is acceptable
     */
    public static List<String> check(String original, String revised, String allowedEvidence,
                                     Collection<String> forbiddenTerms) {
        List<String> violations = new ArrayList<>();
        String before = original == null ? "" : original.trim();
        String after = revised == null ? "" : revised.trim();
        if (after.isEmpty()) {
            violations.add("empty rewrite");
            return violations;
        }
        int ratio = before.isEmpty() ? 100 : (int) (100L * after.length() / before.length());
        if (ratio < MIN_LENGTH_PERCENT || ratio > MAX_LENGTH_PERCENT) {
            violations.add("length changed too much (" + ratio + "%)");
        }
        if (!RewritePii.placeholdersIn(before).equals(RewritePii.placeholdersIn(after))) {
            violations.add("personal details were changed or removed");
        }

        String evidence = allowedEvidence == null ? "" : allowedEvidence;
        String evidenceLower = evidence.toLowerCase(Locale.ROOT);

        Map<String, Integer> beforeNumbers = count(NUMBER, before);
        Map<String, Integer> afterNumbers = count(NUMBER, after);
        for (Map.Entry<String, Integer> entry : beforeNumbers.entrySet()) {
            if (afterNumbers.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                violations.add("number or date changed or removed: " + entry.getKey());
            }
        }
        for (String number : afterNumbers.keySet()) {
            if (!beforeNumbers.containsKey(number) && !containsNumber(evidence, number)) {
                violations.add("new number not in the resume: " + number);
            }
        }

        Set<String> beforeTerms = properTerms(before);
        String afterLower = after.toLowerCase(Locale.ROOT);
        for (String term : beforeTerms) {
            if (!afterLower.contains(term.toLowerCase(Locale.ROOT))) {
                violations.add("name, company, title or skill removed: " + term);
            }
        }
        String beforeLower = before.toLowerCase(Locale.ROOT);
        for (String term : properTerms(after)) {
            String lower = term.toLowerCase(Locale.ROOT);
            if (!beforeLower.contains(lower) && !containsWord(evidenceLower, lower)) {
                violations.add("new name, company, title or skill not in the resume: " + term);
            }
        }

        if (forbiddenTerms != null) {
            for (String term : forbiddenTerms) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                String lower = term.trim().toLowerCase(Locale.ROOT);
                if (containsWord(afterLower, lower) && !containsWord(beforeLower, lower)
                        && !containsWord(evidenceLower, lower)) {
                    violations.add("unsupported content added: " + term.trim());
                }
            }
        }
        return violations;
    }

    /** Capitalised mid-sentence words, acronyms and tech tokens (C++, Node.js, iOS). */
    static Set<String> properTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() < 2 || word.equals("PII") || word.startsWith("PII_")) {
                continue;
            }
            boolean acronym = word.chars().filter(Character::isUpperCase).count() >= 2;
            boolean techToken = word.matches(".*[0-9+#].*") || word.matches(".*[a-z][A-Z].*")
                    || word.matches(".*\\.[A-Za-z].*");
            boolean capitalised = Character.isUpperCase(word.charAt(0));
            boolean sentenceStart = isSentenceStart(text, matcher.start());
            if (acronym || techToken || (capitalised && !sentenceStart)) {
                terms.add(word);
            }
        }
        return terms;
    }

    private static boolean isSentenceStart(String text, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char previous = text.charAt(i);
        return ".!?;:•●▪◦‣∙·-–—(\"\u201C".indexOf(previous) >= 0;
    }

    private static Map<String, Integer> count(Pattern pattern, String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (text.startsWith("[[PII_", Math.max(0, matcher.start() - 6))) {
                continue;
            }
            counts.merge(matcher.group(), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean containsNumber(String evidence, String number) {
        return Pattern.compile("(?<![\\d.,])" + Pattern.quote(number) + "(?![\\d])").matcher(evidence).find();
    }

    private static boolean containsWord(String haystackLower, String needleLower) {
        if (needleLower.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needleLower) + "(?![\\p{L}\\p{N}])")
                .matcher(haystackLower).find();
    }
}
