package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.ObjectDomainRegistry;
import aijob.util.TermAliasNormalizer;

import java.util.List;
import java.util.regex.Pattern;

public record V3Fact(
        String canonicalEntryKey,
        V3FactKind factType,
        String normalizedFact,
        String surface,
        List<String> aliases,
        List<String> sourceNodeIds,
        V3FactSource sourceType,
        V3FactPolarity polarity,
        Long evidenceItemId,
        V3FactMateriality materiality
) {
    public V3Fact {
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        factType = factType == null ? V3FactKind.TASK : factType;
        normalizedFact = normalizedFact == null ? "" : normalizedFact;
        surface = surface == null ? "" : surface;
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
        sourceType = sourceType == null ? V3FactSource.RESUME : sourceType;
        polarity = polarity == null ? V3FactPolarity.CONFIRMED : polarity;
        materiality = materiality == null ? V3FactMateriality.PROTECTED : materiality;
    }

    public V3Fact(
            String canonicalEntryKey,
            V3FactKind factType,
            String normalizedFact,
            String surface,
            List<String> aliases,
            List<String> sourceNodeIds,
            V3FactSource sourceType,
            V3FactPolarity polarity,
            Long evidenceItemId) {
        this(
                canonicalEntryKey,
                factType,
                normalizedFact,
                surface,
                aliases,
                sourceNodeIds,
                sourceType,
                polarity,
                evidenceItemId,
                V3FactMateriality.PROTECTED);
    }

    public V3Fact withMateriality(V3FactMateriality next) {
        return new V3Fact(
                canonicalEntryKey,
                factType,
                normalizedFact,
                surface,
                aliases,
                sourceNodeIds,
                sourceType,
                polarity,
                evidenceItemId,
                next);
    }

    public V3FactKind kind() {
        return factType;
    }

    public String factKey() {
        return normalizedFact;
    }

    public boolean protectedFact() {
        return materiality == V3FactMateriality.PROTECTED;
    }

    public boolean coveredBy(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (V3CoverageMorphology.covers(this, text)) {
            return true;
        }
        if (!surface.isBlank() && TermAliasNormalizer.matches(text, surface)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank() && TermAliasNormalizer.matches(text, alias)) {
                return true;
            }
        }
        if (factType == V3FactKind.ACTION && normalizedFact.startsWith("action:")) {
            String family = normalizedFact.substring("action:".length());
            if (V3CoverageMorphology.coversAction(text, family)
                    || ActionConceptRegistry.contains(text, family)) {
                return true;
            }
        }
        if (factType == V3FactKind.OBJECT && normalizedFact.startsWith("domain:")) {
            String domain = normalizedFact.substring("domain:".length());
            if (ObjectDomainRegistry.contains(text, domain)) {
                return true;
            }
        }
        if (leadFact() && LEAD.matcher(text).find()) {
            return true;
        }
        return false;
    }

    private boolean leadFact() {
        return V3CoverageMorphology.leadFact(this);
    }

    private static final Pattern LEAD = Pattern.compile("(?i)\\b(?:lead|led)\\b");
}
