package aijob.util;

import aijob.dto.RewriteIntent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Distinctive facts a rewrite must keep to fully replace another same-entry bullet.
 * Generic nouns such as project/application/system are not supporting hits.
 */
public final class ConservativeMaterialFacts {

    public enum Status {
        UNUSED,
        PARTIALLY_USED,
        FULLY_SUBSUMED
    }

    public enum Category {
        ROLE,
        TECH,
        TASK,
        CONSTRAINT,
        USER_CONTEXT,
        RESULT,
        OBJECT
    }

    public record Fact(String key, Category category, List<String> aliases) {
        boolean distinctive() {
            return category != Category.ROLE;
        }
    }

    public static final Set<String> GENERIC_TOKENS = Set.of(
            "project", "projects", "application", "applications", "system", "systems",
            "service", "services", "platform", "platforms", "version", "initial",
            "feature", "features", "process", "processes", "work", "data",
            "information", "solution", "solutions", "tool", "tools", "using", "used",
            "based", "successfully", "management", "managements", "workflows", "pattern", "principles"
    );

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "into", "onto",
            "within", "given", "using", "used", "than", "then", "such", "also"
    );
    private static final List<String> TASK_HINTS = List.of(
            "budget", "components", "authentication", "product", "cart", "deploy"
    );
    private static final Set<String> VAGUE_MODIFIERS = Set.of(
            "optimal", "best", "exceptional", "clean", "thoroughly", "effectively",
            "dynamically", "successfully", "given", "initial", "personal", "multiple"
    );
    private static final Pattern ACRONYM = Pattern.compile("\\b[A-Z]{2,5}\\b");
    private static final Pattern USER_SELECT = Pattern.compile(
            "(?i)(?:help(?:s|ing)?|assist(?:s|ing)?|aid(?:s|ing)?)\\s+(?:the\\s+)?users?"
                    + "|users?\\s+(?:in\\s+)?(?:select|choos|pick)|user\\s+selection"
    );
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{2,}");

    private ConservativeMaterialFacts() {
    }

    public static List<Fact> extract(String text) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Fact> facts = new ArrayList<>();
        String original = text == null ? "" : text;
        String lower = original.toLowerCase(Locale.ROOT);
        for (String family : ConservativeEvidencePacket.CORE_ACTION_FAMILIES) {
            if (ActionConceptRegistry.contains(original, family)) {
                add(facts, seen, new Fact(
                        "role:" + family,
                        Category.ROLE,
                        aliasesForRole(family)));
            }
        }
        for (String tech : ConservativeEvidencePacket.extractNormalizedTech(original)) {
            add(facts, seen, new Fact(
                    "tech:" + tech.toLowerCase(Locale.ROOT),
                    Category.TECH,
                    techAliases(tech)));
        }
        for (String hint : TASK_HINTS) {
            if (lower.contains(hint)) {
                add(facts, seen, new Fact(
                        "task:" + hint,
                        "budget".equals(hint) ? Category.CONSTRAINT : Category.TASK,
                        taskAliases(hint)));
            }
        }
        if (USER_SELECT.matcher(original).find()) {
            add(facts, seen, new Fact(
                    "user:select",
                    Category.USER_CONTEXT,
                    List.of(
                            "help users", "helps users", "helping users",
                            "assist users", "assisting users", "assist the user",
                            "aid users", "aiding users",
                            "users select", "users choose", "users pick",
                            "users in select", "users in choosing", "users in picking",
                            "user select", "user selection", "help the user"
                    )));
        }
        if (ActionConceptRegistry.contains(original, ActionConceptRegistry.DEPLOY)
                && (lower.contains("deploy") || lower.contains("aws"))) {
            add(facts, seen, new Fact(
                    "result:deploy",
                    Category.RESULT,
                    List.of("deploy", "deployed", "deploying", "deployment")));
        }
        var acronyms = ACRONYM.matcher(original);
        while (acronyms.find()) {
            String acronym = acronyms.group().toLowerCase(Locale.ROOT);
            if (GENERIC_TOKENS.contains(acronym) || seen.contains("tech:" + acronym)) {
                continue;
            }
            add(facts, seen, new Fact("object:" + acronym, Category.OBJECT, List.of(acronym)));
        }
        var matcher = TOKEN.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group().replaceAll("[.]+$", "");
            if (token.length() < 5
                    || GENERIC_TOKENS.contains(token)
                    || STOP.contains(token)
                    || VAGUE_MODIFIERS.contains(token)
                    || ActionConceptRegistry.isActionToken(token)
                    || token.startsWith("suggest")
                    || token.startsWith("utiliz")
                    || token.startsWith("leverag")
                    || token.startsWith("includ")
                    || token.startsWith("clarif")
                    || token.startsWith("handle")
                    || token.startsWith("update")
                    || token.startsWith("help")
                    || token.equals("select")
                    || token.equals("choose")
                    || token.equals("pick")
                    || (seen.contains("task:cart") && token.startsWith("shop"))
                    || (seen.contains("user:select") && (token.equals("users") || token.equals("user")))) {
                continue;
            }
            String key = "object:" + token;
            if (seen.contains(key) || seen.contains("tech:" + token) || seen.contains("task:" + token)) {
                continue;
            }
            add(facts, seen, new Fact(key, Category.OBJECT, objectAliases(token)));
        }
        return List.copyOf(facts);
    }

    public static List<Fact> distinctive(List<Fact> facts) {
        List<Fact> distinctive = new ArrayList<>();
        if (facts == null) {
            return distinctive;
        }
        for (Fact fact : facts) {
            if (fact != null && fact.distinctive()) {
                distinctive.add(fact);
            }
        }
        return distinctive;
    }

    public static boolean covers(Fact fact, String text) {
        if (fact == null || text == null || text.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String alias : fact.aliases()) {
            if (alias != null && !alias.isBlank() && containsAlias(haystack, alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean containsAlias(String haystack, String alias) {
        if (haystack == null || alias == null || alias.isBlank()) {
            return false;
        }
        if (alias.length() <= 3) {
            return Pattern.compile("\\b" + Pattern.quote(alias) + "\\b").matcher(haystack).find();
        }
        return haystack.contains(alias);
    }

    public static Status coverage(String sourceOriginal, String newText) {
        List<Fact> facts = distinctive(extract(sourceOriginal));
        if (facts.isEmpty()) {
            return Status.UNUSED;
        }
        int hit = 0;
        for (Fact fact : facts) {
            if (covers(fact, newText)) {
                hit++;
            }
        }
        if (hit == 0) {
            return Status.UNUSED;
        }
        if (hit == facts.size()) {
            return Status.FULLY_SUBSUMED;
        }
        return Status.PARTIALLY_USED;
    }

    public static boolean fullySubsumes(String sourceOriginal, String newText) {
        return coverage(sourceOriginal, newText) == Status.FULLY_SUBSUMED;
    }

    public static boolean contributesConcreteFact(
            String targetOriginal,
            String supportingOriginal,
            String newText) {

        List<Fact> supportingFacts = distinctive(extract(supportingOriginal));
        if (supportingFacts.isEmpty()) {
            return false;
        }
        boolean contributed = false;
        for (Fact fact : supportingFacts) {
            if (!covers(fact, newText)) {
                continue;
            }
            if (isGenericFact(fact)) {
                continue;
            }
            if (!covers(fact, targetOriginal)) {
                contributed = true;
            }
        }
        return contributed;
    }

    public static Set<String> coveredKeys(String text) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Fact fact : distinctive(extract(text))) {
            if (covers(fact, text)) {
                keys.add(fact.key());
            }
        }
        return keys;
    }

    public static Set<String> uniqueKeys(String focusText, List<String> otherTexts) {
        Set<String> mine = coveredKeys(focusText);
        for (String other : otherTexts) {
            mine.removeAll(coveredKeys(other));
        }
        return mine;
    }

    public static double batchRetention(List<String> originalTexts, List<String> newTexts) {
        List<Fact> needed = new ArrayList<>();
        for (String original : originalTexts) {
            needed.addAll(distinctive(extract(original)));
        }
        if (needed.isEmpty()) {
            return 1.0;
        }
        String union = String.join(" ", newTexts == null ? List.of() : newTexts);
        int hit = 0;
        for (Fact fact : needed) {
            if (covers(fact, union)) {
                hit++;
            }
        }
        return hit / (double) needed.size();
    }

    public static boolean isGenericToken(String token) {
        return token != null && GENERIC_TOKENS.contains(token.toLowerCase(Locale.ROOT));
    }

    private static boolean isGenericFact(Fact fact) {
        if (fact == null) {
            return true;
        }
        for (String alias : fact.aliases()) {
            if (alias != null && alias.indexOf(' ') < 0 && GENERIC_TOKENS.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Fact> facts, Set<String> seen, Fact fact) {
        if (fact == null || fact.key() == null || !seen.add(fact.key())) {
            return;
        }
        facts.add(fact);
    }

    private static List<String> aliasesForRole(String family) {
        List<String> aliases = new ArrayList<>();
        aliases.add(family);
        aliases.addAll(ActionConceptRegistry.variants(family));
        return aliases;
    }

    private static List<String> techAliases(String tech) {
        String lower = tech.toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        aliases.add(lower);
        if ("oop".equals(lower) || "object-oriented programming".equals(lower)) {
            aliases.add("oop");
            aliases.add("object-oriented");
            aliases.add("object oriented");
        }
        if ("spring boot".equals(lower)) {
            aliases.add("spring boot");
        }
        if (lower.startsWith("restful")) {
            aliases.add("restful");
            aliases.add("rest");
        }
        return aliases;
    }

    private static List<String> taskAliases(String hint) {
        if ("budget".equals(hint)) {
            return List.of("budget", "budget-aligned", "within budget", "given budget");
        }
        if ("components".equals(hint)) {
            return List.of("component", "components");
        }
        if ("cart".equals(hint)) {
            return List.of("cart", "shopping-cart", "shopping cart");
        }
        if ("authentication".equals(hint)) {
            return List.of("authentication", "auth");
        }
        return List.of(hint);
    }

    private static List<String> objectAliases(String token) {
        List<String> aliases = new ArrayList<>();
        aliases.add(token);
        aliases.add(token + "s");
        if (token.endsWith("s") && token.length() > 5) {
            aliases.add(token.substring(0, token.length() - 1));
        }
        if (token.endsWith("ies") && token.length() > 6) {
            aliases.add(token.substring(0, token.length() - 3) + "y");
        }
        return aliases;
    }

    static boolean skipSummary(String intent) {
        return RewriteIntent.SUMMARY_REWRITE.equals(intent);
    }
}
