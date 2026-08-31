package aijob.util;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable project/experience entry keys for Evidence Bank. Keys are derived from
 * headings, not from a single node id such as p-16.
 */
public final class ResumeEntryCatalog {

    public static final String GENERAL = "GENERAL";
    public static final String PROJECT = "PROJECT";
    public static final String EXPERIENCE = "EXPERIENCE";

    private ResumeEntryCatalog() {
    }

    public record EntryOption(
            String entryKey,
            String entryLabel,
            String entryType,
            List<String> nodeIds
    ) {
        public aijob.dto.EntryOption toDto() {
            aijob.dto.EntryOption option = new aijob.dto.EntryOption();
            option.setEntryKey(entryKey);
            option.setEntryLabel(entryLabel);
            option.setEntryType(entryType);
            option.setNodeIds(nodeIds == null ? List.of() : List.copyOf(nodeIds));
            return option;
        }
    }

    public static List<aijob.dto.EntryOption> options(
            List<ResumeDocumentNode> nodes,
            ResumeStructure structure) {

        List<EntryOption> entries = fromDocx(nodes);
        if (entries.isEmpty() && structure != null && hasStructuredEntries(structure)) {
            entries.addAll(fromStructure(structure));
        }
        Map<String, EntryOption> unique = new LinkedHashMap<>();
        for (EntryOption entry : entries) {
            unique.putIfAbsent(entry.entryKey(), entry);
        }
        List<aijob.dto.EntryOption> result = new ArrayList<>();
        for (EntryOption entry : unique.values()) {
            result.add(entry.toDto());
        }
        result.add(generalOption());
        return result;
    }

    static List<EntryOption> fromDocx(List<ResumeDocumentNode> nodes) {
        List<EntryOption> options = new ArrayList<>();
        ClaimLedger ledger = ClaimLedger.from(nodes);
        for (ClaimLedger.Entry entry : ledger.entries()) {
            if (entry.nodes() == null || entry.nodes().isEmpty()) {
                continue;
            }
            String heading = entry.nodes().get(0).getText();
            String type = PROJECT.equals(entry.sectionRole()) ? PROJECT : EXPERIENCE;
            String label = headingLabel(heading);
            String key = CanonicalEntryKeyResolver.key(heading, type);
            if (key.isBlank()) {
                continue;
            }
            options.add(new EntryOption(key, label, type, entry.sourceNodeIds()));
        }
        return options;
    }

    static List<EntryOption> fromStructure(ResumeStructure structure) {
        List<EntryOption> options = new ArrayList<>();
        if (structure.getProjects() != null) {
            for (ResumeStructure.ResumeProject project : structure.getProjects()) {
                if (project == null || isBlank(project.getName())) {
                    continue;
                }
                String key = CanonicalEntryKeyResolver.key(project.getName(), PROJECT);
                String label = project.getName().strip();
                List<String> nodeIds = project.getSourceBlockIds() == null
                        ? List.of()
                        : List.copyOf(project.getSourceBlockIds());
                options.add(new EntryOption(key, label, PROJECT, nodeIds));
            }
        }
        if (structure.getExperience() != null) {
            for (ResumeStructure.ResumeExperience experience : structure.getExperience()) {
                if (experience == null) {
                    continue;
                }
                String heading = joinHeading(experience.getTitle(), experience.getCompany());
                if (isBlank(heading)) {
                    continue;
                }
                String key = CanonicalEntryKeyResolver.key(heading, EXPERIENCE);
                String label = heading.strip();
                List<String> nodeIds = experience.getSourceBlockIds() == null
                        ? List.of()
                        : List.copyOf(experience.getSourceBlockIds());
                options.add(new EntryOption(key, label, EXPERIENCE, nodeIds));
            }
        }
        return options;
    }

    public static aijob.dto.EntryOption generalOption() {
        aijob.dto.EntryOption option = new aijob.dto.EntryOption();
        option.setEntryKey(GENERAL);
        option.setEntryLabel("Unlinked entry");
        option.setEntryType(GENERAL);
        option.setNodeIds(List.of());
        return option;
    }

    public static String slug(String heading, String entryType) {
        return CanonicalEntryKeyResolver.slug(heading, entryType);
    }

    static String headingLabel(String heading) {
        if (isBlank(heading)) {
            return "";
        }
        String[] parts = heading.split("\\|");
        return parts[0] == null ? heading.strip() : parts[0].strip();
    }

    private static boolean hasStructuredEntries(ResumeStructure structure) {
        return (structure.getProjects() != null && !structure.getProjects().isEmpty())
                || (structure.getExperience() != null && !structure.getExperience().isEmpty());
    }

    private static String joinHeading(String title, String company) {
        String left = title == null ? "" : title.strip();
        String right = company == null ? "" : company.strip();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + " " + right;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
