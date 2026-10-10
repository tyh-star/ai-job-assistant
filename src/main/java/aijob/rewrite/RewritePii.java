package aijob.rewrite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal details the rewrite does not need (e-mail addresses, URLs, phone
 * numbers and known names) with stable placeholders such as [[PII_1]] before any text
 * is sent to the AI, and puts the real values back afterwards. The same value always
 * gets the same placeholder, so a placeholder the model drops or invents is detectable.
 */
public final class RewritePii {

    static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[PII_\\d+]]");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+|\\b(?:linkedin\\.com|github\\.com)/\\S+");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.])\\+?\\(?\\d[\\d\\s().-]{6,}\\d(?![\\w.])");

    private final Map<String, String> valueToPlaceholder = new LinkedHashMap<>();
    private final Map<String, String> placeholderToValue = new LinkedHashMap<>();
    private final List<String> names;

    private RewritePii(Collection<String> knownNames) {
        List<String> cleaned = new ArrayList<>();
        if (knownNames != null) {
            for (String name : knownNames) {
                if (name != null && name.trim().length() >= 2) {
                    cleaned.add(name.trim());
                }
            }
        }
        cleaned.sort(Comparator.comparingInt(String::length).reversed());
        this.names = cleaned;
    }

    public static RewritePii forNames(Collection<String> knownNames) {
        return new RewritePii(knownNames);
    }

    /** Redacts one text; call {@link #restore} on anything derived from it. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = replaceAll(text, EMAIL);
        result = replaceAll(result, URL);
        result = replacePhones(result);
        for (String name : names) {
            result = replaceLiteral(result, name);
        }
        return result;
    }

    public String restore(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = placeholderToValue.getOrDefault(matcher.group(), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** True when nothing but placeholders, punctuation and whitespace is left. */
    public static boolean onlyPersonalDetails(String redacted) {
        String rest = PLACEHOLDER.matcher(redacted == null ? "" : redacted).replaceAll("");
        return rest.replaceAll("[\\p{Punct}\\s·•|–—]", "").isEmpty();
    }

    static List<String> placeholdersIn(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        found.sort(String::compareTo);
        return found;
    }

    private String placeholderFor(String value) {
        return valueToPlaceholder.computeIfAbsent(value, v -> {
            String placeholder = "[[PII_" + (valueToPlaceholder.size() + 1) + "]]";
            placeholderToValue.put(placeholder, v);
            return placeholder;
        });
    }

    private String replaceAll(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(placeholderFor(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String replacePhones(String text) {
        Matcher matcher = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            long digits = candidate.chars().filter(Character::isDigit).count();
            // A year range such as "2019 - 2021" is not a phone number.
            boolean yearRange = candidate.trim().matches("(19|20)\\d{2}\\s*[-–]\\s*(19|20)\\d{2}");
            String replacement = digits >= 8 && !yearRange ? placeholderFor(candidate) : candidate;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String replaceLiteral(String text, String literal) {
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(literal) + "(?![\\p{L}\\p{N}])");
        return replaceAll(text, pattern);
    }
}
