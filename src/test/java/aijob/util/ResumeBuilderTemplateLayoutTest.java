package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.SkillCategory;
import aijob.dto.builder.ResumeBuilderSectionIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeBuilderTemplateLayoutTest {

    @Test
    void switchingTemplateReordersButDoesNotDeleteFilledSkills() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        SkillCategory skills = new SkillCategory();
        skills.setName("Office");
        skills.setSkills(List.of("Calendar management", "Customer service"));
        content.setSkillCategories(List.of(skills));
        content.normalize();

        List<String> originalOrder = List.copyOf(content.getSectionOrder());
        List<String> reordered = ResumeBuilderTemplateLayout.reorderForTemplate(originalOrder, "STUDENT");

        assertEquals(ResumeBuilderSectionIds.EDUCATION, reordered.get(0));
        assertTrue(reordered.contains(ResumeBuilderSectionIds.SKILLS));
        assertEquals(1, content.getSkillCategories().size());
        assertEquals("Calendar management", content.getSkillCategories().get(0).getSkills().get(0));
        assertEquals(originalOrder.size(), reordered.size());
    }

    @Test
    void newDraftReordersForTemplateButKeepsEverySectionShown() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();

        ResumeBuilderTemplateLayout.applyForNewDraft(content, "GENERIC_ATS");

        List<String> suggested = ResumeBuilderTemplateLayout.suggestedSectionOrder("GENERIC_ATS");
        assertEquals(suggested, content.getSectionOrder().subList(0, suggested.size()));
        assertFalse(suggested.contains(ResumeBuilderSectionIds.PROJECTS));
        assertEquals(Boolean.TRUE, content.getSectionEnabled().get(ResumeBuilderSectionIds.PROJECTS));
        assertTrue(content.getSectionOrder().stream()
                .allMatch(id -> Boolean.TRUE.equals(content.getSectionEnabled().get(id))));
    }

    @Test
    void newDraftLayoutDoesNotReEnableASectionAlreadyTurnedOff() {
        ResumeBuilderDraftContent content = ResumeBuilderDraftContent.blank();
        content.normalize();
        content.getSectionEnabled().put(ResumeBuilderSectionIds.SKILLS, Boolean.FALSE);

        ResumeBuilderTemplateLayout.applyForNewDraft(content, "TECHNICAL");

        assertEquals(ResumeBuilderSectionIds.SKILLS, content.getSectionOrder().get(0));
        assertEquals(Boolean.FALSE, content.getSectionEnabled().get(ResumeBuilderSectionIds.SKILLS));
        assertEquals(Boolean.TRUE, content.getSectionEnabled().get(ResumeBuilderSectionIds.PROJECTS));
    }

    @Test
    void genericAtsDoesNotSuggestGithub() {
        assertEquals(List.of(ResumeBuilderProfessionalLinks.LINKEDIN),
                ResumeBuilderTemplateLayout.suggestedLinkTypes("GENERIC_ATS"));
    }

    @Test
    void technicalSuggestsLinkedInGithubAndPortfolio() {
        assertEquals(List.of(
                        ResumeBuilderProfessionalLinks.LINKEDIN,
                        ResumeBuilderProfessionalLinks.GITHUB,
                        ResumeBuilderProfessionalLinks.PORTFOLIO),
                ResumeBuilderTemplateLayout.suggestedLinkTypes("TECHNICAL"));
    }
}
