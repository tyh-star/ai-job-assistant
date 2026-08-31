package aijob.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controlled object/domain families for responsibility matching.
 * Unknown tokens are not aliases.
 */
public final class ObjectDomainRegistry {

    public static final String BACKEND = "BACKEND";
    public static final String FRONTEND = "FRONTEND";
    public static final String DATABASE = "DATABASE";
    public static final String DEPLOYMENT = "DEPLOYMENT";
    public static final String TESTING = "TESTING";
    public static final String DOCUMENTATION = "DOCUMENTATION";
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String AGILE = "AGILE";

    private static final List<Family> FAMILIES = List.of(
            family(BACKEND,
                    "authentication handled by the server", "authentication handled by server",
                    "server-side", "server side", "spring boot", "restful apis", "restful api",
                    "rest apis", "rest api", "data-access", "data access", "backend",
                    "restful", "controller", "controllers", "repository", "repositories",
                    "persistence", "rest"),
            family(FRONTEND,
                    "client-side", "client side", "front-end", "frontend", "react", "vue",
                    "html", "css", "ui"),
            family(DATABASE,
                    "data-access", "data access", "database", "persistence", "mysql", "sql",
                    "query", "queries"),
            family(DEPLOYMENT,
                    "deployment", "hosting", "environment", "cloud", "aws"),
            family(TESTING,
                    "integration test", "automated test", "unit test", "integration tests",
                    "automated tests", "unit tests"),
            family(DOCUMENTATION,
                    "api documentation", "java doc", "javadoc"),
            family(PERFORMANCE,
                    "throughput", "performance", "latency"),
            family(AGILE,
                    "stand-up", "standup", "backlog", "sprint", "scrum", "agile")
    );

    private ObjectDomainRegistry() {
    }

    public static List<String> familyIds() {
        List<String> ids = new ArrayList<>();
        for (Family family : FAMILIES) {
            ids.add(family.id);
        }
        return ids;
    }

    public static List<String> phrases(String familyId) {
        for (Family family : FAMILIES) {
            if (family.id.equals(familyId)) {
                return family.phrases;
            }
        }
        return List.of();
    }

    public static LinkedHashSet<String> domainsIn(String text) {
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
        return domainsIn(text).contains(familyId);
    }

    public static boolean satisfies(String required, Set<String> found) {
        if (required == null || found == null || found.isEmpty()) {
            return false;
        }
        if (found.contains(required)) {
            return true;
        }
        return BACKEND.equals(required) && found.contains(DATABASE);
    }

    public static boolean satisfiesAll(List<String> required, Set<String> found) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (String domain : required) {
            if (!satisfies(domain, found)) {
                return false;
            }
        }
        return true;
    }

    public static boolean conflicts(List<String> required, Set<String> found) {
        if (required == null || found == null || found.isEmpty()) {
            return false;
        }
        if (required.contains(BACKEND) && found.contains(FRONTEND) && !satisfies(BACKEND, found)) {
            return true;
        }
        return required.contains(FRONTEND) && found.contains(BACKEND) && !satisfies(FRONTEND, found);
    }

    public static boolean nodeMatchesAny(String text, List<String> required) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        Set<String> found = domainsIn(text);
        for (String domain : required) {
            if (satisfies(domain, found)) {
                return true;
            }
        }
        return false;
    }

    private static Family family(String id, String... phrases) {
        List<String> ordered = List.of(phrases);
        StringBuilder alternation = new StringBuilder();
        for (String phrase : ordered) {
            if (!alternation.isEmpty()) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(phrase));
        }
        Pattern pattern = Pattern.compile("\\b(?:" + alternation + ")\\b");
        return new Family(id, ordered, pattern);
    }

    private record Family(String id, List<String> phrases, Pattern pattern) {
    }
}
