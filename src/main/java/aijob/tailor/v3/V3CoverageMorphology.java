package aijob.tailor.v3;

import aijob.util.ActionConceptRegistry;
import aijob.util.EvidenceText;
import aijob.util.ObjectDomainRegistry;
import aijob.util.TermAliasNormalizer;

import java.util.regex.Pattern;

/**
 * Controlled morphology on top of the existing Action / Term / Object registries.
 * This is not an open synonym list: Java is not JavaScript, Spring is not Spring Boot,
 * and managing a cart is not managing a team.
 */
public final class V3CoverageMorphology {

    private static final Pattern LEAD = Pattern.compile("(?i)\\b(?:lead|led)\\b");
    private static final Pattern DEVELOPMENT = Pattern.compile("(?i)\\bdevelopment\\b");
    private static final Pattern JAVADOC = Pattern.compile("(?i)\\b(?:javadoc|java\\s*docs?|java\\s+doc(?:umentation|\\s+string)?)\\b");
    private static final Pattern CART = Pattern.compile(
            "(?i)\\bshopping\\s+carts?(?:\\s+(?:management|managements|operations?|functions?))?\\b|\\bcarts?\\b");
    private static final Pattern JAVA_WORD = Pattern.compile("(?i)\\bjava\\b");
    private static final Pattern JAVASCRIPT = Pattern.compile("(?i)\\bjavascript\\b");
    private static final Pattern SPRING_BOOT = Pattern.compile("(?i)\\bspring\\s+boot\\b");
    private static final Pattern SPRING_ALONE = Pattern.compile("(?i)\\bspring\\b");
    private static final Pattern TEAM = Pattern.compile(
            "(?i)\\b(?:team|teams|engineers|staff|department|organization)\\b");
    private static final Pattern CART_OBJECT = Pattern.compile("(?i)\\b(?:cart|carts|shopping\\s+cart)\\b");
    private static final Pattern OOP = Pattern.compile("(?i)\\boop\\b");
    private static final Pattern OBJECT_ORIENTED = Pattern.compile(
            "(?i)\\bobject[\\s-]*oriented(?:\\s+programming)?\\b");

    private V3CoverageMorphology() {
    }

    public static boolean covers(V3Fact fact, String text) {
        if (fact == null || text == null || text.isBlank()) {
            return false;
        }
        if (leadFact(fact) && LEAD.matcher(text).find()) {
            return true;
        }
        if (documentationFact(fact) && coversDocumentation(text)) {
            return true;
        }
        if (fact.kind() == V3FactKind.ACTION && fact.factKey().startsWith("action:")) {
            return coversAction(text, fact.factKey().substring("action:".length()));
        }
        if (cartFact(fact) && CART.matcher(text).find()) {
            return true;
        }
        if (javaFact(fact)) {
            return coversJava(text);
        }
        if (springBootFact(fact)) {
            return SPRING_BOOT.matcher(text).find();
        }
        if (oopFact(fact)) {
            return OOP.matcher(text).find() || OBJECT_ORIENTED.matcher(text).find();
        }
        if (fact.kind() == V3FactKind.TASK || fact.kind() == V3FactKind.OBJECT) {
            return coversInflectedToken(text, tokenOf(fact));
        }
        return false;
    }

    public static boolean coversAction(String text, String family) {
        if (family == null || family.isBlank()) {
            return false;
        }
        if ("lead".equalsIgnoreCase(family) && text != null && LEAD.matcher(text).find()) {
            return true;
        }
        if (ActionConceptRegistry.contains(text, family)) {
            return true;
        }
        if (ActionConceptRegistry.DEVELOP.equals(family) && text != null && DEVELOPMENT.matcher(text).find()) {
            return true;
        }
        if (ActionConceptRegistry.DOCUMENT.equals(family) && coversDocumentation(text)) {
            return true;
        }
        return false;
    }

    public static boolean coversDocumentation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (ObjectDomainRegistry.contains(text, ObjectDomainRegistry.DOCUMENTATION)) {
            return true;
        }
        if (ActionConceptRegistry.contains(text, ActionConceptRegistry.DOCUMENT)) {
            return true;
        }
        return JAVADOC.matcher(text).find();
    }

    public static boolean coversJava(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean java = JAVA_WORD.matcher(text).find();
        boolean javascript = JAVASCRIPT.matcher(text).find();
        return java && (!javascript || remainingJavaAfterScript(text));
    }

    public static boolean springAloneDoesNotCoverSpringBoot(String text) {
        if (text == null) {
            return true;
        }
        return SPRING_ALONE.matcher(text).find() && !SPRING_BOOT.matcher(text).find();
    }

    public static boolean manageCartIsNotManageTeam(String text) {
        if (text == null) {
            return false;
        }
        return CART_OBJECT.matcher(text).find() && !TEAM.matcher(text).find();
    }

    public static boolean containsTeamManagement(String text) {
        return text != null && TEAM.matcher(text).find()
                && Pattern.compile("(?i)\\b(?:manage|managed|managing|management)\\b").matcher(text).find();
    }

    static boolean documentationFact(V3Fact fact) {
        String hay = (fact.factKey() + " " + fact.surface()).toLowerCase();
        return hay.contains("javadoc")
                || hay.contains("java doc")
                || hay.contains("documentation")
                || "domain:documentation".equalsIgnoreCase(fact.factKey())
                || (fact.kind() == V3FactKind.ACTION && fact.factKey().equals("action:document"));
    }

    static boolean javaFact(V3Fact fact) {
        String hay = (fact.factKey() + " " + fact.surface()).toLowerCase();
        return fact.kind() == V3FactKind.TECHNOLOGY
                && TermAliasNormalizer.containsPhrase(EvidenceText.normalize(hay), "java")
                && !hay.contains("javascript")
                && !hay.contains("javadoc")
                && !hay.contains("java doc");
    }

    static boolean oopFact(V3Fact fact) {
        String hay = fact.factKey() + " " + fact.surface();
        return OOP.matcher(hay).find() || OBJECT_ORIENTED.matcher(hay).find();
    }

    static boolean coversInflectedToken(String text, String token) {
        if (text == null || token == null || token.length() < 4) {
            return false;
        }
        String normalized = EvidenceText.normalize(token);
        if (normalized.isBlank() || normalized.contains(" ")) {
            return false;
        }
        if (normalized.endsWith("tion") && normalized.length() > 6) {
            String stem = normalized.substring(0, normalized.length() - 3);
            return Pattern.compile("(?i)\\b" + Pattern.quote(stem) + "(?:e|es|ed|ing|ion)?\\b")
                    .matcher(text)
                    .find();
        }
        return Pattern.compile("(?i)\\b" + Pattern.quote(normalized) + "(?:s|es)?\\b")
                .matcher(text)
                .find();
    }

    private static String tokenOf(V3Fact fact) {
        String key = fact.factKey() == null ? "" : fact.factKey();
        int colon = key.indexOf(':');
        String raw = colon >= 0 ? key.substring(colon + 1) : key;
        return EvidenceText.normalize(raw.isBlank() ? fact.surface() : raw);
    }

    static boolean springBootFact(V3Fact fact) {
        String hay = (fact.factKey() + " " + fact.surface()).toLowerCase();
        return hay.contains("spring boot") || hay.contains("spring-boot");
    }

    static boolean cartFact(V3Fact fact) {
        String hay = (fact.factKey() + " " + fact.surface()).toLowerCase();
        return hay.contains("cart");
    }

    static boolean leadFact(V3Fact fact) {
        return LEAD.matcher(fact.surface()).find()
                || LEAD.matcher(fact.normalizedFact()).find()
                || "action:lead".equals(fact.factKey());
    }

    private static boolean remainingJavaAfterScript(String text) {
        String stripped = JAVASCRIPT.matcher(text).replaceAll(" ");
        return JAVA_WORD.matcher(stripped).find();
    }
}
