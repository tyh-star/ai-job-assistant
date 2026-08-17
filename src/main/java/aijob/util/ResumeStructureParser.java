package aijob.util;

import aijob.dto.ResumeStructure;
import aijob.dto.ResumeStructure.ResumeContact;
import aijob.dto.ResumeStructure.ResumeEducation;
import aijob.dto.ResumeStructure.ResumeExperience;
import aijob.dto.ResumeStructure.ResumeProject;
import aijob.dto.ResumeStructure.ResumeSkill;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResumeStructureParser {

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)\\d{3}[-.\\s]?\\d{4}"
    );
    private static final Pattern URL = Pattern.compile(
            "(https?://\\S+|www\\.\\S+|linkedin\\.com/\\S+|github\\.com/\\S+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEAR_RANGE = Pattern.compile(
            "((?:19|20)\\d{2})\\s*(?:[-–—]|to)\\s*((?:19|20)\\d{2}|present|current|now)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BULLET = Pattern.compile(
            "^[\\u2022\\u25CF\\u25E6\\u2043\\-*·•●]\\s+(.+)$"
    );

    private static final Set<String> SUMMARY_HEADINGS = Set.of(
            "summary", "professional summary", "profile", "objective",
            "about", "个人简介", "简介", "概述"
    );
    private static final Set<String> SKILLS_HEADINGS = Set.of(
            "skills", "technical skills", "core skills", "technologies",
            "skill", "技能", "专业技能"
    );
    private static final Set<String> EXPERIENCE_HEADINGS = Set.of(
            "experience", "work experience", "professional experience",
            "employment", "work history", "工作经历", "工作经验", "经历"
    );
    private static final Set<String> EDUCATION_HEADINGS = Set.of(
            "education", "academic", "academic background",
            "教育", "教育背景", "学历"
    );
    private static final Set<String> PROJECTS_HEADINGS = Set.of(
            "projects", "personal projects", "selected projects",
            "项目", "项目经历"
    );

    private enum Section {
        CONTACT, SUMMARY, SKILLS, EXPERIENCE, EDUCATION, PROJECTS
    }

    public ResumeStructure parse(String extractedText) {
        ResumeStructure structure = ResumeStructure.empty();
        if (extractedText == null || extractedText.isBlank()) {
            structure.getFieldConfidence().put("contact.fullName", CONFIDENCE_LOW);
            return structure;
        }

        List<String> lines = extractedText.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        SectionSplit split = splitSections(lines);
        parseContact(structure, split.sections.get(Section.CONTACT));
        parseSummary(structure, split.sections.get(Section.SUMMARY));
        parseSkills(structure, split.sections.get(Section.SKILLS));
        parseExperience(structure, split.sections.get(Section.EXPERIENCE));
        parseEducation(structure, split.sections.get(Section.EDUCATION));
        parseProjects(structure, split.sections.get(Section.PROJECTS));
        applyConfidence(structure, split);
        return structure;
    }

    private SectionSplit splitSections(List<String> lines) {
        Map<Section, List<String>> sections = new LinkedHashMap<>();
        for (Section section : Section.values()) {
            sections.put(section, new ArrayList<>());
        }
        EnumSet<Section> seenHeadings = EnumSet.noneOf(Section.class);

        Section current = Section.CONTACT;
        for (String line : lines) {
            Section heading = headingSection(line);
            if (heading != null) {
                current = heading;
                seenHeadings.add(heading);
                continue;
            }
            sections.get(current).add(line);
        }
        return new SectionSplit(sections, seenHeadings);
    }

    private Section headingSection(String line) {
        String normalized = normalizeHeading(line);
        if (normalized.isEmpty() || normalized.length() > 40) {
            return null;
        }
        if (SUMMARY_HEADINGS.contains(normalized)) {
            return Section.SUMMARY;
        }
        if (SKILLS_HEADINGS.contains(normalized)) {
            return Section.SKILLS;
        }
        if (EXPERIENCE_HEADINGS.contains(normalized)) {
            return Section.EXPERIENCE;
        }
        if (EDUCATION_HEADINGS.contains(normalized)) {
            return Section.EDUCATION;
        }
        if (PROJECTS_HEADINGS.contains(normalized)) {
            return Section.PROJECTS;
        }
        return null;
    }

    private String normalizeHeading(String line) {
        String trimmed = line.trim();
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void parseContact(ResumeStructure structure, List<String> lines) {
        ResumeContact contact = structure.getContact();
        List<String> leftover = new ArrayList<>();

        for (String line : lines) {
            Matcher emailMatcher = EMAIL.matcher(line);
            if (emailMatcher.find() && contact.getEmail().isEmpty()) {
                contact.setEmail(emailMatcher.group());
            }

            Matcher phoneMatcher = PHONE.matcher(line);
            if (phoneMatcher.find() && contact.getPhone().isEmpty()) {
                contact.setPhone(phoneMatcher.group().trim());
            }

            Matcher urlMatcher = URL.matcher(line);
            while (urlMatcher.find()) {
                String url = urlMatcher.group().replaceAll("[),.;]+$", "");
                if (!contact.getLinks().contains(url)) {
                    contact.getLinks().add(url);
                }
            }

            String remainder = stripContactTokens(line);
            if (!remainder.isBlank()) {
                leftover.add(remainder);
            }
        }

        if (!leftover.isEmpty() && looksLikeName(leftover.get(0))) {
            contact.setFullName(leftover.get(0));
            leftover.remove(0);
        } else if (!leftover.isEmpty() && contact.getFullName().isEmpty()) {
            contact.setFullName(leftover.get(0));
            leftover.remove(0);
        }

        if (!leftover.isEmpty() && contact.getLocation().isEmpty()) {
            contact.setLocation(leftover.get(0));
        }
    }

    private String stripContactTokens(String line) {
        String remainder = EMAIL.matcher(line).replaceAll(" ");
        remainder = PHONE.matcher(remainder).replaceAll(" ");
        remainder = URL.matcher(remainder).replaceAll(" ");
        remainder = remainder.replace("|", " ");
        return remainder.replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeName(String line) {
        if (line.contains("@") || line.matches(".*\\d.*")) {
            return false;
        }
        String[] words = line.split("\\s+");
        return words.length >= 2 && words.length <= 5 && line.length() <= 60;
    }

    private void parseSummary(ResumeStructure structure, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        structure.setSummary(String.join(" ", lines).trim());
    }

    private void parseSkills(ResumeStructure structure, List<String> lines) {
        for (String line : lines) {
            String content = stripBullet(line);
            String[] parts = content.split("[,;|/]|\\u2022|\\u25CF");
            for (String part : parts) {
                String skill = part.trim();
                if (!skill.isEmpty() && structure.getSkills().stream()
                        .noneMatch(existing -> skill.equals(existing.getName()))) {
                    ResumeSkill item = new ResumeSkill();
                    item.setName(skill);
                    structure.getSkills().add(item);
                }
            }
        }
    }

    private void parseExperience(ResumeStructure structure, List<String> lines) {
        ResumeExperience current = null;
        for (String line : lines) {
            String bullet = bulletText(line);
            if (bullet != null) {
                if (current == null) {
                    current = newExperience();
                    structure.getExperience().add(current);
                }
                current.getBullets().add(bullet);
                continue;
            }

            ResumeExperience parsed = parseExperienceHeader(line);
            current = parsed;
            structure.getExperience().add(current);
        }
    }

    private ResumeExperience parseExperienceHeader(String line) {
        ResumeExperience experience = newExperience();
        Matcher range = YEAR_RANGE.matcher(line);
        String datesRemoved = line;
        if (range.find()) {
            experience.setStartDate(range.group(1));
            experience.setEndDate(range.group(2));
            datesRemoved = (line.substring(0, range.start()) + " " + line.substring(range.end())).trim();
        }

        String[] parts = splitHeader(datesRemoved);
        if (parts.length >= 2) {
            experience.setCompany(parts[0]);
            experience.setTitle(parts[1]);
            if (parts.length >= 3 && experience.getStartDate().isEmpty()) {
                experience.setLocation(parts[2]);
            }
        } else if (parts.length == 1) {
            experience.setTitle(parts[0]);
        }
        return experience;
    }

    private void parseEducation(ResumeStructure structure, List<String> lines) {
        ResumeEducation current = null;
        for (String line : lines) {
            String bullet = bulletText(line);
            if (bullet != null) {
                if (current == null) {
                    current = newEducation();
                    structure.getEducation().add(current);
                }
                if (current.getDetails().isEmpty()) {
                    current.setDetails(bullet);
                } else {
                    current.setDetails(current.getDetails() + " " + bullet);
                }
                continue;
            }

            current = newEducation();
            Matcher range = YEAR_RANGE.matcher(line);
            String datesRemoved = line;
            if (range.find()) {
                current.setDates(range.group(0).trim());
                datesRemoved = (line.substring(0, range.start()) + " " + line.substring(range.end())).trim();
            }
            String[] parts = splitHeader(datesRemoved);
            if (parts.length >= 2) {
                current.setSchool(parts[0]);
                current.setDegree(parts[1]);
            } else if (parts.length == 1) {
                current.setSchool(parts[0]);
            }
            structure.getEducation().add(current);
        }
    }

    private void parseProjects(ResumeStructure structure, List<String> lines) {
        ResumeProject current = null;
        for (String line : lines) {
            String bullet = bulletText(line);
            if (bullet != null) {
                if (current == null) {
                    current = newProject();
                    structure.getProjects().add(current);
                }
                current.getBullets().add(bullet);
                continue;
            }
            current = newProject();
            current.setName(line);
            structure.getProjects().add(current);
        }
    }

    private void applyConfidence(ResumeStructure structure, SectionSplit split) {
        Map<String, String> confidence = structure.getFieldConfidence();
        ResumeContact contact = structure.getContact();

        confidence.put(
                "contact.fullName",
                contact.getFullName().isBlank() ? CONFIDENCE_LOW
                        : looksLikeName(contact.getFullName()) ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW
        );
        confidence.put(
                "contact.email",
                EMAIL.matcher(contact.getEmail()).matches() ? CONFIDENCE_HIGH : CONFIDENCE_LOW
        );
        confidence.put(
                "contact.phone",
                contact.getPhone().isBlank() ? CONFIDENCE_LOW : CONFIDENCE_HIGH
        );
        confidence.put(
                "contact.location",
                contact.getLocation().isBlank() ? CONFIDENCE_LOW : CONFIDENCE_LOW
        );
        confidence.put(
                "summary",
                structure.getSummary().isBlank() ? CONFIDENCE_LOW
                        : split.seenHeadings.contains(Section.SUMMARY) ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW
        );
        confidence.put(
                "skills",
                structure.getSkills().isEmpty() ? CONFIDENCE_LOW
                        : split.seenHeadings.contains(Section.SKILLS) ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW
        );
        confidence.put(
                "experience",
                structure.getExperience().isEmpty() ? CONFIDENCE_LOW
                        : split.seenHeadings.contains(Section.EXPERIENCE) ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW
        );
        confidence.put(
                "education",
                structure.getEducation().isEmpty() ? CONFIDENCE_LOW : CONFIDENCE_MEDIUM
        );
        confidence.put(
                "projects",
                structure.getProjects().isEmpty() ? CONFIDENCE_LOW : CONFIDENCE_MEDIUM
        );
    }

    private record SectionSplit(
            Map<Section, List<String>> sections,
            EnumSet<Section> seenHeadings) {
    }

    private String[] splitHeader(String line) {
        String normalized = line.replace(" - ", " | ").replace(" – ", " | ").replace(" — ", " | ");
        String[] parts = normalized.split("\\s*\\|\\s*");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                cleaned.add(value);
            }
        }
        return cleaned.toArray(String[]::new);
    }

    private String bulletText(String line) {
        Matcher matcher = BULLET.matcher(line);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String stripBullet(String line) {
        String bullet = bulletText(line);
        return bullet != null ? bullet : line;
    }

    private ResumeExperience newExperience() {
        ResumeExperience experience = new ResumeExperience();
        experience.normalize();
        return experience;
    }

    private ResumeEducation newEducation() {
        ResumeEducation education = new ResumeEducation();
        education.normalize();
        return education;
    }

    private ResumeProject newProject() {
        ResumeProject project = new ResumeProject();
        project.normalize();
        return project;
    }
}
