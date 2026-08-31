package aijob.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Occupation-agnostic action families for responsibility matching.
 * Variants are controlled morphology, not semantic similarity.
 */
public final class ActionConceptRegistry {

    public static final String DESIGN = "design";
    public static final String DEVELOP = "develop";
    public static final String IMPLEMENT = "implement";
    public static final String BUILD = "build";
    public static final String INTEGRATE = "integrate";
    public static final String DEBUG = "debug";
    public static final String TEST = "test";
    public static final String DOCUMENT = "document";
    public static final String OPTIMIZE = "optimize";
    public static final String DEPLOY = "deploy";
    public static final String MAINTAIN = "maintain";
    public static final String SUPPORT = "support";
    public static final String MONITOR = "monitor";
    public static final String COLLABORATE = "collaborate";
    public static final String ANALYZE = "analyze";
    public static final String TROUBLESHOOT = "troubleshoot";
    public static final String REVIEW = "review";

    private static final List<Family> FAMILIES = List.of(
            family(DESIGN, "designing", "designed", "design"),
            family(DEVELOP, "developing", "developed", "develop"),
            family(IMPLEMENT, "implementation", "implementing", "implemented", "implement"),
            family(BUILD, "building", "built", "build"),
            family(INTEGRATE, "integration", "integrating", "integrated", "integrate"),
            family(DEBUG, "debugging", "debugged", "debug"),
            family(TEST, "testing", "tested", "tests", "test"),
            family(DOCUMENT, "documentation", "documenting", "documented", "document"),
            family(OPTIMIZE, "optimization", "optimizing", "optimized", "optimize"),
            family(DEPLOY, "deployment", "deploying", "deployed", "deploy"),
            family(MAINTAIN, "maintenance", "maintaining", "maintained", "maintain"),
            family(SUPPORT, "supporting", "supported", "support"),
            family(MONITOR, "monitoring", "monitored", "monitor"),
            family(COLLABORATE, "collaboration", "collaborating", "collaborated", "collaborate"),
            family(ANALYZE, "analyzing", "analyzed", "analysis", "analyze"),
            family(TROUBLESHOOT, "troubleshooting", "troubleshot", "troubleshoot"),
            family(REVIEW, "reviewing", "reviewed", "reviews", "review")
    );
    private static final Map<String, Family> BY_ID = index();

    private ActionConceptRegistry() {
    }

    public static List<String> familyIds() {
        List<String> ids = new ArrayList<>();
        for (Family family : FAMILIES) {
            ids.add(family.id);
        }
        return ids;
    }

    public static List<String> variants(String familyId) {
        Family family = BY_ID.get(familyId);
        return family == null ? List.of() : family.variants;
    }

    public static boolean isActionToken(String token) {
        String normalized = EvidenceText.normalize(token);
        if (normalized.isEmpty()) {
            return false;
        }
        for (Family family : FAMILIES) {
            if (family.id.equals(normalized) || family.variants.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static LinkedHashSet<String> actionsIn(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String haystack = EvidenceText.normalize(text);
        if (haystack.isEmpty()) {
            return found;
        }
        for (Family family : FAMILIES) {
            if (family.pattern.matcher(haystack).find()) {
                found.add(family.id);
            }
        }
        return found;
    }

    public static boolean contains(String text, String familyId) {
        Family family = BY_ID.get(familyId);
        if (family == null) {
            return false;
        }
        String haystack = EvidenceText.normalize(text);
        return !haystack.isEmpty() && family.pattern.matcher(haystack).find();
    }

    public static String matchedVariant(String text, String familyId) {
        Family family = BY_ID.get(familyId);
        if (family == null) {
            return null;
        }
        String haystack = EvidenceText.normalize(text);
        if (haystack.isEmpty()) {
            return null;
        }
        Matcher matcher = family.pattern.matcher(haystack);
        return matcher.find() ? matcher.group() : null;
    }

    private static Map<String, Family> index() {
        Map<String, Family> index = new LinkedHashMap<>();
        for (Family family : FAMILIES) {
            index.put(family.id, family);
        }
        return Map.copyOf(index);
    }

    private static Family family(String id, String... variants) {
        List<String> ordered = List.of(variants);
        StringBuilder alternation = new StringBuilder();
        for (String variant : ordered) {
            if (!alternation.isEmpty()) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(variant));
        }
        Pattern pattern = Pattern.compile("\\b(?:" + alternation + ")\\b");
        return new Family(id, ordered, pattern);
    }

    private record Family(String id, List<String> variants, Pattern pattern) {
    }
}
