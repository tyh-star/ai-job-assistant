package aijob.tailor.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Candidate-level joint search over ORIGINAL / CLARITY_FIRST / JD_TARGETED nodes.
 * The model still generates whole entries; this class only coordinates existing text.
 */
public final class V3HybridSelector {

    public static final int BEAM_WIDTH = 16;
    static final int EXHAUSTIVE_LIMIT = 243;

    private static final Pattern STACKED_STACK = Pattern.compile(
            "(?i)\\b(?:using|in|with)\\s+java\\s+spring(?:\\s+boot)?\\b");
    private static final Pattern NATURAL_STACK = Pattern.compile(
            "(?i)\\bjava\\s+and\\s+spring\\s+boot\\b");
    private static final Pattern UTILIZING = Pattern.compile("(?i)\\butilizing\\b");
    private static final Pattern ONTO_AWS = Pattern.compile("(?i)\\bonto\\s+aws\\b");
    private static final Pattern TO_AWS = Pattern.compile("(?i)\\bto\\s+aws\\b");
    private static final Pattern NOMINAL = Pattern.compile(
            "(?i)\\bthe\\s+(?:development|implementation|deployment|integration|design)\\s+of\\b");

    private V3HybridSelector() {
    }

    public record Hybrid(
            V3EntryRewriteVariant variant,
            List<V3NodeSource> nodeSources,
            V3QualityScore score,
            V3FactCoverage.Result coverage,
            boolean valid,
            boolean admitted,
            String validatorCode,
            String detail
    ) {
        public Hybrid {
            nodeSources = nodeSources == null ? List.of() : List.copyOf(nodeSources);
            validatorCode = validatorCode == null ? "" : validatorCode;
            detail = detail == null ? "" : detail;
        }

        public List<String> bullets() {
            if (variant == null) {
                return List.of();
            }
            List<String> bullets = new ArrayList<>();
            for (V3RewrittenNode node : variant.rewrittenNodes()) {
                bullets.add(node.newText());
            }
            return bullets;
        }
    }

    public record Result(Hybrid hybrid1, Hybrid hybrid2) {
        public static Result empty() {
            return new Result(null, null);
        }

        public boolean hasAdmitted() {
            return (hybrid1 != null && hybrid1.admitted())
                    || (hybrid2 != null && hybrid2.admitted());
        }
    }

    public static Result select(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            List<V3WritableNode> writable,
            List<String> originalBullets,
            V3EntryRewriteVariant clarity,
            V3EntryRewriteVariant targeted,
            V3QualityScore originalScore,
            List<V3RankedRequirement> ranked) {

        if (graph == null || writable == null || writable.isEmpty() || originalBullets == null) {
            return Result.empty();
        }
        List<String> original = pad(originalBullets, writable.size());
        List<String> clarityTexts = variantTexts(clarity, writable, original);
        List<String> targetedTexts = variantTexts(targeted, writable, original);
        List<List<V3NodeSource>> options = optionsFor(original, clarityTexts, targetedTexts);
        List<List<V3NodeSource>> combinations = search(options, original, clarityTexts, targetedTexts);
        Map<String, Hybrid> unique = new LinkedHashMap<>();
        for (List<V3NodeSource> sources : combinations) {
            if (allSame(sources)) {
                continue;
            }
            List<String> bullets = combine(sources, original, clarityTexts, targetedTexts);
            String key = String.join("\n", bullets);
            if (unique.containsKey(key) || sameAs(bullets, original)
                    || sameAs(bullets, clarityTexts) || sameAs(bullets, targetedTexts)) {
                continue;
            }
            Hybrid evaluated = evaluate(graphs, graph, writable, original, bullets, sources, originalScore, ranked);
            if (evaluated.admitted()) {
                unique.put(key, evaluated);
            }
        }
        List<Hybrid> rankedHybrids = new ArrayList<>(unique.values());
        rankedHybrids.sort(Comparator
                .comparingInt((Hybrid hybrid) -> hybrid.score() == null ? 0 : hybrid.score().total())
                .thenComparingInt(hybrid -> hybrid.score() == null ? 0 : hybrid.score().clarityNaturalness())
                .thenComparingInt(hybrid -> hybrid.score() == null ? 0 : hybrid.score().factIntegrity())
                .thenComparingInt(hybrid -> -originalCount(hybrid.nodeSources()))
                .reversed());
        Hybrid first = rankedHybrids.isEmpty() ? null : rankedHybrids.get(0);
        Hybrid second = rankedHybrids.size() < 2 ? null : rankedHybrids.get(1);
        return new Result(
                first == null ? null : withType(first, V3VariantType.HYBRID_1),
                second == null ? null : withType(second, V3VariantType.HYBRID_2));
    }

    static List<List<V3NodeSource>> search(
            List<List<V3NodeSource>> options,
            List<String> original,
            List<String> clarity,
            List<String> targeted) {
        int product = 1;
        for (List<V3NodeSource> option : options) {
            product *= Math.max(1, option.size());
            if (product > EXHAUSTIVE_LIMIT) {
                break;
            }
        }
        if (product <= EXHAUSTIVE_LIMIT) {
            return exhaustive(options);
        }
        return beam(options, original, clarity, targeted);
    }

    private static List<List<V3NodeSource>> exhaustive(List<List<V3NodeSource>> options) {
        List<List<V3NodeSource>> beams = new ArrayList<>();
        beams.add(List.of());
        for (List<V3NodeSource> option : options) {
            List<List<V3NodeSource>> next = new ArrayList<>();
            for (List<V3NodeSource> prefix : beams) {
                for (V3NodeSource source : option) {
                    List<V3NodeSource> extended = new ArrayList<>(prefix);
                    extended.add(source);
                    next.add(extended);
                }
            }
            beams = next;
        }
        return beams;
    }

    private static List<List<V3NodeSource>> beam(
            List<List<V3NodeSource>> options,
            List<String> original,
            List<String> clarity,
            List<String> targeted) {
        List<Beam> beams = new ArrayList<>();
        beams.add(new Beam(List.of(), List.of(), 0));
        for (int i = 0; i < options.size(); i++) {
            List<Beam> next = new ArrayList<>();
            for (Beam prefix : beams) {
                for (V3NodeSource source : options.get(i)) {
                    List<V3NodeSource> sources = new ArrayList<>(prefix.sources);
                    sources.add(source);
                    List<String> bullets = new ArrayList<>(prefix.bullets);
                    bullets.add(textAt(i, source, original, clarity, targeted));
                    next.add(new Beam(sources, bullets, heuristic(bullets)));
                }
            }
            next.sort(Comparator.comparingInt(Beam::heuristic).reversed());
            beams = next.size() <= BEAM_WIDTH ? next : new ArrayList<>(next.subList(0, BEAM_WIDTH));
        }
        List<List<V3NodeSource>> result = new ArrayList<>();
        for (Beam beam : beams) {
            result.add(beam.sources);
        }
        return result;
    }

    private static Hybrid evaluate(
            V3ResumeFactGraphs graphs,
            V3EntryFactGraph graph,
            List<V3WritableNode> writable,
            List<String> original,
            List<String> bullets,
            List<V3NodeSource> sources,
            V3QualityScore originalScore,
            List<V3RankedRequirement> ranked) {
        V3EntryRewriteVariant variant = variantOf(V3VariantType.HYBRID_1, writable, bullets);
        V3EntryRewrite rewrite = new V3EntryRewrite(
                graph.canonicalEntryKey(),
                graph.originalHeading(),
                graph.originalDateText(),
                bullets);
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(graphs, rewrite);
        if (!decision.accepted()) {
            return new Hybrid(variant, sources, null, null, false, false, decision.reasonCode(), decision.detail());
        }
        V3FactCoverage.Result coverage = V3FactCoverage.compute(graph, original, bullets);
        V3QualityScore score = V3EntryQualityScorer.scoreVariant(graph, original, bullets, ranked);
        boolean admitted = V3EntryQualityScorer.admit(score, originalScore);
        return new Hybrid(variant, sources, score, coverage, true, admitted, decision.reasonCode(), decision.detail());
    }

    private static V3EntryRewriteVariant variantOf(
            V3VariantType type,
            List<V3WritableNode> writable,
            List<String> bullets) {
        List<V3RewrittenNode> nodes = new ArrayList<>();
        for (int i = 0; i < writable.size(); i++) {
            String text = i < bullets.size() ? bullets.get(i) : writable.get(i).originalText();
            nodes.add(new V3RewrittenNode(
                    writable.get(i).nodeId(),
                    text,
                    List.of(),
                    List.of(),
                    "HYBRID",
                    "coherent-hybrid"));
        }
        return new V3EntryRewriteVariant(
                type,
                V3RewriteDecision.REWRITE,
                nodes,
                "hybrid",
                List.of(),
                List.of(),
                List.of(),
                V3SelfCheck.empty());
    }

    private static Hybrid withType(Hybrid hybrid, V3VariantType type) {
        V3EntryRewriteVariant next = new V3EntryRewriteVariant(
                type,
                hybrid.variant().decision(),
                hybrid.variant().rewrittenNodes(),
                hybrid.variant().entrySummary(),
                hybrid.variant().factsMoved(),
                hybrid.variant().factsRetained(),
                hybrid.variant().factsOmitted(),
                hybrid.variant().selfCheck());
        return new Hybrid(
                next,
                hybrid.nodeSources(),
                hybrid.score(),
                hybrid.coverage(),
                hybrid.valid(),
                hybrid.admitted(),
                hybrid.validatorCode(),
                hybrid.detail());
    }

    private static List<List<V3NodeSource>> optionsFor(
            List<String> original,
            List<String> clarity,
            List<String> targeted) {
        List<List<V3NodeSource>> options = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            List<V3NodeSource> option = new ArrayList<>();
            option.add(V3NodeSource.ORIGINAL);
            if (distinct(clarity.get(i), original.get(i))) {
                option.add(V3NodeSource.CLARITY_FIRST);
            }
            if (distinct(targeted.get(i), original.get(i)) && distinct(targeted.get(i), clarity.get(i))) {
                option.add(V3NodeSource.JD_TARGETED);
            } else if (distinct(targeted.get(i), original.get(i)) && !option.contains(V3NodeSource.JD_TARGETED)) {
                option.add(V3NodeSource.JD_TARGETED);
            }
            options.add(option);
        }
        return options;
    }

    private static List<String> variantTexts(
            V3EntryRewriteVariant variant,
            List<V3WritableNode> writable,
            List<String> original) {
        if (variant == null || variant.decision() != V3RewriteDecision.REWRITE
                || variant.rewrittenNodes().size() != writable.size()) {
            return original;
        }
        return pad(variant.bulletsInNodeOrder(writable), writable.size());
    }

    private static List<String> combine(
            List<V3NodeSource> sources,
            List<String> original,
            List<String> clarity,
            List<String> targeted) {
        List<String> bullets = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            bullets.add(textAt(i, sources.get(i), original, clarity, targeted));
        }
        return bullets;
    }

    private static String textAt(
            int index,
            V3NodeSource source,
            List<String> original,
            List<String> clarity,
            List<String> targeted) {
        return switch (source) {
            case ORIGINAL -> original.get(index);
            case CLARITY_FIRST -> clarity.get(index);
            case JD_TARGETED -> targeted.get(index);
        };
    }

    private static List<String> pad(List<String> bullets, int size) {
        List<String> padded = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            padded.add(i < bullets.size() && bullets.get(i) != null ? bullets.get(i) : "");
        }
        return padded;
    }

    private static boolean allSame(List<V3NodeSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return true;
        }
        V3NodeSource first = sources.get(0);
        for (V3NodeSource source : sources) {
            if (source != first) {
                return false;
            }
        }
        return true;
    }

    private static int originalCount(List<V3NodeSource> sources) {
        int count = 0;
        if (sources == null) {
            return 0;
        }
        for (V3NodeSource source : sources) {
            if (source == V3NodeSource.ORIGINAL) {
                count++;
            }
        }
        return count;
    }

    private static boolean sameAs(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!normalize(left.get(i)).equals(normalize(right.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean distinct(String left, String right) {
        return !normalize(left).equals(normalize(right));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    static int heuristic(List<String> bullets) {
        int score = 0;
        String text = String.join(" ", bullets == null ? List.of() : bullets);
        if (NATURAL_STACK.matcher(text).find()) {
            score += 12;
        }
        if (STACKED_STACK.matcher(text).find()) {
            score -= 10;
        }
        if (UTILIZING.matcher(text).find()) {
            score -= 6;
        }
        if (TO_AWS.matcher(text).find() && !ONTO_AWS.matcher(text).find()) {
            score += 6;
        }
        if (ONTO_AWS.matcher(text).find()) {
            score -= 4;
        }
        if (NOMINAL.matcher(text).find()) {
            score -= 8;
        }
        if (text.toLowerCase(Locale.ROOT).contains("restful")) {
            score += 3;
        }
        return score;
    }

    private record Beam(List<V3NodeSource> sources, List<String> bullets, int heuristic) {
    }
}
