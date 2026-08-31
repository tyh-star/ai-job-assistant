package aijob.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controlled, deterministic term aliases for Tailor Analysis matching.
 * This is not embedding similarity and does not treat unrelated verbs as evidence.
 */
public final class TermAliasNormalizer {

    private static final List<Set<String>> GROUPS = List.of(
            Set.of("mvc", "mvc pattern", "mvc architecture"),
            Set.of(
                    "rest",
                    "rest api",
                    "rest apis",
                    "restful",
                    "restful api",
                    "restful apis"
            ),
            Set.of(
                    "oop",
                    "object-oriented programming",
                    "object oriented programming"
            ),
            Set.of(
                    "javadoc",
                    "java doc",
                    "java doc string",
                    "java doc strings",
                    "java documentation",
                    "java docs"
            ),
            Set.of(
                    "computer science",
                    "computer science degree",
                    "degree in computer science",
                    "bachelor of computer science",
                    "b.s. computer science",
                    "bs computer science",
                    "bsc computer science",
                    "b.s. in computer science",
                    "bachelor in computer science"
            )
    );

    private TermAliasNormalizer() {
    }

    public static boolean matches(String text, String term) {
        String haystack = EvidenceText.normalize(text);
        String needle = EvidenceText.normalize(term);
        if (haystack.isEmpty() || needle.isEmpty()) {
            return false;
        }
        for (String variant : variants(needle)) {
            if (containsPhrase(haystack, variant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exact alias-group identity. {@code Java} is not {@code JavaScript},
     * {@code Spring} is not {@code Spring Boot}, and {@code MySQL} is not {@code SQL}.
     */
    public static String canonicalKey(String term) {
        String normalized = EvidenceText.normalize(term);
        if (normalized.isEmpty()) {
            return "";
        }
        Set<String> group = exactGroup(normalized);
        if (group == null) {
            return normalized;
        }
        if (group.contains("rest")) {
            return "restful api";
        }
        if (group.contains("oop")) {
            return "oop";
        }
        if (group.contains("javadoc")) {
            return "javadoc";
        }
        if (group.contains("mvc")) {
            return "mvc";
        }
        return group.stream().min(Comparator.comparingInt(String::length)).orElse(normalized);
    }

    public static boolean sameTerm(String left, String right) {
        String first = canonicalKey(left);
        String second = canonicalKey(right);
        return !first.isEmpty() && first.equals(second);
    }

    public static String displayName(String term) {
        String key = canonicalKey(term);
        if (key.isEmpty()) {
            return "";
        }
        return switch (key) {
            case "restful api" -> "RESTful API";
            case "oop" -> "OOP";
            case "javadoc" -> "Javadoc";
            case "mvc" -> "MVC";
            case "javascript" -> "JavaScript";
            case "spring boot" -> "Spring Boot";
            case "mysql" -> "MySQL";
            case "sql" -> "SQL";
            case "html" -> "HTML";
            case "css" -> "CSS";
            case "aws" -> "AWS";
            case "c/c++" -> "C/C++";
            case "c++" -> "C++";
            case "java" -> "Java";
            case "python" -> "Python";
            case "vue" -> "Vue";
            case "spring" -> "Spring";
            case "maven" -> "Maven";
            case "docker" -> "Docker";
            case "kubernetes" -> "Kubernetes";
            case "junit" -> "JUnit";
            case "git" -> "Git";
            default -> titleCase(key);
        };
    }

    private static Set<String> exactGroup(String normalizedTerm) {
        for (Set<String> group : GROUPS) {
            if (group.contains(normalizedTerm)) {
                return group;
            }
        }
        return null;
    }

    private static String titleCase(String key) {
        if (key.length() <= 3 && key.equals(key.toLowerCase())) {
            boolean letters = key.chars().allMatch(Character::isLetter);
            if (letters) {
                return key.toUpperCase();
            }
        }
        String[] parts = key.split("[\\s-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    static List<String> variants(String normalizedTerm) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(normalizedTerm);
        for (Set<String> group : GROUPS) {
            boolean related = false;
            for (String member : group) {
                if (normalizedTerm.equals(member) || containsPhrase(normalizedTerm, member)) {
                    related = true;
                    break;
                }
            }
            if (related) {
                values.addAll(group);
            }
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    public static boolean containsPhrase(String haystack, String phrase) {
        if (haystack == null || phrase == null || haystack.isEmpty() || phrase.isEmpty()) {
            return false;
        }
        if (phrase.contains(" ") || phrase.contains("/") || phrase.contains("+") || phrase.contains("#")
                || phrase.contains(".")) {
            return haystack.contains(phrase);
        }
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(haystack).find();
    }
}
