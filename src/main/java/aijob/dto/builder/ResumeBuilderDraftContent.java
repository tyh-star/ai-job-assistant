package aijob.dto.builder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full typed content of one resume-builder draft -- everything the user has typed
 * into the blank, generic form. This is the ONLY shape {@code draft_json} is ever
 * treated as; the service layer deserializes into this class (never handles the JSON
 * as an opaque string) and {@link aijob.util.ResumeBuilderDraftValidator} validates
 * every field before a save is accepted. See ResumeBuilderNormalizer for how this
 * becomes the single rendered model shared by preview and DOCX generation.
 * <p>
 * A brand new draft's content is {@link #blank()} -- every field empty, no defaults
 * from any real person. Nothing in this class or its nested classes may ever be
 * pre-filled with a real person's data (see the resume-builder feature spec's absolute
 * prohibition list).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeBuilderDraftContent {

    public static final int CONTENT_SCHEMA_VERSION = 1;

    private Integer schemaVersion;
    private BasicInfo basicInfo;
    private JobTarget jobTarget;
    private List<EducationEntry> education;
    private List<SkillCategory> skillCategories;
    private List<WorkExperienceEntry> experience;
    private List<ProjectEntry> projects;
    private List<VolunteerEntry> volunteer;
    private List<Certification> certifications;
    private List<LanguageEntry> languages;
    private List<LeadershipEntry> leadership;
    private List<Publication> publications;
    private List<String> hobbies;
    private List<CustomSection> customSections;
    /** Ordered list of section ids (fixed ids from {@link ResumeBuilderSectionIds} plus
     * {@code CUSTOM:<id>} refs) -- the order sections render in when enabled. */
    private List<String> sectionOrder;
    /** Section id -> whether it is enabled. Missing entries default to enabled=true. */
    private Map<String, Boolean> sectionEnabled;
    private CareerMaterials careerMaterials;

    public static ResumeBuilderDraftContent blank() {
        ResumeBuilderDraftContent content = new ResumeBuilderDraftContent();
        content.normalize();
        return content;
    }

    public void normalize() {
        schemaVersion = CONTENT_SCHEMA_VERSION;
        if (basicInfo == null) {
            basicInfo = new BasicInfo();
        }
        basicInfo.normalize();
        if (jobTarget == null) {
            jobTarget = new JobTarget();
        }
        jobTarget.normalize();
        education = normalizeList(education, EducationEntry::normalize);
        skillCategories = normalizeList(skillCategories, SkillCategory::normalize);
        experience = normalizeList(experience, WorkExperienceEntry::normalize);
        projects = normalizeList(projects, ProjectEntry::normalize);
        volunteer = normalizeList(volunteer, VolunteerEntry::normalize);
        certifications = normalizeList(certifications, Certification::normalize);
        languages = normalizeList(languages, LanguageEntry::normalize);
        leadership = normalizeList(leadership, LeadershipEntry::normalize);
        publications = normalizeList(publications, Publication::normalize);
        if (hobbies == null) {
            hobbies = new ArrayList<>();
        }
        customSections = normalizeList(customSections, CustomSection::normalize);
        if (sectionEnabled == null) {
            sectionEnabled = new LinkedHashMap<>();
        }
        if (sectionOrder == null) {
            sectionOrder = new ArrayList<>();
        }
        // Any known section (fixed or custom) missing from sectionOrder is appended at
        // the end, enabled by default -- so a brand new draft, or one saved before a
        // custom section existed, always has a complete, well-formed order.
        List<String> known = new ArrayList<>(ResumeBuilderSectionIds.DEFAULT_ORDER);
        for (CustomSection section : customSections) {
            known.add(ResumeBuilderSectionIds.customSectionRef(section.getId()));
        }
        for (String id : known) {
            if (!sectionOrder.contains(id)) {
                sectionOrder.add(id);
            }
            sectionEnabled.putIfAbsent(id, Boolean.TRUE);
        }
        if (careerMaterials == null) {
            careerMaterials = new CareerMaterials();
        }
        careerMaterials.normalize();
    }

    private interface Normalizer<T> {
        void normalize(T item);
    }

    private static <T> List<T> normalizeList(List<T> list, Normalizer<T> normalizer) {
        List<T> result = list == null ? new ArrayList<>() : new ArrayList<>(list);
        result.removeIf(java.util.Objects::isNull);
        result.forEach(normalizer::normalize);
        return result;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BasicInfo {
        private String fullName;
        private String city;
        private String country;
        private String phone;
        private String email;
        private String linkedin;
        private String github;
        private String website;
        /** Null = not stated. Deliberately the ONLY "eligibility" style field -- age,
         * gender, photo, and marital status are never collected by this form. */
        private Boolean legallyEligibleToWork;

        public void normalize() {
            fullName = orEmpty(fullName);
            city = orEmpty(city);
            country = orEmpty(country);
            phone = orEmpty(phone);
            email = orEmpty(email);
            linkedin = orEmpty(linkedin);
            github = orEmpty(github);
            website = orEmpty(website);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobTarget {
        private String targetTitle;
        private String jobLevel;
        private String targetIndustry;
        private String workMode;
        private String targetCity;
        private String careerSummary;

        public void normalize() {
            targetTitle = orEmpty(targetTitle);
            jobLevel = orEmpty(jobLevel);
            targetIndustry = orEmpty(targetIndustry);
            workMode = orEmpty(workMode);
            targetCity = orEmpty(targetCity);
            careerSummary = orEmpty(careerSummary);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EducationEntry {
        private String school;
        private String degree;
        private String major;
        private String startDate;
        private String endDate;
        private String gpa;
        private String honors;
        private List<String> courses;

        public void normalize() {
            school = orEmpty(school);
            degree = orEmpty(degree);
            major = orEmpty(major);
            startDate = orEmpty(startDate);
            endDate = orEmpty(endDate);
            gpa = orEmpty(gpa);
            honors = orEmpty(honors);
            courses = courses == null ? new ArrayList<>() : new ArrayList<>(courses);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillCategory {
        private String id;
        private String name;
        private List<String> skills;

        public void normalize() {
            if (id == null || id.isBlank()) {
                id = java.util.UUID.randomUUID().toString();
            }
            name = orEmpty(name);
            skills = skills == null ? new ArrayList<>() : new ArrayList<>(skills);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkExperienceEntry {
        private String company;
        private String title;
        private String location;
        private String startDate;
        private String endDate;
        private Boolean current;
        private List<String> responsibilities;
        private List<String> toolsUsed;
        private List<String> outcomes;

        public void normalize() {
            company = orEmpty(company);
            title = orEmpty(title);
            location = orEmpty(location);
            startDate = orEmpty(startDate);
            endDate = orEmpty(endDate);
            current = current != null && current;
            responsibilities = responsibilities == null ? new ArrayList<>() : new ArrayList<>(responsibilities);
            toolsUsed = toolsUsed == null ? new ArrayList<>() : new ArrayList<>(toolsUsed);
            outcomes = outcomes == null ? new ArrayList<>() : new ArrayList<>(outcomes);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectEntry {
        private String name;
        private String purpose;
        private String startDate;
        private String endDate;
        private String actualDuration;
        private List<String> techStack;
        private String role;
        private Integer teamSize;
        private List<String> features;
        private List<String> challenges;
        private List<String> solutions;
        private Long userCount;
        private List<String> outcomes;
        private String githubUrl;
        private String demoUrl;

        /** Never a user-editable field -- always the actual length of {@link #features}.
         * See the resume-builder spec's "feature count must be computed, never typed
         * in." */
        public int featureCount() {
            return features == null ? 0 : features.size();
        }

        public void normalize() {
            name = orEmpty(name);
            purpose = orEmpty(purpose);
            startDate = orEmpty(startDate);
            endDate = orEmpty(endDate);
            actualDuration = orEmpty(actualDuration);
            techStack = techStack == null ? new ArrayList<>() : new ArrayList<>(techStack);
            role = orEmpty(role);
            features = features == null ? new ArrayList<>() : new ArrayList<>(features);
            challenges = challenges == null ? new ArrayList<>() : new ArrayList<>(challenges);
            solutions = solutions == null ? new ArrayList<>() : new ArrayList<>(solutions);
            outcomes = outcomes == null ? new ArrayList<>() : new ArrayList<>(outcomes);
            githubUrl = orEmpty(githubUrl);
            demoUrl = orEmpty(demoUrl);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VolunteerEntry {
        private String organization;
        private String role;
        private String location;
        private String startDate;
        private String endDate;
        private Integer serviceHours;
        private List<String> workContent;
        private List<String> impact;

        public void normalize() {
            organization = orEmpty(organization);
            role = orEmpty(role);
            location = orEmpty(location);
            startDate = orEmpty(startDate);
            endDate = orEmpty(endDate);
            workContent = workContent == null ? new ArrayList<>() : new ArrayList<>(workContent);
            impact = impact == null ? new ArrayList<>() : new ArrayList<>(impact);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Certification {
        private String name;
        private String issuer;
        private String issueDate;
        private String expiryDate;
        private String credentialId;
        private String credentialUrl;

        public void normalize() {
            name = orEmpty(name);
            issuer = orEmpty(issuer);
            issueDate = orEmpty(issueDate);
            expiryDate = orEmpty(expiryDate);
            credentialId = orEmpty(credentialId);
            credentialUrl = orEmpty(credentialUrl);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LanguageEntry {
        private String language;
        private String proficiency;

        public void normalize() {
            language = orEmpty(language);
            proficiency = orEmpty(proficiency);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeadershipEntry {
        private String organization;
        private String role;
        private String startDate;
        private String endDate;
        private List<String> description;

        public void normalize() {
            organization = orEmpty(organization);
            role = orEmpty(role);
            startDate = orEmpty(startDate);
            endDate = orEmpty(endDate);
            description = description == null ? new ArrayList<>() : new ArrayList<>(description);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Publication {
        private String title;
        private String venue;
        private String date;
        private String url;
        private String authors;

        public void normalize() {
            title = orEmpty(title);
            venue = orEmpty(venue);
            date = orEmpty(date);
            url = orEmpty(url);
            authors = orEmpty(authors);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomSection {
        private String id;
        private String title;
        private List<String> items;

        public void normalize() {
            if (id == null || id.isBlank()) {
                id = java.util.UUID.randomUUID().toString();
            }
            title = orEmpty(title);
            items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        }
    }

    /** Facts for the OPTIONAL, separately-generated career materials (positioning
     * summary, 60-second self-introduction, AI-usage reflection, strengths/weaknesses,
     * development plan). Never auto-inserted into the formal ATS resume -- see
     * CareerMaterialsGenerator. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CareerMaterials {
        private List<String> selfIntroFacts;
        private List<String> aiUsageScenarios;
        private List<String> aiStrengths;
        private List<String> aiRisks;
        private List<String> aiVerificationApproach;
        private String oneYearGoal;
        private String threeYearGoal;
        private List<String> skillsToImprove;

        public void normalize() {
            selfIntroFacts = selfIntroFacts == null ? new ArrayList<>() : new ArrayList<>(selfIntroFacts);
            aiUsageScenarios = aiUsageScenarios == null ? new ArrayList<>() : new ArrayList<>(aiUsageScenarios);
            aiStrengths = aiStrengths == null ? new ArrayList<>() : new ArrayList<>(aiStrengths);
            aiRisks = aiRisks == null ? new ArrayList<>() : new ArrayList<>(aiRisks);
            aiVerificationApproach = aiVerificationApproach == null
                    ? new ArrayList<>() : new ArrayList<>(aiVerificationApproach);
            oneYearGoal = orEmpty(oneYearGoal);
            threeYearGoal = orEmpty(threeYearGoal);
            skillsToImprove = skillsToImprove == null ? new ArrayList<>() : new ArrayList<>(skillsToImprove);
        }
    }
}
