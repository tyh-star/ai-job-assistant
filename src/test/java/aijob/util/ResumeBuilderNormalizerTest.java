package aijob.util;

import aijob.dto.builder.NormalizedResume;
import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.CustomSection;
import aijob.dto.builder.ResumeBuilderDraftContent.EducationEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.ProjectEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.ProfessionalLink;
import aijob.dto.builder.ResumeBuilderDraftContent.SkillCategory;
import aijob.dto.builder.ResumeBuilderDraftContent.WorkExperienceEntry;
import aijob.dto.builder.ResumeBuilderSectionIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All fixtures: Alex Morgan, Example University, Example Company, Project Atlas --
 * entirely fictional. */
class ResumeBuilderNormalizerTest {

    private final ResumeBuilderNormalizer normalizer = new ResumeBuilderNormalizer();

    @Test
    void aCompletelyBlankDraftIsNotReadyToGenerateAndListsMissingFacts() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        NormalizedResume normalized = normalizer.normalize(content, "en");

        assertFalse(normalized.isReadyToGenerate());
        assertTrue(normalized.getSections().isEmpty());
        assertTrue(normalized.getMissingKeyFacts().contains("your name"));
    }

    @Test
    void justANameIsEnoughToBeReadyToGenerate() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        NormalizedResume normalized = normalizer.normalize(content, "en");

        assertTrue(normalized.isReadyToGenerate());
    }

    @Test
    void anEnabledSectionWithNoRealContentIsOmittedNotRenderedEmpty() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        // Education left entirely blank -- section stays enabled by default but must
        // not appear.
        NormalizedResume normalized = normalizer.normalize(content, "en");

        boolean educationRendered = normalized.getSections().stream()
                .anyMatch(s -> ResumeBuilderSectionIds.EDUCATION.equals(s.getId()));
        assertFalse(educationRendered);
        assertTrue(normalized.getOmittedSectionIds().contains(ResumeBuilderSectionIds.EDUCATION));
    }

    @Test
    void aFilledSectionRendersAndNeverInventsAFactNotEntered() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        edu.setDegree("B.Sc. Computer Science");
        edu.setStartDate("2018-09");
        edu.setEndDate("2022-06");
        // GPA and honors deliberately left blank.
        content.setEducation(List.of(edu));

        NormalizedResume normalized = normalizer.normalize(content, "en");

        var section = normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.EDUCATION.equals(s.getId()))
                .findFirst().orElseThrow();
        assertEquals(1, section.getBlocks().size());
        String heading = section.getBlocks().get(0).getHeading();
        assertTrue(heading.contains("Example University"));
        assertFalse(heading.contains("GPA"));
        assertTrue(section.getBlocks().get(0).getLines().stream().noneMatch(l -> l.contains("GPA")));
    }

    @Test
    void aDisabledSectionIsOmittedEvenWhenItHasContent() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        content.setEducation(List.of(edu));
        content.normalize();
        content.getSectionEnabled().put(ResumeBuilderSectionIds.EDUCATION, Boolean.FALSE);

        NormalizedResume normalized = normalizer.normalize(content, "en");

        boolean educationRendered = normalized.getSections().stream()
                .anyMatch(s -> ResumeBuilderSectionIds.EDUCATION.equals(s.getId()));
        assertFalse(educationRendered);
    }

    @Test
    void sectionsRenderInTheSavedOrder() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");

        SkillCategory skills = new SkillCategory();
        skills.setName("Languages");
        skills.setSkills(List.of("Java", "Python"));
        content.setSkillCategories(List.of(skills));

        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Engineer");
        content.setExperience(List.of(exp));

        content.normalize();
        List<String> order = new ArrayList<>();
        order.add(ResumeBuilderSectionIds.EXPERIENCE);
        order.add(ResumeBuilderSectionIds.SKILLS);
        for (String id : content.getSectionOrder()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        content.setSectionOrder(order);

        NormalizedResume normalized = normalizer.normalize(content, "en");
        assertEquals(ResumeBuilderSectionIds.EXPERIENCE, normalized.getSections().get(0).getId());
        assertEquals(ResumeBuilderSectionIds.SKILLS, normalized.getSections().get(1).getId());
    }

    @Test
    void projectFeatureCountIsAlwaysTheActualListLengthNeverAnEnteredNumber() {
        ProjectEntry project = new ProjectEntry();
        project.setName("Project Atlas");
        project.setFeatures(List.of("Search", "Export", "Import"));
        assertEquals(3, project.featureCount());

        project.setFeatures(new ArrayList<>());
        assertEquals(0, project.featureCount());
    }

    @Test
    void aCustomSectionRendersUnderItsOwnTitle() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        CustomSection custom = new CustomSection();
        custom.setId("awards");
        custom.setTitle("Awards");
        custom.setItems(List.of("Dean's list, 2021"));
        content.setCustomSections(List.of(custom));
        content.normalize();

        NormalizedResume normalized = normalizer.normalize(content, "en");
        String customId = ResumeBuilderSectionIds.customSectionRef("awards");
        var section = normalized.getSections().stream()
                .filter(s -> customId.equals(s.getId()))
                .findFirst().orElseThrow();
        assertEquals("Awards", section.getTitle());
        assertEquals(List.of("Dean's list, 2021"), section.getBlocks().get(0).getLines());
    }

    @Test
    void skillsAndHobbiesLinesAreNotBulletedButOtherSectionsAre() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        SkillCategory skills = new SkillCategory();
        skills.setName("Languages");
        skills.setSkills(List.of("Java"));
        content.setSkillCategories(List.of(skills));
        content.setHobbies(List.of("Chess", "Hiking"));

        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Engineer");
        exp.setResponsibilities(List.of("Built things"));
        content.setExperience(List.of(exp));

        NormalizedResume normalized = normalizer.normalize(content, "en");
        var skillsSection = normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.SKILLS.equals(s.getId())).findFirst().orElseThrow();
        assertFalse(skillsSection.getBlocks().get(0).isBulletedLines());

        var hobbiesSection = normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.HOBBIES.equals(s.getId())).findFirst().orElseThrow();
        assertFalse(hobbiesSection.getBlocks().get(0).isBulletedLines());

        var expSection = normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.EXPERIENCE.equals(s.getId())).findFirst().orElseThrow();
        assertTrue(expSection.getBlocks().get(0).isBulletedLines());
    }

    @Test
    void veryLongContentWarnsAboutOverflowButNeverBlocksGeneration() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        List<WorkExperienceEntry> jobs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            WorkExperienceEntry exp = new WorkExperienceEntry();
            exp.setCompany("Example Company " + i);
            exp.setTitle("Engineer");
            exp.setResponsibilities(List.of("Did thing A", "Did thing B", "Did thing C"));
            jobs.add(exp);
        }
        content.setExperience(jobs);

        NormalizedResume normalized = normalizer.normalize(content, "en");
        assertTrue(normalized.isReadyToGenerate());
        assertFalse(normalized.getWarnings().isEmpty());
    }

    @Test
    void chineseLocaleUsesChineseSectionTitles() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Engineer");
        content.setExperience(List.of(exp));

        NormalizedResume normalized = normalizer.normalize(content, "zh-CN");
        var section = normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.EXPERIENCE.equals(s.getId())).findFirst().orElseThrow();
        assertEquals("工作经历", section.getTitle());
    }

    @Test
    void emptyProfessionalLinkRowsAreOmittedFromTheHeader() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Jamie Lee");
        content.getBasicInfo().setEmail("jamie.lee@example.test");
        ProfessionalLink emptyGithub = ResumeBuilderProfessionalLinks.empty("GITHUB");
        content.getBasicInfo().setProfessionalLinks(List.of(emptyGithub));
        content.normalize();

        NormalizedResume normalized = normalizer.normalize(content, "en", "GENERIC_ATS");
        assertTrue(normalized.getHeader().getLinks().isEmpty());
        assertFalse(String.join("\n", normalized.getHeader().getLinks()).toLowerCase().contains("github"));
    }

    @Test
    void jamieLeeAdministrativeAssistantNeverEmitsGithubOrTechStackPlaceholders() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Jamie Lee");
        content.getBasicInfo().setCity("Ottawa");
        content.getBasicInfo().setCountry("Canada");
        content.getBasicInfo().setEmail("jamie.lee@example.test");
        content.getBasicInfo().setPhone("613-555-0140");
        content.getJobTarget().setTargetTitle("Administrative Assistant");
        content.getJobTarget().setCareerSummary("Keeps office schedules and correspondence organized.");
        SkillCategory skills = new SkillCategory();
        skills.setName("Office support");
        skills.setSkills(List.of("Customer service", "Calendar management", "Records filing"));
        content.setSkillCategories(List.of(skills));
        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Civic Office");
        exp.setTitle("Administrative Assistant");
        exp.setResponsibilities(List.of("Scheduled meetings", "Filed correspondence"));
        content.setExperience(List.of(exp));
        content.getBasicInfo().setProfessionalLinks(List.of());
        content.normalize();

        NormalizedResume normalized = normalizer.normalize(content, "en", "GENERIC_ATS");
        String blob = blob(normalized);
        assertTrue(blob.contains("Jamie Lee"));
        assertTrue(blob.contains("Administrative Assistant"));
        assertTrue(blob.contains("Customer service"));
        assertFalse(blob.toLowerCase().contains("github"));
        assertFalse(blob.contains("Java"));
        assertFalse(blob.contains("Python"));
        assertFalse(blob.contains("AWS"));
        assertFalse(blob.contains("N/A"));
        assertFalse(blob.toLowerCase().contains("not provided"));
        assertTrue(normalized.getHeader().getLinks().isEmpty());
    }

    @Test
    void alexMorganTechnicalDraftStillOutputsGithubAndProjectAtlas() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        content.getBasicInfo().setEmail("alex.morgan@example.com");
        content.getJobTarget().setTargetTitle("Junior Software Developer");
        ProfessionalLink github = ResumeBuilderProfessionalLinks.empty("GITHUB");
        github.setUrl("https://github.com/alexmorgan");
        content.getBasicInfo().setProfessionalLinks(List.of(github));
        ProjectEntry project = new ProjectEntry();
        project.setName("Project Atlas");
        project.setGithubUrl("https://github.com/alexmorgan/atlas");
        content.setProjects(List.of(project));
        content.normalize();

        NormalizedResume normalized = normalizer.normalize(content, "en", "TECHNICAL");
        String blob = blob(normalized);
        assertTrue(blob.contains("https://github.com/alexmorgan"));
        assertTrue(blob.contains("Project Atlas"));
        assertTrue(blob.contains("Link: https://github.com/alexmorgan/atlas"));
        assertFalse(blob.contains("GitHub: "));
        assertEquals("Projects", normalized.getSections().stream()
                .filter(s -> ResumeBuilderSectionIds.PROJECTS.equals(s.getId()))
                .findFirst().orElseThrow()
                .getTitle());
    }

    private static String blob(NormalizedResume normalized) {
        StringBuilder text = new StringBuilder();
        text.append(normalized.getHeader().getFullName()).append('\n');
        text.append(String.join("\n", normalized.getHeader().getLinks())).append('\n');
        text.append(normalized.getHeader().getTargetTitle()).append('\n');
        if (normalized.getSummary() != null) {
            text.append(normalized.getSummary()).append('\n');
        }
        normalized.getSections().forEach(section -> {
            text.append(section.getTitle()).append('\n');
            section.getBlocks().forEach(block -> {
                text.append(block.getHeading()).append('\n');
                block.getLines().forEach(line -> text.append(line).append('\n'));
            });
        });
        return text.toString();
    }
}
