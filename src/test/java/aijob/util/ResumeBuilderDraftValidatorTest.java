package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.EducationEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.ProjectEntry;
import aijob.dto.builder.ResumeBuilderDraftContent.WorkExperienceEntry;
import aijob.exception.InvalidResumeBuilderDraftException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All fixtures here are entirely fictional: Alex Morgan, Example University, Example
 * Company, Project Atlas. Never a real person's data. */
class ResumeBuilderDraftValidatorTest {

    private final ResumeBuilderDraftValidator validator = new ResumeBuilderDraftValidator();

    @Test
    void aFreshBlankDraftPassesValidation() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        assertDoesNotThrow(() -> validator.validate(content));
    }

    @Test
    void aReasonableFilledDraftPassesValidation() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("Alex Morgan");
        content.getBasicInfo().setEmail("alex.morgan@example.com");
        content.getBasicInfo().setProfessionalLinks(null);
        content.getBasicInfo().setLinkedin("https://linkedin.com/in/alexmorgan");
        content.getJobTarget().setTargetTitle("Software Engineer");

        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        edu.setDegree("B.Sc.");
        edu.setStartDate("2018-09");
        edu.setEndDate("2022-06");
        content.setEducation(List.of(edu));

        ProjectEntry project = new ProjectEntry();
        project.setName("Project Atlas");
        project.setFeatures(List.of("Search", "Export"));
        project.setGithubUrl("https://github.com/example/atlas");
        content.setProjects(List.of(project));

        content.normalize();
        assertDoesNotThrow(() -> validator.validate(content));
    }

    @Test
    void tooLongATextFieldIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setFullName("x".repeat(500));

        InvalidResumeBuilderDraftException ex =
                assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
        assertTrue(ex.getMessage().contains("fullName"));
    }

    @Test
    void aUrlWithoutHttpSchemeIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setGithub("javascript:alert(1)");

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void aPlainHttpsUrlIsAccepted() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getBasicInfo().setWebsite("https://example.com/alex");
        assertDoesNotThrow(() -> validator.validate(content));
    }

    @Test
    void startDateAfterEndDateIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        edu.setStartDate("2022-01");
        edu.setEndDate("2020-01");
        content.setEducation(List.of(edu));

        InvalidResumeBuilderDraftException ex =
                assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
        assertTrue(ex.getMessage().contains("startDate"));
    }

    @Test
    void aMalformedDateIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        EducationEntry edu = new EducationEntry();
        edu.setSchool("Example University");
        edu.setStartDate("not-a-date");
        content.setEducation(List.of(edu));

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void ongoingMarkersAreAcceptedAsEndDateWithoutRangeComparison() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        WorkExperienceEntry exp = new WorkExperienceEntry();
        exp.setCompany("Example Company");
        exp.setTitle("Engineer");
        exp.setStartDate("2023-01");
        exp.setCurrent(true);
        content.setExperience(List.of(exp));

        content.normalize();
        assertDoesNotThrow(() -> validator.validate(content));
    }

    @Test
    void tooManyEntriesInARepeatableSectionIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        List<EducationEntry> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            EducationEntry edu = new EducationEntry();
            edu.setSchool("Example University " + i);
            many.add(edu);
        }
        content.setEducation(many);

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void negativeOrUnreasonablyLargeNumbersAreRejected() {
        ResumeBuilderDraftContent negative = ResumeBuilderDraftContent.blank();
        ProjectEntry p1 = new ProjectEntry();
        p1.setName("Project Atlas");
        p1.setTeamSize(-1);
        negative.setProjects(List.of(p1));
        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(negative));

        ResumeBuilderDraftContent huge = ResumeBuilderDraftContent.blank();
        ProjectEntry p2 = new ProjectEntry();
        p2.setName("Project Atlas");
        p2.setUserCount(9999999999L);
        huge.setProjects(List.of(p2));
        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(huge));
    }

    @Test
    void aControlCharacterInTextIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        int controlCodePoint = 1;
        String withControlChar = "Alex" + Character.toString(controlCodePoint) + "Morgan";
        content.getBasicInfo().setFullName(withControlChar);

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void newlinesAndTabsAreAllowedInsideText() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.getJobTarget().setCareerSummary("Line one\nLine two\tindented");
        assertDoesNotThrow(() -> validator.validate(content));
    }

    @Test
    void duplicateCustomSectionIdsAreRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        ResumeBuilderDraftContent.CustomSection a = new ResumeBuilderDraftContent.CustomSection();
        a.setId("dup");
        a.setTitle("A");
        ResumeBuilderDraftContent.CustomSection b = new ResumeBuilderDraftContent.CustomSection();
        b.setId("dup");
        b.setTitle("B");
        content.setCustomSections(List.of(a, b));

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void aDuplicateEntryInSectionOrderIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.normalize();
        List<String> order = new ArrayList<>(content.getSectionOrder());
        order.add(order.get(0));
        content.setSectionOrder(order);

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void anUnknownEntryInSectionOrderIsRejected() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.normalize();
        List<String> order = new ArrayList<>(content.getSectionOrder());
        order.add("NOT_A_REAL_SECTION");
        content.setSectionOrder(order);

        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validate(content));
    }

    @Test
    void unrecognizedTemplateTypeIsRejected() {
        assertThrows(InvalidResumeBuilderDraftException.class,
                () -> validator.validateTemplateType("NOT_A_TEMPLATE"));
    }

    @Test
    void everyDocumentedTemplateTypeIsAccepted() {
        for (String type : List.of("STUDENT", "INTERNSHIP", "EXPERIENCED", "CAREER_CHANGE", "TECHNICAL", "GENERIC_ATS")) {
            assertDoesNotThrow(() -> validator.validateTemplateType(type));
        }
    }

    @Test
    void onlyEnglishAndSimplifiedChineseLocalesAreAccepted() {
        assertDoesNotThrow(() -> validator.validateLocale("en"));
        assertDoesNotThrow(() -> validator.validateLocale("zh-CN"));
        assertThrows(InvalidResumeBuilderDraftException.class, () -> validator.validateLocale("fr"));
    }
}
