package aijob.util;

import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side atomization of JD requirements. OpenAI output is not trusted to
 * already be one skill per requirement.
 */
public final class RequirementAtomizer {

    private static final int MAX_REQUIREMENTS = 20;
    private static final Pattern SPLIT = Pattern.compile(
            "\\s*(?:,\\s*(?:and|&)?\\s+|\\s*(?:&|\\band\\b)\\s+)\\s*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INSEPARABLE = Pattern.compile(
            "(?i)\\b(design|develop(?:ment)?|implement(?:ation)?)\\s+and\\s+"
                    + "(design|develop(?:ment)?|implement(?:ation)?)\\b"
    );
    private static final Set<String> SOFT_TOKENS = Set.of(
            "communication",
            "collaboration",
            "teamwork",
            "leadership",
            "interpersonal",
            "problem",
            "solving",
            "written",
            "verbal",
            "time",
            "management",
            "attention",
            "detail",
            "ownership",
            "attitude",
            "mindset"
    );

    private RequirementAtomizer() {
    }

    public static List<JobRequirementDraft> atomize(List<JobRequirementDraft> drafts) {
        List<JobRequirementDraft> exploded = new ArrayList<>();
        if (drafts != null) {
            for (JobRequirementDraft draft : drafts) {
                if (draft == null || isBlank(draft.getRequirement())) {
                    continue;
                }
                exploded.addAll(split(draft));
            }
        }
        return cap(merge(exploded));
    }

    static List<JobRequirementDraft> split(JobRequirementDraft draft) {
        String category = normalizeCategory(draft.getCategory());
        String priority = normalizePriority(draft.getPriority());
        List<String> terms = ExactTermPolicy.termsForRequirement(
                draft.getRequirement(),
                draft.getExactTerms()
        );
        if (!shouldSplit(category, draft.getRequirement(), terms)) {
            return List.of(copy(draft.getRequirement().strip(), category, priority, terms));
        }
        List<String> parts = atomicParts(draft.getRequirement(), terms);
        if (parts.size() < 2) {
            return List.of(copy(draft.getRequirement().strip(), category, priority, terms));
        }
        List<JobRequirementDraft> result = new ArrayList<>();
        for (String part : parts) {
            result.add(copy(part, category, priority, List.of(part)));
        }
        return result;
    }

    private static boolean shouldSplit(String category, String requirement, List<String> terms) {
        if (RequirementCategory.SOFT_SKILL.equals(category)
                || RequirementCategory.QUALIFICATION.equals(category)
                || RequirementCategory.RESPONSIBILITY.equals(category)
                || RequirementCategory.OTHER_KEYWORD.equals(category)
                || RequirementCategory.DOMAIN.equals(category)) {
            return false;
        }
        if (isInseparable(requirement)) {
            return false;
        }
        return looksLikeTechList(requirement, terms) || RequirementCategory.HARD_SKILL.equals(category);
    }

    private static List<String> atomicParts(String requirement, List<String> terms) {
        List<String> fromTerms = new ArrayList<>();
        for (String term : terms) {
            if (isInseparable(term) || !looksLikeTechName(term)) {
                continue;
            }
            fromTerms.addAll(splitPhrase(term));
        }
        fromTerms = uniqueMeaningful(fromTerms);
        if (fromTerms.size() >= 2 && !isSoftBundle(fromTerms)) {
            return fromTerms;
        }
        List<String> fromText = uniqueMeaningful(splitPhrase(requirement));
        if (fromText.size() >= 2 && !isSoftBundle(fromText) && fromText.stream().allMatch(RequirementAtomizer::looksLikeTechName)) {
            return fromText;
        }
        return fromTerms.size() >= 2 ? fromTerms : List.of();
    }

    private static boolean looksLikeTechList(String requirement, List<String> terms) {
        List<String> parts = uniqueMeaningful(terms);
        if (parts.size() >= 2 && parts.stream().allMatch(RequirementAtomizer::looksLikeTechName)) {
            return true;
        }
        List<String> fromText = uniqueMeaningful(splitPhrase(requirement));
        return fromText.size() >= 2 && fromText.stream().allMatch(RequirementAtomizer::looksLikeTechName);
    }

    private static List<String> splitPhrase(String phrase) {
        if (isBlank(phrase) || isInseparable(phrase)) {
            return List.of(phrase == null ? "" : phrase.strip());
        }
        String[] bits = SPLIT.split(phrase.strip());
        List<String> parts = new ArrayList<>();
        for (String bit : bits) {
            if (!isBlank(bit)) {
                parts.add(bit.strip());
            }
        }
        return parts.isEmpty() ? List.of(phrase.strip()) : parts;
    }

    private static List<String> uniqueMeaningful(List<String> parts) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String part : parts) {
            if (isBlank(part) || ExactTermPolicy.isLowInformation(part)) {
                continue;
            }
            unique.putIfAbsent(EvidenceText.normalize(part), part.strip());
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean looksLikeTechName(String phrase) {
        if (isBlank(phrase) || isInseparable(phrase) || isSoftPhrase(phrase)) {
            return false;
        }
        String[] words = phrase.strip().split("\\s+");
        if (words.length > 4) {
            return false;
        }
        int meaningful = 0;
        for (String word : words) {
            if (ExactTermPolicy.isMeaningfulToken(word)) {
                meaningful++;
            }
        }
        return meaningful >= 1 && phrase.strip().length() <= 40;
    }

    private static boolean isSoftBundle(List<String> parts) {
        if (parts.size() < 2) {
            return false;
        }
        for (String part : parts) {
            if (!isSoftPhrase(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSoftPhrase(String phrase) {
        String normalized = EvidenceText.normalize(phrase);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean sawSoft = false;
        for (String token : normalized.split("[^a-z0-9+#]+")) {
            if (token.isBlank() || token.length() < 3) {
                continue;
            }
            if (SOFT_TOKENS.contains(token) || SOFT_TOKENS.contains(ExactTermPolicy.stem(token))) {
                sawSoft = true;
                continue;
            }
            if (ExactTermPolicy.isMeaningfulToken(token)) {
                return false;
            }
        }
        return sawSoft;
    }

    private static boolean isInseparable(String text) {
        return text != null && INSEPARABLE.matcher(text).find();
    }

    private static List<JobRequirementDraft> merge(List<JobRequirementDraft> drafts) {
        Map<String, JobRequirementDraft> merged = new LinkedHashMap<>();
        for (JobRequirementDraft draft : drafts) {
            String key = mergeKey(draft);
            JobRequirementDraft existing = merged.get(key);
            if (existing == null) {
                merged.put(key, draft);
                continue;
            }
            if (priorityRank(draft.getPriority()) > priorityRank(existing.getPriority())) {
                existing.setPriority(draft.getPriority());
            }
            if (RequirementCategory.HARD_SKILL.equals(draft.getCategory())) {
                existing.setCategory(RequirementCategory.HARD_SKILL);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static String mergeKey(JobRequirementDraft draft) {
        return normalizeCategory(draft.getCategory())
                + "|"
                + ResponsibilityConceptExtractor.signature(draft.getRequirement(), draft.getExactTerms());
    }

    private static int priorityRank(String priority) {
        if (RequirementPriority.MUST_HAVE.equals(priority)) {
            return 3;
        }
        if (RequirementPriority.PREFERRED.equals(priority)) {
            return 2;
        }
        if (RequirementPriority.OPTIONAL.equals(priority)) {
            return 1;
        }
        return 0;
    }

    private static List<JobRequirementDraft> cap(List<JobRequirementDraft> drafts) {
        if (drafts.size() <= MAX_REQUIREMENTS) {
            return drafts;
        }
        return new ArrayList<>(drafts.subList(0, MAX_REQUIREMENTS));
    }

    private static JobRequirementDraft copy(String requirement, String category, String priority, List<String> terms) {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement(requirement);
        draft.setCategory(category);
        draft.setPriority(priority);
        draft.setExactTerms(terms);
        return draft;
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return RequirementCategory.OTHER_KEYWORD;
        }
        String value = category.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case RequirementCategory.RESPONSIBILITY,
                    RequirementCategory.QUALIFICATION,
                    RequirementCategory.DOMAIN,
                    RequirementCategory.SOFT_SKILL,
                    RequirementCategory.HARD_SKILL,
                    RequirementCategory.OTHER_KEYWORD -> value;
            default -> RequirementCategory.OTHER_KEYWORD;
        };
    }

    private static String normalizePriority(String priority) {
        if (priority == null) {
            return RequirementPriority.PREFERRED;
        }
        String value = priority.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case RequirementPriority.MUST_HAVE,
                    RequirementPriority.PREFERRED,
                    RequirementPriority.OPTIONAL -> value;
            default -> RequirementPriority.PREFERRED;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
