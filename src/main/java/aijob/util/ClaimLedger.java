package aijob.util;

import aijob.dto.ResumeDocumentNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Groups PROJECT / EXPERIENCE nodes into entries from DOCX order.
 * Facts are copied from original node text; nothing is generated.
 */
public final class ClaimLedger {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");
    private static final Pattern PRESENT = Pattern.compile("\\b(?:present|current)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern MONTH = Pattern.compile(
            "\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                    + "jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|"
                    + "nov(?:ember)?|dec(?:ember)?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> TECH = Set.of(
            "java", "spring", "boot", "maven", "python", "javascript", "vue",
            "html", "css", "mysql", "sql", "linux", "unix", "restful", "rest",
            "mvc", "cpu", "gpu", "ram", "github", "aws", "docker", "kubernetes",
            "redis", "api", "oop", "cicd"
    );
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private final List<Entry> entries;
    private final Map<String, Entry> byNodeId;

    private ClaimLedger(List<Entry> entries, Map<String, Entry> byNodeId) {
        this.entries = entries;
        this.byNodeId = byNodeId;
    }

    public static ClaimLedger from(List<ResumeDocumentNode> nodes) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byNodeId = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty()) {
            return new ClaimLedger(entries, byNodeId);
        }

        List<ConservativeTailorPlanner.ClassifiedNode> classified =
                ConservativeTailorPlanner.classify(nodes);
        String sectionRole = null;
        EntryBuilder current = null;
        int sequence = 0;

        for (ConservativeTailorPlanner.ClassifiedNode classifiedNode : classified) {
            ResumeDocumentNode node = classifiedNode.node();
            ResumeNodeRole role = classifiedNode.role();
            if (role == ResumeNodeRole.HEADING) {
                String nextSection = sectionRoleOf(node.getText());
                if (nextSection != null) {
                    current = flush(current, entries, byNodeId);
                    sectionRole = nextSection;
                }
                continue;
            }
            if (!"PROJECT".equals(sectionRole) && !"EXPERIENCE".equals(sectionRole)) {
                continue;
            }
            if (role == ResumeNodeRole.SKILLS || role == ResumeNodeRole.CONTACT) {
                continue;
            }
            if (role == ResumeNodeRole.LINK) {
                if (current != null) {
                    current.add(node);
                }
                continue;
            }
            if (isEntryHeader(role, node.getText())) {
                current = flush(current, entries, byNodeId);
                current = new EntryBuilder(sectionRole + "-" + sequence++, sectionRole);
                current.add(node);
                continue;
            }
            if (isEntryBullet(role, node.getText())) {
                if (current == null) {
                    current = new EntryBuilder(sectionRole + "-" + sequence++, sectionRole);
                }
                current.add(node);
            }
        }
        flush(current, entries, byNodeId);
        return new ClaimLedger(List.copyOf(entries), Map.copyOf(byNodeId));
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entryOf(String nodeId) {
        return nodeId == null ? null : byNodeId.get(nodeId);
    }

    public boolean sameEntry(String leftNodeId, String rightNodeId) {
        Entry left = entryOf(leftNodeId);
        Entry right = entryOf(rightNodeId);
        return left != null && left == right;
    }

    public List<ResumeDocumentNode> nodesInEntry(String nodeId) {
        Entry entry = entryOf(nodeId);
        if (entry == null) {
            return List.of();
        }
        return entry.nodes();
    }

    public String entryText(String nodeId) {
        StringBuilder text = new StringBuilder();
        for (ResumeDocumentNode node : nodesInEntry(nodeId)) {
            text.append(' ').append(node.getText());
        }
        return text.toString();
    }

    public String allowedFactsBlock(String nodeId) {
        Entry entry = entryOf(nodeId);
        if (entry == null) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        for (Fact fact : entry.facts()) {
            block.append('[')
                    .append(fact.sourceNodeId())
                    .append("] ")
                    .append(fact.factText())
                    .append('\n');
        }
        return block.toString();
    }

    public List<String> sourceNodeIds(String nodeId) {
        Entry entry = entryOf(nodeId);
        return entry == null ? List.of() : entry.sourceNodeIds();
    }

    public boolean coversTech(String newText, String targetNodeId) {
        Set<String> allowed = techTerms(entryText(targetNodeId));
        for (String term : techTerms(newText)) {
            if (!allowed.contains(term)) {
                return false;
            }
        }
        return true;
    }

    public boolean coversNumbers(String newText, String targetNodeId) {
        Set<String> allowed = numbers(entryText(targetNodeId));
        for (String number : numbers(newText)) {
            if (!allowed.contains(number)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the ClaimLedger entry dates include Present/Current (ongoing role or project).
     */
    public boolean isOngoing(String nodeId) {
        return PRESENT.matcher(entryText(nodeId)).find();
    }

    /**
     * True when the entry has dated bounds and does not include Present.
     */
    public boolean isEnded(String nodeId) {
        if (isOngoing(nodeId)) {
            return false;
        }
        String text = entryText(nodeId);
        return YEAR.matcher(text).find() || MONTH.matcher(text).find();
    }

    public static Set<String> techTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        if (text.toUpperCase(Locale.ROOT).contains("C/C++") || text.contains("C++")) {
            terms.add("c/c++");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("ci/cd") || lower.contains("cicd")) {
            terms.add("cicd");
        }
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            if (TECH.contains(term) || term.contains("+") || term.contains("#")) {
                terms.add(term);
            }
        }
        return terms;
    }

    static Set<String> numbers(String text) {
        Set<String> values = new LinkedHashSet<>();
        if (text == null) {
            return values;
        }
        var matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group().replace(",", ""));
        }
        return values;
    }

    private static String sectionRoleOf(String headingText) {
        String normalized = headingText == null ? "" : headingText
                .replaceAll("[^A-Za-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INTERNSHIP EXPERIENCE", "WORK EXPERIENCE", "PROFESSIONAL EXPERIENCE",
                    "EXPERIENCE", "EMPLOYMENT" -> "EXPERIENCE";
            case "RELEVANT PROJECTS", "PROJECTS", "ACADEMIC PROJECTS", "SELECTED PROJECTS" ->
                    "PROJECT";
            case "EDUCATION", "ACADEMIC BACKGROUND" -> "EDUCATION";
            case "SUMMARY OF QUALIFICATIONS", "SUMMARY", "PROFESSIONAL SUMMARY", "OBJECTIVE" ->
                    "SUMMARY";
            case "TECHNICAL PROFICIENCIES", "TECHNICAL SKILLS", "SKILLS", "CORE SKILLS" ->
                    "SKILLS";
            default -> null;
        };
    }

    private static boolean isEntryHeader(ResumeNodeRole role, String text) {
        if (role == ResumeNodeRole.DATE) {
            return true;
        }
        if (role == ResumeNodeRole.SKILLS
                || role == ResumeNodeRole.CONTACT
                || role == ResumeNodeRole.LINK
                || role == ResumeNodeRole.HEADING) {
            return false;
        }
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.length() > 90 || startsLikeBullet(value)) {
            return false;
        }
        if (value.contains(",") && !value.contains("|")) {
            return false;
        }
        if ((role == ResumeNodeRole.EXPERIENCE_BULLET || role == ResumeNodeRole.PROJECT_BULLET)
                && !ActionConceptRegistry.actionsIn(value).isEmpty()) {
            return false;
        }
        return true;
    }

    private static boolean isEntryBullet(ResumeNodeRole role, String text) {
        if (role == ResumeNodeRole.EXPERIENCE_BULLET || role == ResumeNodeRole.PROJECT_BULLET) {
            return true;
        }
        return role == ResumeNodeRole.OTHER && startsLikeBullet(text);
    }

    private static boolean startsLikeBullet(String text) {
        return text.startsWith("●")
                || text.startsWith("•")
                || text.startsWith("-")
                || ActionConceptRegistry.isActionToken(ConservativeTailorPlanner.firstWord(text));
    }

    private static EntryBuilder flush(
            EntryBuilder current,
            List<Entry> entries,
            Map<String, Entry> byNodeId) {

        if (current == null || current.nodes.isEmpty()) {
            return null;
        }
        Entry entry = current.build();
        entries.add(entry);
        for (String nodeId : entry.sourceNodeIds()) {
            byNodeId.put(nodeId, entry);
        }
        return null;
    }

    public record Fact(String sourceNodeId, String factText) {
    }

    public record Entry(
            String entryId,
            String sectionRole,
            List<String> sourceNodeIds,
            List<Fact> facts,
            List<ResumeDocumentNode> nodes
    ) {
    }

    private static final class EntryBuilder {
        private final String entryId;
        private final String sectionRole;
        private final List<ResumeDocumentNode> nodes = new ArrayList<>();

        private EntryBuilder(String entryId, String sectionRole) {
            this.entryId = entryId;
            this.sectionRole = sectionRole;
        }

        private void add(ResumeDocumentNode node) {
            nodes.add(node);
        }

        private Entry build() {
            List<String> ids = new ArrayList<>();
            List<Fact> facts = new ArrayList<>();
            for (ResumeDocumentNode node : nodes) {
                ids.add(node.getNodeId());
                facts.add(new Fact(node.getNodeId(), node.getText() == null ? "" : node.getText()));
            }
            return new Entry(entryId, sectionRole, List.copyOf(ids), List.copyOf(facts), List.copyOf(nodes));
        }
    }
}
