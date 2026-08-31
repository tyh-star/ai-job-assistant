package aijob.tailor.v3;

import aijob.util.EvidenceText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects 3-5 JD requirements for one entry without calling OpenAI.
 * Coverage is computed from the entry fact graph, not from model output.
 */
public final class V3JdRequirementRanker {

    private V3JdRequirementRanker() {
    }

    public static List<String> rankedIds(String jobDescription, String entryText) {
        List<String> ids = new ArrayList<>();
        for (V3RankedRequirement requirement : rank(jobDescription, entryText, null)) {
            ids.add(requirement.id());
        }
        return ids;
    }

    public static List<V3RankedRequirement> rank(String jobDescription, V3EntryFactGraph graph) {
        return rank(jobDescription, graph == null ? "" : graph.originalEntryText(), graph);
    }

    public static List<V3RankedRequirement> rank(
            String jobDescription,
            String entryText,
            V3EntryFactGraph graph) {
        List<String> sentences = split(jobDescription);
        String haystack = EvidenceText.normalize(entryText);
        Set<String> entryTech = graph == null ? Set.of() : graph.keysOf(V3FactKind.TECHNOLOGY);
        List<Scored> scored = new ArrayList<>();
        int index = 0;
        for (String sentence : sentences) {
            int score = overlap(haystack, sentence);
            V3RequirementCoverage coverage = coverageOf(sentence, haystack, entryTech);
            scored.add(new Scored("req-" + index, score, sentence, coverage, tokens(sentence)));
            index++;
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<V3RankedRequirement> ranked = new ArrayList<>();
        for (Scored item : scored) {
            if (item.score > 0 || ranked.size() < 3) {
                ranked.add(new V3RankedRequirement(item.id, item.sentence, item.coverage, List.copyOf(item.terms)));
            }
            if (ranked.size() >= 5) {
                break;
            }
        }
        return ranked;
    }

    private static V3RequirementCoverage coverageOf(String sentence, String entryHaystack, Set<String> entryTech) {
        int hits = overlap(entryHaystack, sentence);
        boolean unsupportedTech = false;
        for (String token : tokens(sentence)) {
            if (looksLikeTech(token) && !entryHaystack.contains(token) && !entryContainsTech(entryTech, token)) {
                unsupportedTech = true;
                break;
            }
        }
        if (unsupportedTech && hits == 0) {
            return V3RequirementCoverage.UNSUPPORTED;
        }
        if (hits >= 2) {
            return V3RequirementCoverage.COVERED;
        }
        if (hits == 1) {
            return V3RequirementCoverage.EMPHASIZABLE;
        }
        return V3RequirementCoverage.LOW_VALUE;
    }

    private static boolean entryContainsTech(Set<String> entryTech, String token) {
        if (entryTech == null) {
            return false;
        }
        for (String key : entryTech) {
            if (key != null && key.toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeTech(String token) {
        return token.length() >= 3 && !Set.of("the", "and", "with", "for", "from", "that", "this", "experience").contains(token);
    }

    private static List<String> split(String jobDescription) {
        List<String> parts = new ArrayList<>();
        if (jobDescription == null || jobDescription.isBlank()) {
            return parts;
        }
        for (String raw : jobDescription.split("[\\n\\r\\.!;]+")) {
            String trimmed = raw.strip();
            if (trimmed.length() >= 8) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty() && jobDescription != null) {
            parts.add(jobDescription.strip());
        }
        return parts;
    }

    private static int overlap(String haystack, String sentence) {
        int hits = 0;
        for (String token : tokens(sentence)) {
            if (token.length() < 3) {
                continue;
            }
            if (haystack.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : EvidenceText.normalize(text).split("[^a-z0-9]+")) {
            if (!part.isBlank()) {
                tokens.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private record Scored(
            String id,
            int score,
            String sentence,
            V3RequirementCoverage coverage,
            Set<String> terms
    ) {
    }
}
