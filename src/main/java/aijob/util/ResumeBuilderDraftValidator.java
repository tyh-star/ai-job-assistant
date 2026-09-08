package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
import aijob.dto.builder.ResumeBuilderDraftContent.CareerMaterials;
import aijob.dto.builder.ResumeBuilderDraftContent.Certification;
import aijob.dto.builder.ResumeBuilderDraftContent.CustomSection;
import aijob.dto.builder.ResumeBuilderDraftContent.EducationEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.JobTarget;
import aijob.dto.builder.ResumeBuilderDraftContent.LanguageEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.LeadershipEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.ProjectEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.Publication;
import aijob.dto.builder.ResumeBuilderDraftContent.SkillCategory;
import aijob.dto.builder.ResumeBuilderDraftContent.VolunteerEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.WorkExperienceEntry;
import aijob.dto.builder.ResumeBuilderSectionIds;
import aijob.enums.ResumeBuilderTemplateType;
import aijob.exception.InvalidResumeBuilderDraftException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Backend validation of a resume-builder draft's typed content. The frontend validates
 * too (for immediate feedback), but that NEVER substitutes for this -- every write
 * still goes through here, since the frontend is not a trust boundary. Called after
 * {@link ResumeBuilderDraftContent#normalize()} (so "filled with defaults" state is
 * never itself flagged as invalid) but before anything is persisted or rendered.
 * <p>
 * On any violation this throws {@link InvalidResumeBuilderDraftException} carrying
 * every violation found, joined by "; " -- not just the first -- so a client fixing one
 * mistake at a time is not stuck re-submitting to discover the next one.
 */
@Component
public class ResumeBuilderDraftValidator {

    private static final Set<String> VALID_LOCALES = Set.of("en", "zh-CN");

    private static final Pattern ILLEGAL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");
    private static final Pattern DATE_YM_OR_YMD = Pattern.compile("^\\d{4}-\\d{2}(-\\d{2})?$");
    private static final Set<String> ONGOING_MARKERS =
            Set.of("present", "current", "ongoing", "至今", "现在", "进行中");

    private static final int SHORT = 200;
    private static final int MEDIUM = 1000;
    private static final int LONG = 4000;
    private static final int URL_MAX = 500;

    private static final int MAX_EDUCATION = 30;
    private static final int MAX_SKILL_CATEGORIES = 30;
    private static final int MAX_SKILLS_PER_CATEGORY = 50;
    private static final int MAX_EXPERIENCE = 50;
    private static final int MAX_PROJECTS = 100;
    private static final int MAX_VOLUNTEER = 50;
    private static final int MAX_CERTIFICATIONS = 50;
    private static final int MAX_LANGUAGES = 30;
    private static final int MAX_LEADERSHIP = 50;
    private static final int MAX_PUBLICATIONS = 50;
    private static final int MAX_HOBBIES = 50;
    private static final int MAX_CUSTOM_SECTIONS = 20;
    private static final int MAX_ITEMS_PER_CUSTOM_SECTION = 100;
    private static final int MAX_LINES_PER_ENTRY = 30;
    private static final int MAX_COURSES = 100;

    private static final long MAX_TEAM_SIZE = 100_000L;
    private static final long MAX_USER_COUNT = 2_000_000_000L;
    private static final long MAX_SERVICE_HOURS = 100_000L;

    public void validate(ResumeBuilderDraftContent content) {
        List<String> errors = new ArrayList<>();
        if (content == null) {
            throw new InvalidResumeBuilderDraftException("content is required");
        }

        validateBasicInfo(content.getBasicInfo(), errors);
        validateJobTarget(content.getJobTarget(), errors);
        validateEducation(content.getEducation(), errors);
        validateSkillCategories(content.getSkillCategories(), errors);
        validateExperience(content.getExperience(), errors);
        validateProjects(content.getProjects(), errors);
        validateVolunteer(content.getVolunteer(), errors);
        validateCertifications(content.getCertifications(), errors);
        validateLanguages(content.getLanguages(), errors);
        validateLeadership(content.getLeadership(), errors);
        validatePublications(content.getPublications(), errors);
        validateHobbies(content.getHobbies(), errors);
        validateCustomSections(content.getCustomSections(), errors);
        validateSectionOrder(content, errors);
        validateCareerMaterials(content.getCareerMaterials(), errors);

        if (!errors.isEmpty()) {
            throw new InvalidResumeBuilderDraftException(String.join("; ", errors));
        }
    }

    public void validateTemplateType(String templateType) {
        if (templateType == null) {
            throw new InvalidResumeBuilderDraftException("templateType is required");
        }
        try {
            ResumeBuilderTemplateType.valueOf(templateType);
        } catch (IllegalArgumentException e) {
            throw new InvalidResumeBuilderDraftException("templateType is not a recognized template type");
        }
    }

    public void validateLocale(String locale) {
        if (locale == null || !VALID_LOCALES.contains(locale)) {
            throw new InvalidResumeBuilderDraftException("locale must be one of " + VALID_LOCALES);
        }
    }

    // --- individual sections ------------------------------------------------

    private void validateBasicInfo(BasicInfo info, List<String> errors) {
        if (info == null) {
            return;
        }
        text("basicInfo.fullName", info.getFullName(), SHORT, errors);
        text("basicInfo.city", info.getCity(), SHORT, errors);
        text("basicInfo.country", info.getCountry(), SHORT, errors);
        text("basicInfo.phone", info.getPhone(), SHORT, errors);
        text("basicInfo.email", info.getEmail(), SHORT, errors);
        url("basicInfo.linkedin", info.getLinkedin(), errors);
        url("basicInfo.github", info.getGithub(), errors);
        url("basicInfo.website", info.getWebsite(), errors);
    }

    private void validateJobTarget(JobTarget target, List<String> errors) {
        if (target == null) {
            return;
        }
        text("jobTarget.targetTitle", target.getTargetTitle(), SHORT, errors);
        text("jobTarget.jobLevel", target.getJobLevel(), SHORT, errors);
        text("jobTarget.targetIndustry", target.getTargetIndustry(), SHORT, errors);
        text("jobTarget.workMode", target.getWorkMode(), SHORT, errors);
        text("jobTarget.targetCity", target.getTargetCity(), SHORT, errors);
        text("jobTarget.careerSummary", target.getCareerSummary(), LONG, errors);
    }

    private void validateEducation(List<EducationEntry> education, List<String> errors) {
        if (education == null) {
            return;
        }
        maxSize("education", education.size(), MAX_EDUCATION, errors);
        for (int i = 0; i < education.size(); i++) {
            EducationEntry e = education.get(i);
            String p = "education[" + i + "].";
            text(p + "school", e.getSchool(), SHORT, errors);
            text(p + "degree", e.getDegree(), SHORT, errors);
            text(p + "major", e.getMajor(), SHORT, errors);
            text(p + "gpa", e.getGpa(), 20, errors);
            text(p + "honors", e.getHonors(), MEDIUM, errors);
            dateRange(p, e.getStartDate(), e.getEndDate(), false, errors);
            list(p + "courses", e.getCourses(), MAX_COURSES, SHORT, errors);
        }
    }

    private void validateSkillCategories(List<SkillCategory> categories, List<String> errors) {
        if (categories == null) {
            return;
        }
        maxSize("skillCategories", categories.size(), MAX_SKILL_CATEGORIES, errors);
        for (int i = 0; i < categories.size(); i++) {
            SkillCategory c = categories.get(i);
            String p = "skillCategories[" + i + "].";
            text(p + "name", c.getName(), SHORT, errors);
            list(p + "skills", c.getSkills(), MAX_SKILLS_PER_CATEGORY, SHORT, errors);
        }
    }

    private void validateExperience(List<WorkExperienceEntry> experience, List<String> errors) {
        if (experience == null) {
            return;
        }
        maxSize("experience", experience.size(), MAX_EXPERIENCE, errors);
        for (int i = 0; i < experience.size(); i++) {
            WorkExperienceEntry e = experience.get(i);
            String p = "experience[" + i + "].";
            text(p + "company", e.getCompany(), SHORT, errors);
            text(p + "title", e.getTitle(), SHORT, errors);
            text(p + "location", e.getLocation(), SHORT, errors);
            boolean current = Boolean.TRUE.equals(e.getCurrent());
            dateRange(p, e.getStartDate(), current ? "" : e.getEndDate(), current, errors);
            list(p + "responsibilities", e.getResponsibilities(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            list(p + "toolsUsed", e.getToolsUsed(), MAX_LINES_PER_ENTRY, SHORT, errors);
            list(p + "outcomes", e.getOutcomes(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        }
    }

    private void validateProjects(List<ProjectEntry> projects, List<String> errors) {
        if (projects == null) {
            return;
        }
        maxSize("projects", projects.size(), MAX_PROJECTS, errors);
        for (int i = 0; i < projects.size(); i++) {
            ProjectEntry e = projects.get(i);
            String p = "projects[" + i + "].";
            text(p + "name", e.getName(), SHORT, errors);
            text(p + "purpose", e.getPurpose(), LONG, errors);
            text(p + "actualDuration", e.getActualDuration(), SHORT, errors);
            text(p + "role", e.getRole(), SHORT, errors);
            dateRange(p, e.getStartDate(), e.getEndDate(), false, errors);
            list(p + "techStack", e.getTechStack(), MAX_LINES_PER_ENTRY, SHORT, errors);
            list(p + "features", e.getFeatures(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            list(p + "challenges", e.getChallenges(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            list(p + "solutions", e.getSolutions(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            list(p + "outcomes", e.getOutcomes(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            url(p + "githubUrl", e.getGithubUrl(), errors);
            url(p + "demoUrl", e.getDemoUrl(), errors);
            nonNegative(p + "teamSize", e.getTeamSize() == null ? null : (long) e.getTeamSize(),
                    MAX_TEAM_SIZE, errors);
            nonNegative(p + "userCount", e.getUserCount(), MAX_USER_COUNT, errors);
        }
    }

    private void validateVolunteer(List<VolunteerEntry> volunteer, List<String> errors) {
        if (volunteer == null) {
            return;
        }
        maxSize("volunteer", volunteer.size(), MAX_VOLUNTEER, errors);
        for (int i = 0; i < volunteer.size(); i++) {
            VolunteerEntry e = volunteer.get(i);
            String p = "volunteer[" + i + "].";
            text(p + "organization", e.getOrganization(), SHORT, errors);
            text(p + "role", e.getRole(), SHORT, errors);
            text(p + "location", e.getLocation(), SHORT, errors);
            dateRange(p, e.getStartDate(), e.getEndDate(), false, errors);
            list(p + "workContent", e.getWorkContent(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            list(p + "impact", e.getImpact(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
            nonNegative(p + "serviceHours", e.getServiceHours() == null ? null : (long) e.getServiceHours(),
                    MAX_SERVICE_HOURS, errors);
        }
    }

    private void validateCertifications(List<Certification> certifications, List<String> errors) {
        if (certifications == null) {
            return;
        }
        maxSize("certifications", certifications.size(), MAX_CERTIFICATIONS, errors);
        for (int i = 0; i < certifications.size(); i++) {
            Certification e = certifications.get(i);
            String p = "certifications[" + i + "].";
            text(p + "name", e.getName(), SHORT, errors);
            text(p + "issuer", e.getIssuer(), SHORT, errors);
            text(p + "credentialId", e.getCredentialId(), SHORT, errors);
            url(p + "credentialUrl", e.getCredentialUrl(), errors);
            singleDate(p + "issueDate", e.getIssueDate(), errors);
            singleDate(p + "expiryDate", e.getExpiryDate(), errors);
        }
    }

    private void validateLanguages(List<LanguageEntry> languages, List<String> errors) {
        if (languages == null) {
            return;
        }
        maxSize("languages", languages.size(), MAX_LANGUAGES, errors);
        for (int i = 0; i < languages.size(); i++) {
            LanguageEntry e = languages.get(i);
            String p = "languages[" + i + "].";
            text(p + "language", e.getLanguage(), SHORT, errors);
            text(p + "proficiency", e.getProficiency(), SHORT, errors);
        }
    }

    private void validateLeadership(List<LeadershipEntry> leadership, List<String> errors) {
        if (leadership == null) {
            return;
        }
        maxSize("leadership", leadership.size(), MAX_LEADERSHIP, errors);
        for (int i = 0; i < leadership.size(); i++) {
            LeadershipEntry e = leadership.get(i);
            String p = "leadership[" + i + "].";
            text(p + "organization", e.getOrganization(), SHORT, errors);
            text(p + "role", e.getRole(), SHORT, errors);
            dateRange(p, e.getStartDate(), e.getEndDate(), false, errors);
            list(p + "description", e.getDescription(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        }
    }

    private void validatePublications(List<Publication> publications, List<String> errors) {
        if (publications == null) {
            return;
        }
        maxSize("publications", publications.size(), MAX_PUBLICATIONS, errors);
        for (int i = 0; i < publications.size(); i++) {
            Publication e = publications.get(i);
            String p = "publications[" + i + "].";
            text(p + "title", e.getTitle(), SHORT, errors);
            text(p + "venue", e.getVenue(), SHORT, errors);
            text(p + "authors", e.getAuthors(), MEDIUM, errors);
            url(p + "url", e.getUrl(), errors);
            singleDate(p + "date", e.getDate(), errors);
        }
    }

    private void validateHobbies(List<String> hobbies, List<String> errors) {
        list("hobbies", hobbies, MAX_HOBBIES, SHORT, errors);
    }

    private void validateCustomSections(List<CustomSection> sections, List<String> errors) {
        if (sections == null) {
            return;
        }
        maxSize("customSections", sections.size(), MAX_CUSTOM_SECTIONS, errors);
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            CustomSection s = sections.get(i);
            String p = "customSections[" + i + "].";
            text(p + "title", s.getTitle(), SHORT, errors);
            list(p + "items", s.getItems(), MAX_ITEMS_PER_CUSTOM_SECTION, MEDIUM, errors);
            if (s.getId() != null && !seenIds.add(s.getId())) {
                errors.add("customSections has a duplicate section id: " + s.getId());
            }
        }
    }

    /** No duplicate entries; every entry is either a fixed section id or a
     * {@code CUSTOM:<id>} ref to a custom section that actually exists. */
    private void validateSectionOrder(ResumeBuilderDraftContent content, List<String> errors) {
        List<String> order = content.getSectionOrder();
        if (order == null) {
            return;
        }
        Set<String> validCustomRefs = new HashSet<>();
        if (content.getCustomSections() != null) {
            for (CustomSection s : content.getCustomSections()) {
                if (s.getId() != null) {
                    validCustomRefs.add(ResumeBuilderSectionIds.customSectionRef(s.getId()));
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (String id : order) {
            if (!seen.add(id)) {
                errors.add("sectionOrder has a duplicate entry: " + id);
                continue;
            }
            boolean known = ResumeBuilderSectionIds.FIXED_IDS.contains(id) || validCustomRefs.contains(id);
            if (!known) {
                errors.add("sectionOrder references an unknown section: " + id);
            }
        }
    }

    private void validateCareerMaterials(CareerMaterials materials, List<String> errors) {
        if (materials == null) {
            return;
        }
        list("careerMaterials.selfIntroFacts", materials.getSelfIntroFacts(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        list("careerMaterials.aiUsageScenarios", materials.getAiUsageScenarios(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        list("careerMaterials.aiStrengths", materials.getAiStrengths(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        list("careerMaterials.aiRisks", materials.getAiRisks(), MAX_LINES_PER_ENTRY, MEDIUM, errors);
        list("careerMaterials.aiVerificationApproach", materials.getAiVerificationApproach(),
                MAX_LINES_PER_ENTRY, MEDIUM, errors);
        list("careerMaterials.skillsToImprove", materials.getSkillsToImprove(), MAX_LINES_PER_ENTRY, SHORT, errors);
        text("careerMaterials.oneYearGoal", materials.getOneYearGoal(), LONG, errors);
        text("careerMaterials.threeYearGoal", materials.getThreeYearGoal(), LONG, errors);
    }

    // --- primitives -----------------------------------------------------

    private void text(String field, String value, int maxLen, List<String> errors) {
        if (value == null) {
            return;
        }
        if (value.length() > maxLen) {
            errors.add(field + " is too long (max " + maxLen + " characters)");
        }
        if (ILLEGAL_CHARS.matcher(value).find()) {
            errors.add(field + " contains characters that are not allowed");
        }
    }

    private void list(String field, List<String> values, int maxItems, int maxLen, List<String> errors) {
        if (values == null) {
            return;
        }
        if (values.size() > maxItems) {
            errors.add(field + " has too many entries (max " + maxItems + ")");
        }
        for (int i = 0; i < values.size(); i++) {
            text(field + "[" + i + "]", values.get(i), maxLen, errors);
        }
    }

    private void maxSize(String field, int size, int max, List<String> errors) {
        if (size > max) {
            errors.add(field + " has too many entries (max " + max + ")");
        }
    }

    private void url(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (value.length() > URL_MAX) {
            errors.add(field + " is too long (max " + URL_MAX + " characters)");
            return;
        }
        if (ILLEGAL_CHARS.matcher(value).find()) {
            errors.add(field + " contains characters that are not allowed");
            return;
        }
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            errors.add(field + " must start with http:// or https://");
        }
    }

    private void nonNegative(String field, Long value, long max, List<String> errors) {
        if (value == null) {
            return;
        }
        if (value < 0) {
            errors.add(field + " cannot be negative");
        } else if (value > max) {
            errors.add(field + " is unreasonably large (max " + max + ")");
        }
    }

    /** Blank is fine (not entered). "Present"/"至今"/... is fine only via
     * {@code allowOngoing}. Otherwise must be YYYY-MM or YYYY-MM-DD, and (when both
     * ends are real dates) start must not be after end. */
    private void dateRange(String prefix, String start, String end, boolean allowOngoing, List<String> errors) {
        LocalDate startDate = singleDate(prefix + "startDate", start, errors);
        if (allowOngoing) {
            return;
        }
        LocalDate endDate = singleDate(prefix + "endDate", end, errors);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            errors.add(prefix + "startDate must not be after " + prefix + "endDate");
        }
    }

    /** Returns the parsed date when {@code value} is a real YYYY-MM/YYYY-MM-DD date,
     * null when blank or an accepted "ongoing" marker, and records a violation (also
     * returning null) for anything else. */
    private LocalDate singleDate(String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (ONGOING_MARKERS.contains(trimmed.toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        if (!DATE_YM_OR_YMD.matcher(trimmed).matches()) {
            errors.add(field + " must be in YYYY-MM or YYYY-MM-DD format");
            return null;
        }
        try {
            String normalized = trimmed.length() == 7 ? trimmed + "-01" : trimmed;
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            errors.add(field + " is not a valid date");
            return null;
        }
    }
}
