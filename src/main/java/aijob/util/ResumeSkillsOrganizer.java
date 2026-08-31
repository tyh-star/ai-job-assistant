package aijob.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Skills organizer. Never adds or deletes skills.
 */
public final class ResumeSkillsOrganizer {

    private static final Pattern CATEGORY = Pattern.compile("^(.{1,40}?):\\s*(.+)$");

    private ResumeSkillsOrganizer() {
    }

    public static Result organize(String originalText, String jobDescription) {
        if (originalText == null || originalText.isBlank()) {
            return Result.unchanged(originalText);
        }
        ParsedSkills parsed = parse(originalText);
        if (!parsed.reliable || parsed.skills.isEmpty()) {
            return Result.unchanged(originalText);
        }
        Set<String> originalNormalized = new LinkedHashSet<>();
        for (String skill : parsed.skills.values()) {
            originalNormalized.add(normalizeKey(skill));
        }
        List<String> jobTerms = ConservativeTailorPlanner.extractTerms(jobDescription);
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsed.skills.entrySet()) {
            grouped.computeIfAbsent(parsed.categories.get(entry.getKey()), key -> new ArrayList<>())
                    .add(entry.getValue());
        }
        StringBuilder rewritten = new StringBuilder();
        boolean firstGroup = true;
        for (Map.Entry<String, List<String>> group : grouped.entrySet()) {
            List<String> ordered = new ArrayList<>(group.getValue());
            ordered.sort((left, right) -> Integer.compare(
                    jobOverlap(right, jobTerms),
                    jobOverlap(left, jobTerms)
            ));
            if (!firstGroup) {
                rewritten.append(parsed.groupSeparator);
            }
            firstGroup = false;
            if (group.getKey() != null && !group.getKey().isBlank()) {
                rewritten.append(group.getKey()).append(": ");
            }
            rewritten.append(String.join(parsed.itemSeparator, ordered));
        }
        Set<String> rewrittenNormalized = new LinkedHashSet<>();
        ParsedSkills check = parse(rewritten.toString());
        for (String skill : check.skills.values()) {
            rewrittenNormalized.add(normalizeKey(skill));
        }
        if (!rewrittenNormalized.equals(originalNormalized)) {
            return Result.unchanged(originalText);
        }
        String output = rewritten.toString();
        if (output.equals(originalText.strip())) {
            return Result.unchanged(originalText);
        }
        return new Result(output, true, true);
    }

    private static ParsedSkills parse(String originalText) {
        ParsedSkills parsed = new ParsedSkills();
        String text = originalText.strip();
        String[] lines = text.split("\\r?\\n");
        boolean usedCategories = false;
        for (String line : lines) {
            String value = line.strip();
            if (value.isEmpty()) {
                continue;
            }
            Matcher matcher = CATEGORY.matcher(value);
            String category = "";
            String items = value;
            if (matcher.matches() && matcher.group(2).contains(",")) {
                category = matcher.group(1).strip();
                items = matcher.group(2).strip();
                usedCategories = true;
            }
            for (String item : items.split("\\s*,\\s*")) {
                String skill = item.strip();
                if (skill.isEmpty()) {
                    continue;
                }
                String key = normalizeKey(skill);
                if (parsed.skills.containsKey(key)) {
                    continue;
                }
                parsed.skills.put(key, skill);
                parsed.categories.put(key, category);
            }
        }
        parsed.reliable = !parsed.skills.isEmpty() && (usedCategories || text.contains(","));
        parsed.itemSeparator = text.contains(", ") ? ", " : ",";
        parsed.groupSeparator = text.contains("\n") ? "\n" : "; ";
        return parsed;
    }

    private static int jobOverlap(String skill, List<String> jobTerms) {
        String normalized = skill.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : jobTerms) {
            if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(term) + "\\b")
                    .matcher(normalized)
                    .find()) {
                score++;
            }
        }
        return score;
    }

    private static String normalizeKey(String skill) {
        return skill.strip().toLowerCase(Locale.ROOT);
    }

    public record Result(String text, boolean changed, boolean reliable) {
        static Result unchanged(String original) {
            return new Result(original, false, false);
        }
    }

    private static final class ParsedSkills {
        private final Map<String, String> skills = new LinkedHashMap<>();
        private final Map<String, String> categories = new LinkedHashMap<>();
        private boolean reliable;
        private String itemSeparator = ", ";
        private String groupSeparator = "\n";
    }
}
