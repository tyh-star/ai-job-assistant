package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.EvidenceText;
import aijob.util.ObjectDomainRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * V3-2.4 action-object provenance. Pairings are taken from the original bullet
 * that contains both sides. Same-entry Cartesian mixes are rejected.
 * This is a closed catalog, not an open synonym system.
 */
public final class V3ActionObjectProvenanceGate {

    private static final Set<String> PRODUCT_OBJECTS = Set.of(
            "shopping_website", "application", "project", "initial_version", "backend");

    private static final List<ObjectPattern> OBJECTS = List.of(
            object("shopping_website", "shopping\\s+websites?", "websites?"),
            object("rest_apis", "restful\\s+apis?", "rest\\s+apis?"),
            object("initial_version", "initial\\s+versions?"),
            object("backend", "backend\\s+services?", "backend"),
            object("uml", "uml\\s+class\\s+diagrams?", "uml"),
            object("authentication", "user\\s+authentication", "authentication", "authenticating"),
            object("cart", "shopping\\s+carts?", "carts?"),
            object("product_updates", "product\\s+updates?", "products?"),
            object("algorithm", "algorithms?"),
            object("components", "components?"),
            object("budget", "budgets?"),
            object("application", "applications?"),
            object("project", "projects?"),
            object("aws", "aws")
    );

    private static final Pattern MVC = Pattern.compile("(?i)\\bmvc\\b");
    private static final Pattern TEAMMATE = Pattern.compile("(?i)\\bteammates?\\b");
    private static final Pattern PARTNER = Pattern.compile("(?i)\\bpartners?\\b");
    private static final Pattern FRIEND = Pattern.compile("(?i)\\bfriends?\\b");
    private static final Pattern STAKEHOLDER = Pattern.compile("(?i)\\bstakeholders?\\b");
    private static final Pattern CLIENT = Pattern.compile("(?i)\\bclients?\\b");
    private static final Pattern PROFESSOR = Pattern.compile("(?i)\\bprofessors?\\b");
    private static final Pattern TA = Pattern.compile("(?i)\\bta\\b");
    private static final Set<String> PURPOSE_ADJUNCT_ACTIONS = Set.of(
            ActionConceptRegistry.SUPPORT
    );

    private V3ActionObjectProvenanceGate() {
    }

    public static List<V3ActionObjectRelation> relationsFrom(V3EntryFactGraph graph) {
        List<V3ActionObjectRelation> relations = new ArrayList<>();
        if (graph == null) {
            return relations;
        }
        List<String> bullets = graph.originalBulletTexts();
        for (int i = 0; i < bullets.size(); i++) {
            String bullet = bullets.get(i);
            String nodeId = nodeIdFor(graph, i, bullet);
            relations.addAll(relationsIn(bullet, nodeId));
        }
        for (V3Fact fact : graph.facts()) {
            if (!userConfirmed(fact)) {
                continue;
            }
            relations.addAll(relationsIn(fact.surface(), "user-confirmed"));
        }
        return List.copyOf(relations);
    }

    public static V3ValidationDecision check(V3EntryFactGraph graph, List<String> rewrittenBullets) {
        if (graph == null || rewrittenBullets == null) {
            return V3ValidationDecision.accept();
        }
        String originalText = String.join("\n", graph.originalBulletTexts());
        String rewrittenText = String.join("\n", rewrittenBullets);
        String context = optionalContextMutation(originalText, rewrittenText);
        if (context != null) {
            return V3ValidationDecision.reject(V3ValidationDecision.OPTIONAL_CONTEXT_MUTATION, context);
        }
        Set<String> allowedActions = new LinkedHashSet<>();
        Set<String> allowedPairs = new LinkedHashSet<>();
        Set<String> productActions = new LinkedHashSet<>();
        for (V3ActionObjectRelation relation : relationsFrom(graph)) {
            allowedActions.add(relation.action());
            allowedPairs.add(relation.relationKey());
            if (PRODUCT_OBJECTS.contains(relation.objectKey())) {
                productActions.add(relation.action());
            }
        }
        allowedActions.addAll(userConfirmedActions(graph));
        boolean backendEvidence = hasBackendEvidence(originalText);
        for (String bullet : rewrittenBullets) {
            for (V3ActionObjectRelation relation : relationsIn(bullet, "rewritten")) {
                if (!allowedActions.contains(relation.action())) {
                    return V3ValidationDecision.reject(
                            V3ValidationDecision.INTRODUCED_RELATION, relation.action());
                }
                if (allowedPairs.contains(relation.relationKey())) {
                    continue;
                }
                if (PRODUCT_OBJECTS.contains(relation.objectKey())
                        && productActions.contains(relation.action())
                        && (!"backend".equals(relation.objectKey()) || backendEvidence)) {
                    continue;
                }
                return V3ValidationDecision.reject(
                        V3ValidationDecision.INTRODUCED_RELATION, relation.relationKey());
            }
        }
        return V3ValidationDecision.accept();
    }

    public static boolean hasMaintainHallucination(V3EntryFactGraph graph, List<String> rewrittenBullets) {
        if (graph == null || rewrittenBullets == null) {
            return false;
        }
        if (ActionConceptRegistry.contains(String.join(" ", graph.originalBulletTexts()), ActionConceptRegistry.MAINTAIN)
                || userConfirmedActions(graph).contains(ActionConceptRegistry.MAINTAIN)) {
            return false;
        }
        return ActionConceptRegistry.contains(String.join(" ", rewrittenBullets), ActionConceptRegistry.MAINTAIN);
    }

    public static boolean hasFriendSubstitution(String original, String rewritten) {
        return optionalContextMutation(original, rewritten) != null
                && (TEAMMATE.matcher(rewritten == null ? "" : rewritten).find()
                || PARTNER.matcher(rewritten == null ? "" : rewritten).find());
    }

    static List<V3ActionObjectRelation> relationsIn(String text, String sourceNodeId) {
        List<V3ActionObjectRelation> relations = new ArrayList<>();
        LinkedHashSet<String> actions = mainActionsIn(text);
        LinkedHashSet<String> objects = objectsIn(text);
        if (hasBackendEvidence(text)) {
            objects.add("backend");
        }
        for (String action : actions) {
            if (objects.isEmpty()) {
                relations.add(new V3ActionObjectRelation(action, "", sourceNodeId, action));
                continue;
            }
            for (String object : objects) {
                relations.add(new V3ActionObjectRelation(action, object, sourceNodeId, action + " " + object));
            }
        }
        return relations;
    }

    static LinkedHashSet<String> mainActionsIn(String text) {
        LinkedHashSet<String> actions = ActionConceptRegistry.actionsIn(text);
        actions.removeIf(action -> PURPOSE_ADJUNCT_ACTIONS.contains(action) && onlyPurposeInfinitive(text, action));
        return actions;
    }

    static boolean onlyPurposeInfinitive(String text, String action) {
        String haystack = EvidenceText.normalize(text);
        if (haystack.isEmpty()) {
            return false;
        }
        int finite = 0;
        int purpose = 0;
        for (String variant : ActionConceptRegistry.variants(action)) {
            java.util.regex.Matcher matcher = Pattern.compile("\\b" + Pattern.quote(variant) + "\\b").matcher(haystack);
            while (matcher.find()) {
                int start = matcher.start();
                if (start >= 3 && "to ".equals(haystack.substring(start - 3, start))) {
                    purpose++;
                } else {
                    finite++;
                }
            }
        }
        return finite == 0 && purpose > 0;
    }

    static LinkedHashSet<String> objectsIn(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String haystack = EvidenceText.normalize(text);
        if (haystack.isEmpty()) {
            return found;
        }
        for (ObjectPattern object : OBJECTS) {
            if (object.pattern.matcher(haystack).find()) {
                found.add(object.key);
            }
        }
        return found;
    }

    static boolean hasBackendEvidence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ObjectDomainRegistry.contains(text, ObjectDomainRegistry.BACKEND)
                || MVC.matcher(text).find()
                || objectsIn(text).contains("backend")
                || objectsIn(text).contains("rest_apis");
    }

    private static String optionalContextMutation(String original, String rewritten) {
        String before = original == null ? "" : original;
        String after = rewritten == null ? "" : rewritten;
        if (introduces(TEAMMATE, before, after)) {
            return "friend->teammate";
        }
        if (introduces(PARTNER, before, after)) {
            return "friend->partner";
        }
        if (introduces(STAKEHOLDER, before, after) && (PROFESSOR.matcher(before).find() || TA.matcher(before).find())) {
            return "professor/ta->stakeholder";
        }
        if (introduces(CLIENT, before, after) && (PROFESSOR.matcher(before).find() || TA.matcher(before).find())) {
            return "professor/ta->client";
        }
        if (FRIEND.matcher(before).find()
                && !FRIEND.matcher(after).find()
                && (TEAMMATE.matcher(after).find() || PARTNER.matcher(after).find())) {
            return "friend substitution";
        }
        return null;
    }

    private static boolean introduces(Pattern token, String original, String rewritten) {
        return token.matcher(rewritten).find() && !token.matcher(original).find();
    }

    private static Set<String> userConfirmedActions(V3EntryFactGraph graph) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (V3Fact fact : graph.facts()) {
            if (!userConfirmed(fact)) {
                continue;
            }
            actions.addAll(ActionConceptRegistry.actionsIn(fact.surface()));
            if (!fact.aliases().isEmpty()) {
                actions.addAll(ActionConceptRegistry.actionsIn(String.join(" ", fact.aliases())));
            }
        }
        return actions;
    }

    private static boolean userConfirmed(V3Fact fact) {
        return fact != null
                && fact.sourceType() == V3FactSource.USER_CONFIRMED
                && fact.polarity() == V3FactPolarity.CONFIRMED;
    }

    private static String nodeIdFor(V3EntryFactGraph graph, int index, String bullet) {
        for (V3Fact fact : graph.facts()) {
            if (fact.sourceNodeIds().size() == 1 && fact.kind() == V3FactKind.ACTION && fact.coveredBy(bullet)) {
                return fact.sourceNodeIds().get(0);
            }
        }
        return "bullet-" + index;
    }

    private static ObjectPattern object(String key, String... phrases) {
        StringBuilder alternation = new StringBuilder();
        for (String phrase : phrases) {
            if (!alternation.isEmpty()) {
                alternation.append('|');
            }
            alternation.append(phrase);
        }
        Pattern pattern = Pattern.compile("\\b(?:" + alternation + ")\\b");
        return new ObjectPattern(key, pattern);
    }

    private record ObjectPattern(String key, Pattern pattern) {
    }
}
