package aijob.tailor.v3;

import aijob.util.ClaimLedger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable facts for one ClaimLedger entry. BASIC and ENHANCED share this graph;
 * ENHANCED only adds exact-key user evidence.
 */
public record V3EntryFactGraph(
        String canonicalEntryKey,
        String ledgerEntryId,
        String sectionRole,
        List<String> sourceNodeIds,
        List<String> originalBulletTexts,
        String originalHeading,
        String originalDateText,
        List<V3Fact> facts
) {
    public V3EntryFactGraph {
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        ledgerEntryId = ledgerEntryId == null ? "" : ledgerEntryId;
        sectionRole = sectionRole == null ? "" : sectionRole;
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
        originalBulletTexts = originalBulletTexts == null ? List.of() : List.copyOf(originalBulletTexts);
        originalHeading = originalHeading == null ? "" : originalHeading;
        originalDateText = originalDateText == null ? "" : originalDateText;
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static V3EntryFactGraph empty() {
        return new V3EntryFactGraph("", "", "", List.of(), List.of(), "", "", List.of());
    }

    public String originalEntryText() {
        StringBuilder text = new StringBuilder(originalHeading);
        if (!originalDateText.isBlank()) {
            text.append(' ').append(originalDateText);
        }
        for (String bullet : originalBulletTexts) {
            text.append(' ').append(bullet);
        }
        return text.toString();
    }

    public List<V3Fact> factsOf(V3FactKind kind) {
        List<V3Fact> matched = new ArrayList<>();
        for (V3Fact fact : facts) {
            if (fact.kind() == kind) {
                matched.add(fact);
            }
        }
        return matched;
    }

    public Set<String> keysOf(V3FactKind kind) {
        Set<String> keys = new LinkedHashSet<>();
        for (V3Fact fact : factsOf(kind)) {
            keys.add(fact.factKey());
        }
        return keys;
    }

    public boolean hasFactKey(String factKey) {
        if (factKey == null) {
            return false;
        }
        for (V3Fact fact : facts) {
            if (factKey.equals(fact.factKey())) {
                return true;
            }
        }
        return false;
    }

    public List<V3Fact> factsOfMateriality(V3FactMateriality materiality) {
        List<V3Fact> matched = new ArrayList<>();
        for (V3Fact fact : facts) {
            if (fact.materiality() == materiality) {
                matched.add(fact);
            }
        }
        return matched;
    }

    public List<String> keysOfMateriality(V3FactMateriality materiality) {
        List<String> keys = new ArrayList<>();
        for (V3Fact fact : factsOfMateriality(materiality)) {
            keys.add(fact.factKey());
        }
        return keys;
    }
}
