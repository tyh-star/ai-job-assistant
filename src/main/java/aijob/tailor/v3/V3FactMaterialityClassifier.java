package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.ConservativeMaterialFacts;
import aijob.util.EvidenceText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns PROTECTED / OPTIONAL_CONTEXT / EXTRACTION_NOISE. Never downgrades a
 * real duty, technology, constraint, JavaDoc, test, credential, or metric.
 */
public final class V3FactMaterialityClassifier {

    private static final Set<String> OPTIONAL_PERSONS = Set.of(
            "friend", "friends", "classmate", "classmates", "buddy", "buddies",
            "roommate", "roommates"
    );
    private static final Set<String> PROTECTED_PERSONS = Set.of(
            "professor", "ta", "manager", "supervisor", "client", "customer",
            "instructor", "mentor"
    );
    private static final Set<String> VERB_AS_OBJECT = Set.of(
            "define", "defined", "defining",
            "utilize", "utilized", "utilizing",
            "ensure", "ensured", "ensuring",
            "provide", "provided", "providing",
            "perform", "performed", "performing",
            "include", "included", "including",
            "applied", "apply", "applying",
            "delivered", "deliver", "delivering",
            "communicated", "communicate", "communicating",
            "resolve", "resolved", "resolving",
            "created", "create", "creating",
            "crafted", "craft",
            "write", "wrote", "writing",
            "talk", "talked", "talking",
            "make", "made", "making"
    );
    private static final Set<String> OPTIONAL_PURPOSE = Set.of(
            "planning", "purpose"
    );
    private static final Set<String> FRAGMENT_NOUNS = Set.of(
            "string", "strings", "pattern", "patterns"
    );
    private static final Set<String> MUST_PROTECT_OBJECT = Set.of(
            "website", "scope", "requirements", "requirement", "uml", "diagram",
            "diagrams", "cart", "authentication", "budget", "components", "component",
            "algorithm", "inheritance", "encapsulation", "modularity", "javadoc",
            "documentation", "testing", "quality", "ambiguities", "ambiguity"
    );
    private static final Pattern PUNCT_OR_CONJ = Pattern.compile("^[\\p{Punct}]+$");

    private V3FactMaterialityClassifier() {
    }

    public static List<V3Fact> classify(List<V3Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return facts == null ? List.of() : facts;
        }
        Set<String> techTokens = new LinkedHashSet<>();
        for (V3Fact fact : facts) {
            if (fact.kind() == V3FactKind.TECHNOLOGY) {
                String token = token(fact);
                techTokens.add(token);
                for (String part : token.split("\\s+")) {
                    if (part.length() >= 4) {
                        techTokens.add(part);
                    }
                }
            }
        }
        List<V3Fact> classified = new ArrayList<>(facts.size());
        for (V3Fact fact : facts) {
            classified.add(fact.withMateriality(decide(fact, techTokens)));
        }
        return List.copyOf(classified);
    }

    static V3FactMateriality decide(V3Fact fact, Set<String> techTokens) {
        if (fact == null) {
            return V3FactMateriality.EXTRACTION_NOISE;
        }
        if (mustProtect(fact)) {
            return V3FactMateriality.PROTECTED;
        }
        if (fact.kind() == V3FactKind.OBJECT) {
            String token = token(fact);
            if (token.isBlank() || PUNCT_OR_CONJ.matcher(token).matches()) {
                return V3FactMateriality.EXTRACTION_NOISE;
            }
            if (MUST_PROTECT_OBJECT.contains(token)) {
                return V3FactMateriality.PROTECTED;
            }
            if (PROTECTED_PERSONS.contains(token)) {
                return V3FactMateriality.PROTECTED;
            }
            if (OPTIONAL_PERSONS.contains(token)) {
                return V3FactMateriality.OPTIONAL_CONTEXT;
            }
            if (OPTIONAL_PURPOSE.contains(token)) {
                return V3FactMateriality.OPTIONAL_CONTEXT;
            }
            if (VERB_AS_OBJECT.contains(token) || ActionConceptRegistry.isActionToken(token)) {
                return V3FactMateriality.EXTRACTION_NOISE;
            }
            if (FRAGMENT_NOUNS.contains(token) && ConservativeMaterialFacts.isGenericToken(token)) {
                return V3FactMateriality.EXTRACTION_NOISE;
            }
            if (FRAGMENT_NOUNS.contains(token)) {
                return V3FactMateriality.EXTRACTION_NOISE;
            }
            if (!token.isBlank() && (techTokens.contains(token) || containedInPhrase(token, techTokens))) {
                return V3FactMateriality.EXTRACTION_NOISE;
            }
            if (fact.factKey().startsWith("domain:")) {
                return V3FactMateriality.PROTECTED;
            }
            return V3FactMateriality.PROTECTED;
        }
        return V3FactMateriality.PROTECTED;
    }

    private static boolean mustProtect(V3Fact fact) {
        V3FactKind kind = fact.kind();
        if (kind == V3FactKind.TECHNOLOGY
                || kind == V3FactKind.ACTION
                || kind == V3FactKind.TASK
                || kind == V3FactKind.CONSTRAINT
                || kind == V3FactKind.OUTCOME
                || kind == V3FactKind.NUMBER
                || kind == V3FactKind.IDENTITY
                || kind == V3FactKind.USER_CONFIRMED) {
            return true;
        }
        if (V3CoverageMorphology.leadFact(fact)
                || V3CoverageMorphology.documentationFact(fact)
                || V3CoverageMorphology.cartFact(fact)) {
            return true;
        }
        return MUST_PROTECT_OBJECT.contains(token(fact));
    }

    private static boolean containedInPhrase(String token, Set<String> phrases) {
        if (token == null || token.length() < 4) {
            return false;
        }
        for (String phrase : phrases) {
            if (phrase != null && phrase.contains(token) && !phrase.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String token(V3Fact fact) {
        String key = fact.factKey() == null ? "" : fact.factKey();
        int colon = key.indexOf(':');
        String raw = colon >= 0 ? key.substring(colon + 1) : key;
        return EvidenceText.normalize(raw.isBlank() ? fact.surface() : raw);
    }
}
