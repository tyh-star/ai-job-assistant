package aijob.util;

import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.NormalizedResume.Header;
import aijob.dto.builder.NormalizedResume.NormalizedBlock;
import aijob.dto.builder.NormalizedResume.NormalizedSection;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
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
import aijob.dto.builder.ResumeBuilderDraftContent.Certification;
import aijob.dto.builder.ResumeBuilderSectionIds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a draft's typed content into the ONE rendered model ({@link NormalizedResume})
 * that both the preview endpoint and {@link aijob.service.ResumeBuilderDocxService}
 * consume -- see {@link NormalizedResume}'s javadoc for why that matters. Pure and
 * deterministic: never calls any AI service, never invents a fact that was not
 * actually typed in, and always omits a section/entry that has no real content rather
 * than rendering an empty heading.
 */
@Component
public class ResumeBuilderNormalizer {

    /** Rough one-page budget for the fixed-size single-column ATS layout
     * {@link aijob.service.impl.ResumeBuilderDocxServiceImpl} renders at -- a heading
     * or meta line counts as one line, each bullet counts as one line, and the summary
     * is estimated at one line per ~90 characters. This is a heuristic warning only; it
     * never blocks generation. */
    private static final int ONE_PAGE_LINE_BUDGET = 52;

    public NormalizedResume normalize(ResumeBuilderDraftContent content, String locale) {
        Labels labels = Labels.forLocale(locale);
        NormalizedResume result = new NormalizedResume();

        result.setHeader(buildHeader(content.getBasicInfo(), content.getJobTarget()));
        String summary = content.getJobTarget() == null ? "" : blank(content.getJobTarget().getCareerSummary());
        result.setSummary(summary.isBlank() ? null : summary);

        Map<String, NormalizedSection> built = buildAllSections(content, labels);

        List<String> order = content.getSectionOrder() == null
                ? ResumeBuilderSectionIds.DEFAULT_ORDER : content.getSectionOrder();
        Map<String, Boolean> enabled = content.getSectionEnabled() == null
                ? Map.of() : content.getSectionEnabled();

        List<NormalizedSection> sections = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (String id : order) {
            if (Boolean.FALSE.equals(enabled.get(id))) {
                continue;
            }
            NormalizedSection section = built.get(id);
            if (section == null || section.getBlocks().isEmpty()) {
                omitted.add(id);
                continue;
            }
            sections.add(section);
        }
        result.setSections(sections);
        result.setOmittedSectionIds(omitted);

        result.setMissingKeyFacts(missingKeyFacts(content, labels));
        List<String> warnings = new ArrayList<>();
        int estimatedLines = estimateLines(result);
        if (estimatedLines > ONE_PAGE_LINE_BUDGET) {
            warnings.add(labels.contentMayOverflow);
        }
        result.setWarnings(warnings);

        boolean hasHeaderContent = !result.getHeader().getFullName().isBlank()
                || !result.getHeader().getEmail().isBlank()
                || !result.getHeader().getPhone().isBlank()
                || !result.getHeader().getLinks().isEmpty();
        result.setReadyToGenerate(hasHeaderContent || result.getSummary() != null || !sections.isEmpty());

        return result;
    }

    private Header buildHeader(BasicInfo info, JobTarget target) {
        Header header = new Header();
        if (info == null) {
            info = new BasicInfo();
            info.normalize();
        }
        header.setFullName(blank(info.getFullName()));
        header.setLocation(join(", ", blank(info.getCity()), blank(info.getCountry())));
        header.setPhone(blank(info.getPhone()));
        header.setEmail(blank(info.getEmail()));
        List<String> links = new ArrayList<>();
        addIfPresent(links, info.getLinkedin());
        addIfPresent(links, info.getGithub());
        addIfPresent(links, info.getWebsite());
        header.setLinks(links);
        header.setTargetTitle(target == null ? "" : blank(target.getTargetTitle()));
        return header;
    }

    private Map<String, NormalizedSection> buildAllSections(ResumeBuilderDraftContent content, Labels labels) {
        Map<String, NormalizedSection> sections = new LinkedHashMap<>();
        sections.put(ResumeBuilderSectionIds.SKILLS, skillsSection(content.getSkillCategories(), labels));
        sections.put(ResumeBuilderSectionIds.EXPERIENCE, experienceSection(content.getExperience(), labels));
        sections.put(ResumeBuilderSectionIds.EDUCATION, educationSection(content.getEducation(), labels));
        sections.put(ResumeBuilderSectionIds.PROJECTS, projectsSection(content.getProjects(), labels));
        sections.put(ResumeBuilderSectionIds.VOLUNTEER, volunteerSection(content.getVolunteer(), labels));
        sections.put(ResumeBuilderSectionIds.CERTIFICATIONS, certificationsSection(content.getCertifications(), labels));
        sections.put(ResumeBuilderSectionIds.LANGUAGES, languagesSection(content.getLanguages(), labels));
        sections.put(ResumeBuilderSectionIds.LEADERSHIP, leadershipSection(content.getLeadership(), labels));
        sections.put(ResumeBuilderSectionIds.PUBLICATIONS, publicationsSection(content.getPublications(), labels));
        sections.put(ResumeBuilderSectionIds.HOBBIES, hobbiesSection(content.getHobbies(), labels));
        if (content.getCustomSections() != null) {
            for (CustomSection custom : content.getCustomSections()) {
                String id = ResumeBuilderSectionIds.customSectionRef(custom.getId());
                sections.put(id, customSection(custom));
            }
        }
        sections.forEach((id, section) -> section.setId(id));
        return sections;
    }

    private NormalizedSection skillsSection(List<SkillCategory> categories, Labels labels) {
        NormalizedSection section = section(labels.skills);
        if (categories == null) {
            return section;
        }
        for (SkillCategory category : categories) {
            List<String> skills = category.getSkills() == null ? List.of() : category.getSkills();
            List<String> nonBlank = skills.stream().filter(s -> s != null && !s.isBlank()).toList();
            if (nonBlank.isEmpty()) {
                continue;
            }
            String name = blank(category.getName());
            String line = name.isBlank() ? String.join(", ", nonBlank) : name + ": " + String.join(", ", nonBlank);
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading("");
            block.setMeta("");
            block.setLines(List.of(line));
            block.setBulletedLines(false);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection experienceSection(List<WorkExperienceEntry> experience, Labels labels) {
        NormalizedSection section = section(labels.experience);
        if (experience == null) {
            return section;
        }
        for (WorkExperienceEntry e : experience) {
            String heading = join(" — ", blank(e.getTitle()), blank(e.getCompany()));
            List<String> lines = new ArrayList<>();
            addAll(lines, e.getResponsibilities());
            if (hasAny(e.getToolsUsed())) {
                lines.add(labels.tools + ": " + String.join(", ", nonBlankOf(e.getToolsUsed())));
            }
            addAll(lines, e.getOutcomes());
            if (heading.isBlank() && lines.isEmpty()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading.isBlank() ? blank(e.getLocation()) : heading);
            block.setMeta(join(" · ", dateRangeText(e.getStartDate(),
                    Boolean.TRUE.equals(e.getCurrent()) ? labels.present : blank(e.getEndDate())),
                    blank(e.getLocation())));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection educationSection(List<EducationEntry> education, Labels labels) {
        NormalizedSection section = section(labels.education);
        if (education == null) {
            return section;
        }
        for (EducationEntry e : education) {
            String degreeMajor = join(", ", blank(e.getDegree()), blank(e.getMajor()));
            String heading = join(" — ", degreeMajor, blank(e.getSchool()));
            List<String> lines = new ArrayList<>();
            addIfPresent(lines, blank(e.getHonors()));
            if (!blank(e.getGpa()).isBlank()) {
                lines.add("GPA: " + e.getGpa());
            }
            if (hasAny(e.getCourses())) {
                lines.add(labels.coursework + ": " + String.join(", ", nonBlankOf(e.getCourses())));
            }
            if (heading.isBlank() && lines.isEmpty()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading.isBlank() ? blank(e.getSchool()) : heading);
            block.setMeta(dateRangeText(e.getStartDate(), e.getEndDate()));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection projectsSection(List<ProjectEntry> projects, Labels labels) {
        NormalizedSection section = section(labels.projects);
        if (projects == null) {
            return section;
        }
        for (ProjectEntry e : projects) {
            String heading = blank(e.getName());
            List<String> lines = new ArrayList<>();
            addIfPresent(lines, blank(e.getPurpose()));
            if (!blank(e.getRole()).isBlank()) {
                lines.add(labels.role + ": " + e.getRole());
            }
            if (e.getTeamSize() != null) {
                lines.add(labels.teamSize + ": " + e.getTeamSize());
            }
            if (hasAny(e.getTechStack())) {
                lines.add(labels.techStack + ": " + String.join(", ", nonBlankOf(e.getTechStack())));
            }
            addAll(lines, e.getFeatures());
            List<String> challenges = nonBlankOf(e.getChallenges());
            List<String> solutions = nonBlankOf(e.getSolutions());
            for (int i = 0; i < Math.max(challenges.size(), solutions.size()); i++) {
                if (i < challenges.size()) {
                    lines.add(labels.challenge + ": " + challenges.get(i));
                }
                if (i < solutions.size()) {
                    lines.add(labels.solution + ": " + solutions.get(i));
                }
            }
            if (e.getUserCount() != null) {
                lines.add(labels.users + ": " + e.getUserCount());
            }
            addAll(lines, e.getOutcomes());
            if (!blank(e.getGithubUrl()).isBlank()) {
                lines.add("GitHub: " + e.getGithubUrl());
            }
            if (!blank(e.getDemoUrl()).isBlank()) {
                lines.add(labels.demo + ": " + e.getDemoUrl());
            }
            if (heading.isBlank() && lines.isEmpty()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading.isBlank() ? labels.projects : heading);
            String duration = !blank(e.getActualDuration()).isBlank()
                    ? e.getActualDuration() : dateRangeText(e.getStartDate(), e.getEndDate());
            block.setMeta(duration);
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection volunteerSection(List<VolunteerEntry> volunteer, Labels labels) {
        NormalizedSection section = section(labels.volunteer);
        if (volunteer == null) {
            return section;
        }
        for (VolunteerEntry e : volunteer) {
            String heading = join(" — ", blank(e.getRole()), blank(e.getOrganization()));
            List<String> lines = new ArrayList<>();
            addAll(lines, e.getWorkContent());
            addAll(lines, e.getImpact());
            if (e.getServiceHours() != null) {
                lines.add(labels.serviceHours + ": " + e.getServiceHours());
            }
            if (heading.isBlank() && lines.isEmpty()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading.isBlank() ? blank(e.getOrganization()) : heading);
            block.setMeta(join(" · ", dateRangeText(e.getStartDate(), e.getEndDate()), blank(e.getLocation())));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection certificationsSection(List<Certification> certifications, Labels labels) {
        NormalizedSection section = section(labels.certifications);
        if (certifications == null) {
            return section;
        }
        for (Certification e : certifications) {
            String heading = blank(e.getName());
            if (heading.isBlank()) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            if (!blank(e.getCredentialId()).isBlank()) {
                lines.add(labels.credentialId + ": " + e.getCredentialId());
            }
            if (!blank(e.getCredentialUrl()).isBlank()) {
                lines.add(e.getCredentialUrl());
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading);
            block.setMeta(join(" · ", blank(e.getIssuer()), dateRangeText(e.getIssueDate(), e.getExpiryDate())));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection languagesSection(List<LanguageEntry> languages, Labels labels) {
        NormalizedSection section = section(labels.languages);
        if (languages == null) {
            return section;
        }
        for (LanguageEntry e : languages) {
            String heading = blank(e.getLanguage());
            if (heading.isBlank()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading);
            block.setMeta(blank(e.getProficiency()));
            block.setLines(List.of());
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection leadershipSection(List<LeadershipEntry> leadership, Labels labels) {
        NormalizedSection section = section(labels.leadership);
        if (leadership == null) {
            return section;
        }
        for (LeadershipEntry e : leadership) {
            String heading = join(" — ", blank(e.getRole()), blank(e.getOrganization()));
            List<String> lines = new ArrayList<>(nonBlankOf(e.getDescription()));
            if (heading.isBlank() && lines.isEmpty()) {
                continue;
            }
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading.isBlank() ? blank(e.getOrganization()) : heading);
            block.setMeta(dateRangeText(e.getStartDate(), e.getEndDate()));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection publicationsSection(List<Publication> publications, Labels labels) {
        NormalizedSection section = section(labels.publications);
        if (publications == null) {
            return section;
        }
        for (Publication e : publications) {
            String heading = blank(e.getTitle());
            if (heading.isBlank()) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            addIfPresent(lines, blank(e.getAuthors()));
            addIfPresent(lines, blank(e.getUrl()));
            NormalizedBlock block = new NormalizedBlock();
            block.setHeading(heading);
            block.setMeta(join(" · ", blank(e.getVenue()), blank(e.getDate())));
            block.setLines(lines);
            section.getBlocks().add(block);
        }
        return section;
    }

    private NormalizedSection hobbiesSection(List<String> hobbies, Labels labels) {
        NormalizedSection section = section(labels.hobbies);
        List<String> nonBlank = nonBlankOf(hobbies);
        if (nonBlank.isEmpty()) {
            return section;
        }
        NormalizedBlock block = new NormalizedBlock();
        block.setHeading("");
        block.setMeta("");
        block.setLines(List.of(String.join(", ", nonBlank)));
        block.setBulletedLines(false);
        section.getBlocks().add(block);
        return section;
    }

    private NormalizedSection customSection(CustomSection custom) {
        NormalizedSection section = section(blank(custom.getTitle()));
        List<String> items = nonBlankOf(custom.getItems());
        if (items.isEmpty()) {
            return section;
        }
        NormalizedBlock block = new NormalizedBlock();
        block.setHeading("");
        block.setMeta("");
        block.setLines(items);
        section.getBlocks().add(block);
        return section;
    }

    private List<String> missingKeyFacts(ResumeBuilderDraftContent content, Labels labels) {
        List<String> missing = new ArrayList<>();
        BasicInfo info = content.getBasicInfo();
        if (info == null || blank(info.getFullName()).isBlank()) {
            missing.add(labels.missingName);
        }
        boolean hasContact = info != null
                && (!blank(info.getEmail()).isBlank() || !blank(info.getPhone()).isBlank());
        if (!hasContact) {
            missing.add(labels.missingContact);
        }
        JobTarget target = content.getJobTarget();
        if (target == null || blank(target.getTargetTitle()).isBlank()) {
            missing.add(labels.missingTargetTitle);
        }
        return missing;
    }

    private int estimateLines(NormalizedResume resume) {
        int lines = 2; // header name + contact line
        if (resume.getSummary() != null) {
            lines += Math.max(1, resume.getSummary().length() / 90);
        }
        for (NormalizedSection section : resume.getSections()) {
            lines += 1; // section title
            for (NormalizedBlock block : section.getBlocks()) {
                if (!block.getHeading().isBlank()) {
                    lines += 1;
                }
                if (!block.getMeta().isBlank()) {
                    lines += 1;
                }
                lines += block.getLines().size();
            }
        }
        return lines;
    }

    // --- small helpers ----------------------------------------------------

    private static NormalizedSection section(String title) {
        NormalizedSection section = new NormalizedSection();
        section.setTitle(title);
        section.setBlocks(new ArrayList<>());
        return section;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String join(String separator, String... parts) {
        List<String> nonBlank = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                nonBlank.add(part);
            }
        }
        return String.join(separator, nonBlank);
    }

    private static String dateRangeText(String start, String end) {
        String s = blank(start);
        String e = blank(end);
        if (s.isBlank() && e.isBlank()) {
            return "";
        }
        if (s.isBlank()) {
            return e;
        }
        if (e.isBlank()) {
            return s;
        }
        return s + " - " + e;
    }

    private static boolean hasAny(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private static List<String> nonBlankOf(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    private static void addAll(List<String> target, List<String> values) {
        target.addAll(nonBlankOf(values));
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static final class Labels {
        String skills, experience, education, projects, volunteer, certifications,
                languages, leadership, publications, hobbies;
        String tools, coursework, role, teamSize, techStack, challenge, solution, users, demo,
                serviceHours, credentialId, present;
        String contentMayOverflow, missingName, missingContact, missingTargetTitle;

        static Labels forLocale(String locale) {
            Labels l = new Labels();
            boolean zh = "zh-CN".equals(locale);
            l.skills = zh ? "技能" : "Skills";
            l.experience = zh ? "工作经历" : "Experience";
            l.education = zh ? "教育背景" : "Education";
            l.projects = zh ? "项目经历" : "Projects";
            l.volunteer = zh ? "志愿者经历" : "Volunteer Experience";
            l.certifications = zh ? "证书与奖项" : "Certificates & Awards";
            l.languages = zh ? "语言能力" : "Languages";
            l.leadership = zh ? "社团与领导经历" : "Clubs & Leadership";
            l.publications = zh ? "出版物" : "Publications";
            l.hobbies = zh ? "兴趣爱好" : "Hobbies";
            l.tools = zh ? "使用工具" : "Tools";
            l.coursework = zh ? "相关课程" : "Coursework";
            l.role = zh ? "担任角色" : "Role";
            l.teamSize = zh ? "团队规模" : "Team size";
            l.techStack = zh ? "技术栈" : "Tech stack";
            l.challenge = zh ? "挑战" : "Challenge";
            l.solution = zh ? "解决方案" : "Solution";
            l.users = zh ? "用户数" : "Users";
            l.demo = zh ? "演示" : "Demo";
            l.serviceHours = zh ? "服务时长" : "Service hours";
            l.credentialId = zh ? "证书编号" : "Credential ID";
            l.present = zh ? "至今" : "Present";
            l.contentMayOverflow = zh
                    ? "内容可能超过一页，建议精简后再生成（系统不会用极小字体强行压缩）"
                    : "Content may not fit on one page -- consider trimming before generating "
                    + "(never auto-shrunk to an unreadable font)";
            l.missingName = zh ? "姓名" : "your name";
            l.missingContact = zh ? "联系方式（邮箱或电话）" : "a way to contact you (email or phone)";
            l.missingTargetTitle = zh ? "目标职位" : "a target job title";
            return l;
        }
    }
}
