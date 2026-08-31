package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.ConservativeEvidencePacket;
import aijob.util.ExactTermPolicy;
import aijob.util.ObjectDomainRegistry;
import aijob.util.TermAliasNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-only fact provenance. Model-declared keys and citations are never
 * the source of used/retained/moved/omitted/introduced sets.
 */
public final class V3FactCoverageResolver {

    private V3FactCoverageResolver() {
    }

    public static V3FactCoverage.Result resolve(
            List<String> originalEntry,
            List<String> rewrittenEntry,
            V3EntryFactGraph factGraph) {
        List<String> original = originalEntry == null ? List.of() : originalEntry;
        List<String> rewritten = rewrittenEntry == null ? List.of() : rewrittenEntry;
        if (factGraph == null) {
            return V3FactCoverage.Result.empty();
        }
        String originalText = String.join(" ", original);
        String rewrittenText = String.join(" ", rewritten);
        LinkedHashSet<String> used = new LinkedHashSet<>();
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        LinkedHashSet<String> moved = new LinkedHashSet<>();
        LinkedHashSet<String> omittedProtected = new LinkedHashSet<>();
        LinkedHashSet<String> omittedOptional = new LinkedHashSet<>();
        LinkedHashSet<String> noise = new LinkedHashSet<>();
        LinkedHashSet<String> supporting = new LinkedHashSet<>();
        for (V3Fact fact : factGraph.facts()) {
            boolean inOriginal = fact.coveredBy(originalText);
            boolean inRewritten = fact.coveredBy(rewrittenText);
            if (fact.materiality() == V3FactMateriality.EXTRACTION_NOISE) {
                if (inOriginal && !inRewritten) {
                    noise.add(fact.factKey());
                }
                continue;
            }
            if (inOriginal && !inRewritten) {
                if (fact.materiality() == V3FactMateriality.OPTIONAL_CONTEXT) {
                    omittedOptional.add(fact.factKey());
                } else if (fact.protectedFact()) {
                    omittedProtected.add(fact.factKey());
                }
                continue;
            }
            if (!inRewritten) {
                continue;
            }
            used.add(fact.factKey());
            supporting.addAll(fact.sourceNodeIds());
            int originalIndex = firstCoveredIndex(fact, original);
            int rewrittenIndex = firstCoveredIndex(fact, rewritten);
            if (originalIndex >= 0 && rewrittenIndex >= 0 && originalIndex != rewrittenIndex) {
                moved.add(fact.factKey());
            } else {
                retained.add(fact.factKey());
            }
        }
        List<String> introduced = introducedTech(originalText, rewrittenText, factGraph);
        List<String> duplicate = duplicateTech(factGraph, rewritten);
        LinkedHashSet<String> usedAll = new LinkedHashSet<>(used);
        usedAll.addAll(retained);
        usedAll.addAll(moved);
        return new V3FactCoverage.Result(
                List.copyOf(usedAll),
                List.copyOf(retained),
                List.copyOf(moved),
                List.copyOf(omittedProtected),
                List.copyOf(introduced),
                List.copyOf(duplicate),
                List.copyOf(supporting),
                List.copyOf(omittedOptional),
                List.copyOf(noise));
    }

    public static V3FactCoverage.Result resolve(V3EntryFactGraph originalEntry, List<String> rewrittenEntry) {
        if (originalEntry == null) {
            return V3FactCoverage.Result.empty();
        }
        return resolve(originalEntry.originalBulletTexts(), rewrittenEntry, originalEntry);
    }

    static boolean textAllowsTech(String originalText, V3EntryFactGraph graph, String tech) {
        if (tech == null || tech.isBlank()) {
            return true;
        }
        if (TermAliasNormalizer.matches(originalText, tech)) {
            return true;
        }
        for (String allowed : ConservativeEvidencePacket.extractNormalizedTech(originalText)) {
            if (allowed.equalsIgnoreCase(tech) || TermAliasNormalizer.matches(allowed, tech)) {
                return true;
            }
        }
        if (graph != null) {
            for (V3Fact fact : graph.factsOf(V3FactKind.TECHNOLOGY)) {
                if (TermAliasNormalizer.matches(fact.surface(), tech)) {
                    return true;
                }
            }
            for (V3Fact fact : graph.factsOf(V3FactKind.USER_CONFIRMED)) {
                if (fact.surface() != null && TermAliasNormalizer.matches(fact.surface(), tech)) {
                    return true;
                }
                for (String alias : fact.aliases()) {
                    if (alias != null && TermAliasNormalizer.matches(alias, tech)) {
                        return true;
                    }
                }
            }
        }
        if (ObjectDomainRegistry.contains(originalText, ObjectDomainRegistry.DOCUMENTATION)
                && ObjectDomainRegistry.contains(tech, ObjectDomainRegistry.DOCUMENTATION)) {
            return true;
        }
        if (ActionConceptRegistry.contains(originalText, ActionConceptRegistry.DOCUMENT)
                && ActionConceptRegistry.contains(tech, ActionConceptRegistry.DOCUMENT)) {
            return true;
        }
        if (ActionConceptRegistry.contains(originalText, ActionConceptRegistry.DEPLOY)
                && ActionConceptRegistry.contains(tech, ActionConceptRegistry.DEPLOY)) {
            return true;
        }
        if (ActionConceptRegistry.contains(originalText, ActionConceptRegistry.TEST)
                && ActionConceptRegistry.contains(tech, ActionConceptRegistry.TEST)) {
            return true;
        }
        if (ActionConceptRegistry.contains(originalText, ActionConceptRegistry.COLLABORATE)
                && ActionConceptRegistry.contains(tech, ActionConceptRegistry.COLLABORATE)) {
            return true;
        }
        return ExactTermPolicy.isLowInformation(tech);
    }

    static List<String> validCitations(List<String> cited, Set<String> allowedNodeIds) {
        if (cited == null || cited.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String id : cited) {
            if (id != null && allowedNodeIds.contains(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    static List<String> invalidCitations(List<String> cited, Set<String> allowedNodeIds) {
        if (cited == null || cited.isEmpty()) {
            return List.of();
        }
        List<String> invalid = new ArrayList<>();
        for (String id : cited) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!allowedNodeIds.contains(id)) {
                invalid.add(id);
            }
        }
        return invalid;
    }

    private static List<String> introducedTech(
            String originalText,
            String rewrittenText,
            V3EntryFactGraph graph) {
        List<String> extra = new ArrayList<>();
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(rewrittenText)) {
            if (!textAllowsTech(originalText, graph, tech)) {
                extra.add(tech);
            }
        }
        return extra;
    }

    private static List<String> duplicateTech(V3EntryFactGraph graph, List<String> bullets) {
        List<String> duplicate = new ArrayList<>();
        if (graph == null || bullets == null || bullets.size() < 2) {
            return duplicate;
        }
        for (V3Fact fact : graph.factsOf(V3FactKind.TECHNOLOGY)) {
            int hits = 0;
            for (String bullet : bullets) {
                if (fact.coveredBy(bullet)) {
                    hits++;
                }
            }
            int originalHits = 0;
            for (String bullet : graph.originalBulletTexts()) {
                if (fact.coveredBy(bullet)) {
                    originalHits++;
                }
            }
            if (hits > 1 && hits > originalHits) {
                duplicate.add(fact.factKey());
            }
        }
        return duplicate;
    }

    private static int firstCoveredIndex(V3Fact fact, List<String> bullets) {
        for (int i = 0; i < bullets.size(); i++) {
            if (fact.coveredBy(bullets.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
