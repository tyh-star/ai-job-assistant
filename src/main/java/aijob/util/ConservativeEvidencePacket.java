package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Same-entry fact packet for constrained CONSERVATIVE rewrites.
 * Supporting facts come only from the target's ClaimLedger entry.
 */
public record ConservativeEvidencePacket(
        String targetNodeId,
        String entryKey,
        List<String> requiredFacts,
        List<String> availableSupportingFacts,
        List<String> supportingNodeIds,
        List<String> forbiddenClaims,
        int maxCharacters
) {

    public static final List<String> DEFAULT_FORBIDDEN_CLAIMS = List.of(
            "unfounded leadership (led/directed/managed/oversaw/owned without original evidence)",
            "unfounded tools or skills",
            "unfounded numbers or metrics",
            "unfounded outcomes or impact claims",
            "education status upgrade (in-progress to graduated)"
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?){1,2}\\d{4}"
    );
    private static final List<String> TECH_PHRASES_LONGEST_FIRST = List.of(
            "object-oriented programming",
            "spring boot",
            "restful apis",
            "restful api",
            "rest apis",
            "rest api",
            "java doc string",
            "c/c++"
    );
    private static final List<String> TASK_HINTS = List.of(
            "budget", "components", "authentication", "product", "cart", "deploy"
    );
    static final Set<String> CORE_ACTION_FAMILIES = Set.of(
            ActionConceptRegistry.DESIGN,
            ActionConceptRegistry.DEVELOP,
            ActionConceptRegistry.IMPLEMENT,
            ActionConceptRegistry.BUILD,
            ActionConceptRegistry.INTEGRATE,
            ActionConceptRegistry.DEPLOY,
            ActionConceptRegistry.OPTIMIZE,
            ActionConceptRegistry.ANALYZE,
            ActionConceptRegistry.DEBUG,
            ActionConceptRegistry.TROUBLESHOOT,
            ActionConceptRegistry.MAINTAIN
    );

    public static ConservativeEvidencePacket build(String targetNodeId, ClaimLedger ledger) {
        ResumeDocumentNode target = targetNode(targetNodeId, ledger);
        String original = target == null || target.getText() == null ? "" : target.getText();
        List<String> required = extractRequiredFacts(original);
        List<String> supportingIds = new ArrayList<>();
        List<String> supportingFacts = new ArrayList<>();
        ClaimLedger.Entry entry = ledger == null ? null : ledger.entryOf(targetNodeId);
        if (entry != null) {
            int index = 0;
            for (ResumeDocumentNode node : entry.nodes()) {
                String nodeId = node.getNodeId();
                if (nodeId == null || nodeId.equals(targetNodeId) || isEntryHeading(node, index)) {
                    index++;
                    continue;
                }
                if (isSkillsOrSummaryDisguised(node.getText())
                        || isDocumentationOrCommunication(node.getText())) {
                    index++;
                    continue;
                }
                String fact = stripPii(node.getText());
                if (fact.isBlank() || looksLikeContact(fact) || looksLikeUrl(fact)) {
                    index++;
                    continue;
                }
                supportingIds.add(nodeId);
                supportingFacts.add("[" + nodeId + "] " + fact);
                index++;
            }
        }
        ConservativeEvidencePacket packet = new ConservativeEvidencePacket(
                targetNodeId,
                entryKey(targetNodeId, ledger),
                List.copyOf(required),
                List.copyOf(supportingFacts),
                List.copyOf(supportingIds),
                DEFAULT_FORBIDDEN_CLAIMS,
                0
        );
        String intent = supportingFacts.isEmpty()
                ? RewriteIntent.CLARITY_IMPROVEMENT
                : RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT;
        return packet.withMaxCharacters(
                ConservativeTailorPlanner.maxCharactersFor(original, intent, packet));
    }

    public ConservativeEvidencePacket withMaxCharacters(int updatedMax) {
        return new ConservativeEvidencePacket(
                targetNodeId,
                entryKey,
                requiredFacts,
                availableSupportingFacts,
                supportingNodeIds,
                forbiddenClaims,
                updatedMax
        );
    }

    public static ConservativeRewritePlan ensurePacket(ConservativeRewritePlan plan, ClaimLedger ledger) {
        if (plan == null) {
            return null;
        }
        if (plan.requiredFacts() != null && !plan.requiredFacts().isEmpty()) {
            return plan;
        }
        return plan.withPacket(build(plan.nodeId(), ledger));
    }

    public List<String> allowedFactNodeIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (targetNodeId != null && !targetNodeId.isBlank()) {
            ids.add(targetNodeId);
        }
        if (supportingNodeIds != null) {
            ids.addAll(supportingNodeIds);
        }
        return List.copyOf(ids);
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetNodeId", targetNodeId);
        map.put("entryKey", entryKey);
        map.put("requiredFacts", requiredFacts == null ? List.of() : requiredFacts);
        map.put("availableSupportingFacts", availableSupportingFacts == null ? List.of() : availableSupportingFacts);
        map.put("supportingNodeIds", supportingNodeIds == null ? List.of() : supportingNodeIds);
        map.put("forbiddenClaims", forbiddenClaims == null ? List.of() : forbiddenClaims);
        map.put("maxCharacters", maxCharacters);
        return map;
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonMap());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize evidence packet", ex);
        }
    }

    public String toPromptBlock() {
        try {
            return JSON.writeValueAsString(toJsonMap());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize evidence packet", ex);
        }
    }

    public static boolean coversRequiredFacts(String newText, List<String> requiredFacts) {
        if (requiredFacts == null || requiredFacts.isEmpty()) {
            return true;
        }
        String haystack = newText == null ? "" : newText.toLowerCase(Locale.ROOT);
        for (String fact : requiredFacts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            if (fact.startsWith("role:")) {
                String family = fact.substring("role:".length()).strip().toLowerCase(Locale.ROOT);
                if (!ActionConceptRegistry.contains(newText, family)) {
                    return false;
                }
                continue;
            }
            String needle = fact.contains(":") ? fact.substring(fact.indexOf(':') + 1) : fact;
            if (!containsFact(haystack, needle)) {
                return false;
            }
        }
        return true;
    }

    public static boolean supportingIdsStayInSameEntry(
            String targetNodeId,
            List<String> supportingNodeIds,
            ClaimLedger ledger) {

        if (supportingNodeIds == null || supportingNodeIds.isEmpty()) {
            return true;
        }
        for (String nodeId : supportingNodeIds) {
            if (!ledger.sameEntry(targetNodeId, nodeId)) {
                return false;
            }
        }
        return true;
    }

    static List<String> extractRequiredFacts(String originalText) {
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        String original = stripPii(originalText);
        if (original.isBlank()) {
            return List.of();
        }
        for (String family : ActionConceptRegistry.familyIds()) {
            if (CORE_ACTION_FAMILIES.contains(family) && ActionConceptRegistry.contains(original, family)) {
                facts.add("role:" + family);
            }
        }
        for (String tech : extractNormalizedTech(original)) {
            facts.add("tech:" + displayTech(tech));
        }
        for (String number : ClaimLedger.numbers(original)) {
            facts.add("number:" + number);
        }
        String lower = original.toLowerCase(Locale.ROOT);
        Set<String> techNeedles = new LinkedHashSet<>();
        for (String tech : extractNormalizedTech(original)) {
            techNeedles.add(tech.toLowerCase(Locale.ROOT));
        }
        List<String> taskHits = new ArrayList<>();
        for (String hint : TASK_HINTS) {
            if (lower.contains(hint) && !techNeedles.contains(hint)) {
                taskHits.add(hint);
            }
        }
        taskHits.sort(java.util.Comparator.comparingInt(lower::indexOf));
        for (String hint : taskHits) {
            facts.add("task:" + hint);
        }
        return new ArrayList<>(facts);
    }

    public static Set<String> extractNormalizedTech(String text) {
        LinkedHashSet<String> tech = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tech;
        }
        String remaining = text.toLowerCase(Locale.ROOT);
        for (String phrase : TECH_PHRASES_LONGEST_FIRST) {
            if (remaining.contains(phrase)) {
                tech.add(phrase);
                remaining = remaining.replace(phrase, " ");
            }
        }
        Set<String> coveredSingles = new LinkedHashSet<>();
        for (String phrase : tech) {
            coveredSingles.addAll(List.of(phrase.split("[\\s/]+")));
        }
        for (String term : ClaimLedger.techTerms(text)) {
            if ("boot".equals(term) || "restful".equals(term) || "api".equals(term) || "apis".equals(term)) {
                continue;
            }
            if (coveredSingles.contains(term)) {
                continue;
            }
            tech.add(term);
        }
        return tech;
    }

    static String stripPii(String text) {
        if (text == null) {
            return "";
        }
        String value = EMAIL.matcher(text).replaceAll("");
        value = PHONE.matcher(value).replaceAll("");
        return collapseSpace(value).strip();
    }

    private static ResumeDocumentNode targetNode(String targetNodeId, ClaimLedger ledger) {
        if (ledger == null) {
            return null;
        }
        for (ResumeDocumentNode node : ledger.nodesInEntry(targetNodeId)) {
            if (targetNodeId.equals(node.getNodeId())) {
                return node;
            }
        }
        return null;
    }

    private static String entryKey(String targetNodeId, ClaimLedger ledger) {
        ClaimLedger.Entry entry = ledger == null ? null : ledger.entryOf(targetNodeId);
        if (entry == null || entry.nodes() == null || entry.nodes().isEmpty()) {
            return "";
        }
        String heading = entry.nodes().get(0).getText();
        String type = "PROJECT".equals(entry.sectionRole())
                ? ResumeEntryCatalog.PROJECT
                : ResumeEntryCatalog.EXPERIENCE;
        String key = CanonicalEntryKeyResolver.key(heading, type);
        return key.isBlank() ? entry.entryId() : key;
    }

    static boolean isEntryHeading(ResumeDocumentNode node, int indexInEntry) {
        if (indexInEntry != 0 || node == null) {
            return false;
        }
        String text = node.getText() == null ? "" : node.getText().strip();
        if (text.isEmpty() || text.length() > 90) {
            return false;
        }
        String first = ConservativeTailorPlanner.firstWord(text);
        return first.isBlank() || !ActionConceptRegistry.isActionToken(first);
    }

    private static boolean isDocumentationOrCommunication(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("well-documented")
                || lower.contains("documentation")
                || lower.contains("java doc")
                || lower.contains("javadoc")
                || lower.contains("communication")
                || lower.contains("communicated")
                || lower.contains("teamwork")
                || lower.contains("team player");
    }

    private static boolean isSkillsOrSummaryDisguised(String text) {
        if (ConservativeTailorPlanner.isSkillList(text)) {
            return true;
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("technical skills") || lower.contains("summary of qualifications");
    }

    private static boolean looksLikeContact(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("[email]") || lower.contains("[phone]") || EMAIL.matcher(text).find();
    }

    private static boolean looksLikeUrl(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("http://") || lower.contains("https://") || lower.contains("www.");
    }

    private static boolean containsFact(String haystack, String fact) {
        String needle = fact == null ? "" : fact.strip().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) {
            return true;
        }
        if ("spring boot".equals(needle)) {
            return haystack.contains("spring boot") || (haystack.contains("spring") && haystack.contains("boot"));
        }
        if ("restful apis".equals(needle) || "rest apis".equals(needle) || "rest api".equals(needle)) {
            return haystack.contains("rest");
        }
        if ("components".equals(needle)) {
            return haystack.contains("component")
                    || (haystack.contains("cpu") && haystack.contains("gpu") && haystack.contains("ram"));
        }
        return haystack.contains(needle);
    }

    private static String displayTech(String term) {
        String value = term == null ? "" : term.strip();
        if (value.isEmpty()) {
            return value;
        }
        if ("spring boot".equalsIgnoreCase(value)) {
            return "Spring Boot";
        }
        if ("restful apis".equalsIgnoreCase(value) || "restful api".equalsIgnoreCase(value)) {
            return "RESTful APIs";
        }
        if ("rest apis".equalsIgnoreCase(value) || "rest api".equalsIgnoreCase(value)) {
            return "REST API";
        }
        if ("object-oriented programming".equalsIgnoreCase(value)) {
            return "OOP";
        }
        if ("mvc".equalsIgnoreCase(value)) {
            return "MVC";
        }
        if ("cpu".equalsIgnoreCase(value) || "gpu".equalsIgnoreCase(value) || "ram".equalsIgnoreCase(value)
                || "oop".equalsIgnoreCase(value) || "aws".equalsIgnoreCase(value) || "sql".equalsIgnoreCase(value)) {
            return value.toUpperCase(Locale.ROOT);
        }
        if ("java".equalsIgnoreCase(value)) {
            return "Java";
        }
        if ("maven".equalsIgnoreCase(value)) {
            return "Maven";
        }
        if ("spring".equalsIgnoreCase(value)) {
            return "Spring";
        }
        return value;
    }

    private static String collapseSpace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ");
    }
}
