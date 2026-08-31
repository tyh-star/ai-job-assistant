package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.ResumeTailorNodeGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Locates an existing Skills / Technical Skills section. Does not create nodes.
 */
public final class V3SkillsSection {

    private V3SkillsSection() {
    }

    public record Snapshot(boolean present, String heading, List<V3WritableNode> writable) {
        public Snapshot {
            heading = heading == null ? "" : heading;
            writable = writable == null ? List.of() : List.copyOf(writable);
        }

        public boolean writablePresent() {
            return present && !writable.isEmpty();
        }
    }

    public static Snapshot find(List<ResumeDocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return new Snapshot(false, "", List.of());
        }
        Set<String> allowed = ResumeTailorNodeGuard.allowedNodeIds(nodes);
        boolean inSkills = false;
        boolean present = false;
        String heading = "";
        List<V3WritableNode> writable = new ArrayList<>();
        for (ResumeDocumentNode node : nodes) {
            String text = node.getText() == null ? "" : node.getText().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (isSkillsHeading(text)) {
                inSkills = true;
                present = true;
                heading = text;
                continue;
            }
            if (inSkills && isSectionHeading(text)) {
                inSkills = false;
                continue;
            }
            if (inSkills && node.getNodeId() != null && allowed.contains(node.getNodeId())) {
                writable.add(new V3WritableNode(node.getNodeId(), text));
            }
        }
        return new Snapshot(present, heading, writable);
    }

    static boolean isSkillsHeading(String text) {
        String normalized = normalizeHeading(text);
        return "TECHNICAL PROFICIENCIES".equals(normalized)
                || "TECHNICAL SKILLS".equals(normalized)
                || "SKILLS".equals(normalized)
                || "CORE SKILLS".equals(normalized);
    }

    static boolean isSectionHeading(String text) {
        String normalized = normalizeHeading(text);
        if (normalized.isEmpty() || normalized.length() > 48) {
            return false;
        }
        return "SUMMARY OF QUALIFICATIONS".equals(normalized)
                || "SUMMARY".equals(normalized)
                || "PROFESSIONAL SUMMARY".equals(normalized)
                || "OBJECTIVE".equals(normalized)
                || isSkillsHeading(text)
                || "INTERNSHIP EXPERIENCE".equals(normalized)
                || "WORK EXPERIENCE".equals(normalized)
                || "PROFESSIONAL EXPERIENCE".equals(normalized)
                || "EXPERIENCE".equals(normalized)
                || "EMPLOYMENT".equals(normalized)
                || "RELEVANT PROJECTS".equals(normalized)
                || "PROJECTS".equals(normalized)
                || "ACADEMIC PROJECTS".equals(normalized)
                || "SELECTED PROJECTS".equals(normalized)
                || "EDUCATION".equals(normalized)
                || "ACADEMIC BACKGROUND".equals(normalized)
                || "CERTIFICATIONS".equals(normalized)
                || "CERTIFICATES".equals(normalized)
                || "AWARDS".equals(normalized)
                || "LEADERSHIP".equals(normalized)
                || "ACTIVITIES".equals(normalized);
    }

    private static String normalizeHeading(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^A-Za-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .toUpperCase(Locale.ROOT);
    }
}
