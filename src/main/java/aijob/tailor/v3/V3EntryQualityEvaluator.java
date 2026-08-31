package aijob.tailor.v3;

import aijob.util.EvidenceText;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * V3 quality floor for a whole entry. Independent of ConservativeRewriteQualityEvaluator.
 * Allows professional wording (handle/support, onto/to, cart manage). Rejects role
 * inflation and empty rhetorical swaps.
 */
public final class V3EntryQualityEvaluator {

    private static final Set<String> ROLE_VERBS = Set.of(
            "led", "lead", "directed", "direct", "owned", "own",
            "oversaw", "oversee", "headed", "head", "supervised", "supervise",
            "spearheaded", "spearhead"
    );
    private static final Set<String> TEAM_OBJECTS = Set.of(
            "team", "teams", "engineers", "staff", "people", "organization",
            "department", "initiative", "initiatives"
    );
    private static final Set<String> OBJECT_MANAGE = Set.of(
            "cart", "carts", "api", "apis", "authentication", "session",
            "sessions", "queue", "queues", "request", "requests", "inventory"
    );
    private static final Pattern EXECUTED_WRAP = Pattern.compile(
            "(?i)\\bexecuted\\s+the\\s+(?:deployment|implementation|development|creation)\\b");
    private static final Pattern COMMUNICATE = Pattern.compile("(?i)\\bcommunicat(?:e|ed|ing|ion)\\b");
    private static final Pattern ENGAGE = Pattern.compile("(?i)\\bengaged?\\b");

    private V3EntryQualityEvaluator() {
    }

    public static boolean hasRoleEscalation(String originalEntry, String rewrittenEntry) {
        String original = originalEntry == null ? "" : originalEntry;
        String rewritten = rewrittenEntry == null ? "" : rewrittenEntry;
        if (hasLeadershipClaim(rewritten) && !hasLeadershipClaim(original)) {
            return true;
        }
        if (hasLeadershipClaim(original)) {
            return false;
        }
        return leadingRoleVerb(rewritten) && !leadingRoleVerb(original);
    }

    public static boolean isLowValue(String originalEntry, String rewrittenEntry) {
        String original = originalEntry == null ? "" : originalEntry;
        String rewritten = rewrittenEntry == null ? "" : rewrittenEntry;
        if (rewritten.isBlank() || EvidenceText.normalize(original).equals(EvidenceText.normalize(rewritten))) {
            return true;
        }
        if (EXECUTED_WRAP.matcher(rewritten).find() && !EXECUTED_WRAP.matcher(original).find()) {
            return true;
        }
        boolean onlyEngage = ENGAGE.matcher(rewritten).find()
                && COMMUNICATE.matcher(original).find()
                && !ENGAGE.matcher(original).find();
        if (onlyEngage && similarContent(original, rewritten)) {
            return true;
        }
        return false;
    }

    private static boolean hasLeadershipClaim(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String verb : ROLE_VERBS) {
            if (!containsWord(lower, verb)) {
                continue;
            }
            if ("manage".equals(verb) || "managed".equals(verb) || "managing".equals(verb)) {
                continue;
            }
            return true;
        }
        return containsWord(lower, "managed") && hasTeamObject(lower) && !hasObjectManage(lower);
    }

    private static boolean leadingRoleVerb(String text) {
        String first = firstWord(text);
        return ROLE_VERBS.contains(first) && !"managed".equals(first) && !"manage".equals(first);
    }

    private static boolean hasTeamObject(String lower) {
        for (String token : TEAM_OBJECTS) {
            if (containsWord(lower, token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObjectManage(String lower) {
        for (String token : OBJECT_MANAGE) {
            if (containsWord(lower, token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean similarContent(String original, String rewritten) {
        return collapse(original).equals(collapse(rewritten));
    }

    private static String collapse(String text) {
        return EvidenceText.normalize(text)
                .replace("communicated effectively with", " ")
                .replace("communicated effectively", " ")
                .replace("communicated with", " ")
                .replace("communicated", " ")
                .replace("engaged with", " ")
                .replace("engaged", " ")
                .replace("onto", "to")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    private static String firstWord(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = text.replaceFirst("^[\\s●•\\-]+", "").strip();
        int space = stripped.indexOf(' ');
        String word = space < 0 ? stripped : stripped.substring(0, space);
        return word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
