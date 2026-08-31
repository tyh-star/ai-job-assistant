package aijob.util;

import aijob.dto.RewriteIntent;
import aijob.dto.UpliftType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Surface-only rewrites that do not fix grammar and do not add fact value.
 */
public final class SurfaceRewriteEquivalence {

    private static final Pattern BULLET = Pattern.compile("^[\\s]*[●•▪◦‣∙·\\-*]\\s*");
    private static final Set<String> PRESENT_EXPERIENCE = Set.of(
            "design", "implement", "develop", "build", "integrate", "create", "deploy"
    );
    private static final Set<String> FILLER = Set.of(
            "a", "an", "the", "that", "which", "to", "in"
    );
    private static final Map<String, String> TOKEN_SYNONYMS = Map.ofEntries(
            Map.entry("help", "help"),
            Map.entry("helps", "help"),
            Map.entry("helping", "help"),
            Map.entry("assist", "help"),
            Map.entry("assists", "help"),
            Map.entry("assisting", "help"),
            Map.entry("assisted", "help"),
            Map.entry("aid", "help"),
            Map.entry("aids", "help"),
            Map.entry("aiding", "help"),
            Map.entry("aided", "help"),
            Map.entry("enable", "help"),
            Map.entry("enables", "help"),
            Map.entry("enabling", "help"),
            Map.entry("enabled", "help"),
            Map.entry("select", "select"),
            Map.entry("selects", "select"),
            Map.entry("selecting", "select"),
            Map.entry("selected", "select"),
            Map.entry("choose", "select"),
            Map.entry("chooses", "select"),
            Map.entry("choosing", "select"),
            Map.entry("chose", "select"),
            Map.entry("chosen", "select"),
            Map.entry("pick", "select"),
            Map.entry("picks", "select"),
            Map.entry("picking", "select"),
            Map.entry("optimal", "optimal"),
            Map.entry("best", "optimal"),
            Map.entry("given", "given"),
            Map.entry("specified", "given"),
            Map.entry("utilize", "use"),
            Map.entry("utilizes", "use"),
            Map.entry("utilizing", "use"),
            Map.entry("utilized", "use"),
            Map.entry("use", "use"),
            Map.entry("uses", "use"),
            Map.entry("using", "use"),
            Map.entry("used", "use"),
            Map.entry("leverage", "use"),
            Map.entry("leverages", "use"),
            Map.entry("leveraging", "use"),
            Map.entry("leveraged", "use"),
            Map.entry("employ", "use"),
            Map.entry("employs", "use"),
            Map.entry("employing", "use"),
            Map.entry("employed", "use"),
            Map.entry("application", "project"),
            Map.entry("applications", "project"),
            Map.entry("project", "project"),
            Map.entry("projects", "project")
    );

    private SurfaceRewriteEquivalence() {
    }

    public static boolean isLowValueSurface(String original, String newText) {
        if (original == null || newText == null || original.isBlank() || newText.isBlank()) {
            return false;
        }
        if (hasValuableGrammarFix(original, newText)) {
            return false;
        }
        String left = canonical(original);
        String right = canonical(newText);
        return !left.isBlank() && left.equals(right);
    }

    public static boolean hasValuableGrammarFix(String original, String newText) {
        if (original == null || newText == null) {
            return false;
        }
        String left = original.toLowerCase(Locale.ROOT);
        String right = newText.toLowerCase(Locale.ROOT);
        if (left.contains("managements") && !right.contains("managements")
                && (right.contains("management") || right.contains("operations")
                || right.contains("workflows") || right.contains("functions"))) {
            return true;
        }
        String firstOriginal = ConservativeTailorPlanner.firstWord(original);
        String firstNew = ConservativeTailorPlanner.firstWord(newText);
        return PRESENT_EXPERIENCE.contains(firstOriginal)
                && firstNew.endsWith("ed")
                && !firstOriginal.equals(firstNew);
    }

    public static boolean grammarFixEstablished(String original, String newText, String intent, String upliftType) {
        return hasValuableGrammarFix(original, newText)
                || RewriteIntent.GRAMMAR_FIX.equals(intent)
                || UpliftType.GRAMMAR_FIX.equals(upliftType);
    }

    static String canonical(String text) {
        String value = BULLET.matcher(text == null ? "" : text).replaceFirst("");
        value = value.toLowerCase(Locale.ROOT);
        value = value.replaceAll("that dynamically (\\w+)", "dynamically $1");
        value = value.replaceAll("to dynamically (\\w+)", "dynamically $1");
        value = value.replaceAll("\\bsuggests\\b", "suggest");
        value = value.replace("cpu, gpu & ram", "cpu gpu ram");
        value = value.replace("cpu, gpu, and ram", "cpu gpu ram");
        value = value.replace("such as cpu gpu ram", "cpu gpu ram");
        value = value.replace("such as cpu, gpu, and ram", "cpu gpu ram");
        value = value.replace("set budget", "given budget");
        value = value.replaceAll("\\busers\\s+(?:in|to)\\s+", "users ");
        value = value.replaceAll("[^a-z0-9+#]+", " ");
        StringBuilder canonical = new StringBuilder();
        for (String token : value.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String mapped = TOKEN_SYNONYMS.getOrDefault(token, token);
            if (FILLER.contains(mapped)) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append(' ');
            }
            canonical.append(mapped);
        }
        return canonical.toString();
    }
}
