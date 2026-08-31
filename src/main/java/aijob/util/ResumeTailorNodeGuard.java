package aijob.util;

import aijob.dto.ResumeDocumentNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which parsed DOCX nodes are safe to rewrite.
 * Headings are detected dynamically from node text; paragraph indexes are never hardcoded.
 */
public final class ResumeTailorNodeGuard {

    private enum Section {
        HEADER,
        SUMMARY,
        TECHNICAL,
        EXPERIENCE,
        PROJECTS,
        EDUCATION,
        OTHER
    }

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?){1,2}\\d{4}"
    );
    private static final Pattern YEAR_OR_PRESENT = Pattern.compile(
            "(?:19|20)\\d{2}|present",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MONTH = Pattern.compile(
            "\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                    + "jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|"
                    + "nov(?:ember)?|dec(?:ember)?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEXT_BULLET_PREFIX = Pattern.compile(
            "^[\\s]*[●•▪◦‣∙·\\-*]\\s*"
    );
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.|github\\.com|linkedin\\.com)",
            Pattern.CASE_INSENSITIVE
    );

    private ResumeTailorNodeGuard() {
    }

    public static Set<String> allowedNodeIds(List<ResumeDocumentNode> nodes) {

        Set<String> allowed = new LinkedHashSet<>();
        Section section = Section.HEADER;

        for (ResumeDocumentNode node : nodes) {
            String text = node.getText() == null ? "" : node.getText().strip();
            if (text.isEmpty()) {
                continue;
            }

            Section heading = matchHeading(text);
            if (heading != null) {
                section = heading;
                continue;
            }

            if (!isEditableSection(section)) {
                continue;
            }

            if (isProtectedMetadata(text)) {
                continue;
            }

            if (section == Section.TECHNICAL || isBulletLike(text)) {
                allowed.add(node.getNodeId());
            }
        }

        return allowed;
    }

    private static boolean isEditableSection(Section section) {
        return section == Section.SUMMARY
                || section == Section.TECHNICAL
                || section == Section.EXPERIENCE
                || section == Section.PROJECTS;
    }

    private static Section matchHeading(String text) {

        if (TEXT_BULLET_PREFIX.matcher(text).find()) {
            return null;
        }

        String normalized = normalizeHeading(text);
        if (normalized.isEmpty() || normalized.length() > 48) {
            return null;
        }

        if (normalized.equals("SUMMARY OF QUALIFICATIONS")
                || normalized.equals("SUMMARY")
                || normalized.equals("PROFESSIONAL SUMMARY")
                || normalized.equals("OBJECTIVE")) {
            return Section.SUMMARY;
        }

        if (normalized.equals("TECHNICAL PROFICIENCIES")
                || normalized.equals("TECHNICAL SKILLS")
                || normalized.equals("SKILLS")
                || normalized.equals("CORE SKILLS")) {
            return Section.TECHNICAL;
        }

        if (normalized.equals("INTERNSHIP EXPERIENCE")
                || normalized.equals("WORK EXPERIENCE")
                || normalized.equals("PROFESSIONAL EXPERIENCE")
                || normalized.equals("EXPERIENCE")
                || normalized.equals("EMPLOYMENT")) {
            return Section.EXPERIENCE;
        }

        if (normalized.equals("RELEVANT PROJECTS")
                || normalized.equals("PROJECTS")
                || normalized.equals("ACADEMIC PROJECTS")
                || normalized.equals("SELECTED PROJECTS")) {
            return Section.PROJECTS;
        }

        if (normalized.equals("EDUCATION")
                || normalized.equals("ACADEMIC BACKGROUND")) {
            return Section.EDUCATION;
        }

        if (normalized.equals("CERTIFICATIONS")
                || normalized.equals("CERTIFICATES")
                || normalized.equals("AWARDS")
                || normalized.equals("LEADERSHIP")
                || normalized.equals("ACTIVITIES")) {
            return Section.OTHER;
        }

        return null;
    }

    private static boolean isProtectedMetadata(String text) {

        if (EMAIL.matcher(text).find()) {
            return true;
        }

        if (PHONE.matcher(text).find() && text.length() < 80) {
            return true;
        }

        if (isUrlLine(text)) {
            return true;
        }

        return isTitleOrDateLine(text);
    }

    private static boolean isUrlLine(String text) {
        String stripped = TEXT_BULLET_PREFIX.matcher(text.strip()).replaceFirst("").strip();
        if (!URL.matcher(stripped).find()) {
            return false;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.startsWith("project link")) {
            return true;
        }
        String withoutUrl = stripped.replaceAll(
                "(?i)(https?://\\S+|www\\.\\S+|github\\.com/\\S+|linkedin\\.com/\\S+)",
                ""
        ).strip();
        return withoutUrl.isEmpty() || withoutUrl.split("\\s+").length <= 2;
    }

    private static boolean isTitleOrDateLine(String text) {

        if (TEXT_BULLET_PREFIX.matcher(text).find() || startsWithActionVerb(text)) {
            return false;
        }

        boolean hasDate = YEAR_OR_PRESENT.matcher(text).find()
                || MONTH.matcher(text).find();
        if (!hasDate) {
            return false;
        }

        boolean hasSeparator = text.contains("|")
                || text.contains("·")
                || text.contains("•")
                || text.contains(" – ")
                || text.contains(" — ");

        return hasSeparator || text.length() < 120;
    }

    private static boolean isBulletLike(String text) {
        if (TEXT_BULLET_PREFIX.matcher(text).find()) {
            return true;
        }

        return startsWithActionVerb(text) || text.length() >= 50;
    }

    private static boolean startsWithActionVerb(String text) {

        String first = text.strip().split("\\s+", 2)[0]
                .replaceAll("[^A-Za-z]", "")
                .toLowerCase(Locale.ROOT);

        return Set.of(
                "developed", "implemented", "built", "designed", "created",
                "led", "improved", "collaborated", "optimized", "engineered",
                "maintained", "automated", "integrated", "deployed", "tested",
                "wrote", "refactored", "migrated", "supported", "analyzed",
                "delivered", "configured", "enhanced", "reduced", "increased"
        ).contains(first);
    }

    private static String normalizeHeading(String text) {
        return text.replaceAll("[^A-Za-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .toUpperCase(Locale.ROOT);
    }
}
