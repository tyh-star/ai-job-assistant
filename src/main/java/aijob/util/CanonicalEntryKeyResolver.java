package aijob.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single producer of Evidence / V3 canonical entry keys.
 * ClaimLedger.entryId ({@code PROJECT-0}) is not an evidence key.
 * Matching is exact string equality only: no prefix and no title similarity.
 */
public final class CanonicalEntryKeyResolver {

    public static final String GENERAL = ResumeEntryCatalog.GENERAL;
    public static final String PROJECT = ResumeEntryCatalog.PROJECT;
    public static final String EXPERIENCE = ResumeEntryCatalog.EXPERIENCE;

    private static final Pattern DATE_LIKE = Pattern.compile(
            "(?i)\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b|\\b(?:19|20)\\d{2}\\b|present"
    );

    private CanonicalEntryKeyResolver() {
    }

    public static String slug(String heading, String entryType) {
        if (isBlank(heading)) {
            return "";
        }
        String[] parts = heading.split("\\|");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part == null ? "" : part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (DATE_LIKE.matcher(trimmed).find() && kept.size() >= (EXPERIENCE.equals(entryType) ? 2 : 1)) {
                continue;
            }
            if (DATE_LIKE.matcher(trimmed).find() && kept.isEmpty() && trimmed.length() < 12) {
                continue;
            }
            kept.add(trimmed);
            if (!EXPERIENCE.equals(entryType) && kept.size() >= 1) {
                break;
            }
            if (EXPERIENCE.equals(entryType) && kept.size() >= 2) {
                break;
            }
        }
        String source = kept.isEmpty() ? heading : String.join(" ", kept);
        return EvidenceText.normalize(source)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    public static String key(String heading, String entryType) {
        String type = PROJECT.equals(entryType) ? PROJECT : EXPERIENCE;
        String value = slug(heading, type);
        if (value.isBlank()) {
            return "";
        }
        return type + ":" + value;
    }

    public static String keyForEntry(ClaimLedger.Entry entry) {
        if (entry == null || entry.nodes() == null || entry.nodes().isEmpty()) {
            return "";
        }
        String type = PROJECT.equals(entry.sectionRole()) ? PROJECT : EXPERIENCE;
        return key(entry.nodes().get(0).getText(), type);
    }

    public static Index index(ClaimLedger ledger) {
        Map<String, List<ClaimLedger.Entry>> byBase = new LinkedHashMap<>();
        if (ledger != null) {
            for (ClaimLedger.Entry entry : ledger.entries()) {
                String base = keyForEntry(entry);
                if (base.isBlank()) {
                    continue;
                }
                byBase.computeIfAbsent(base, ignored -> new ArrayList<>()).add(entry);
            }
        }
        Map<String, String> keyByEntryId = new LinkedHashMap<>();
        Map<String, String> keyByNodeId = new LinkedHashMap<>();
        Map<String, ClaimLedger.Entry> entryByKey = new LinkedHashMap<>();
        for (Map.Entry<String, List<ClaimLedger.Entry>> group : byBase.entrySet()) {
            List<ClaimLedger.Entry> entries = group.getValue();
            boolean collide = entries.size() > 1;
            for (int i = 0; i < entries.size(); i++) {
                ClaimLedger.Entry entry = entries.get(i);
                String resolved = !collide || i == 0
                        ? group.getKey()
                        : group.getKey() + "#" + entry.entryId();
                keyByEntryId.put(entry.entryId(), resolved);
                entryByKey.put(resolved, entry);
                if (entry.sourceNodeIds() != null) {
                    for (String nodeId : entry.sourceNodeIds()) {
                        keyByNodeId.put(nodeId, resolved);
                    }
                }
            }
        }
        return new Index(Map.copyOf(keyByEntryId), Map.copyOf(keyByNodeId), Map.copyOf(entryByKey));
    }

    public record Index(
            Map<String, String> keyByEntryId,
            Map<String, String> keyByNodeId,
            Map<String, ClaimLedger.Entry> entryByKey
    ) {
        public String keyForEntryId(String entryId) {
            return entryId == null ? "" : keyByEntryId.getOrDefault(entryId, "");
        }

        public String keyForNodeId(String nodeId) {
            return nodeId == null ? "" : keyByNodeId.getOrDefault(nodeId, "");
        }

        public ClaimLedger.Entry entry(String canonicalKey) {
            return canonicalKey == null ? null : entryByKey.get(canonicalKey);
        }

        public boolean containsKey(String canonicalKey) {
            return canonicalKey != null && entryByKey.containsKey(canonicalKey);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
