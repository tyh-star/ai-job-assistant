package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
import aijob.dto.builder.ResumeBuilderDraftContent.ProfessionalLink;
import aijob.dto.builder.ResumeBuilderSectionIds;
import aijob.enums.ResumeBuilderTemplateType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggested section order and suggested professional-link types per template.
 * Suggestions never delete user data: switching templates only reorders existing
 * section ids; filled entries stay. New drafts may start with non-suggested
 * sections hidden (still present, still enableable).
 */
public final class ResumeBuilderTemplateLayout {

    private ResumeBuilderTemplateLayout() {
    }

    public static List<String> suggestedSectionOrder(String templateType) {
        ResumeBuilderTemplateType type = parse(templateType);
        return switch (type) {
            case STUDENT -> List.of(
                    ResumeBuilderSectionIds.EDUCATION,
                    ResumeBuilderSectionIds.PROJECTS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.VOLUNTEER,
                    ResumeBuilderSectionIds.LEADERSHIP,
                    ResumeBuilderSectionIds.SKILLS);
            case INTERNSHIP -> List.of(
                    ResumeBuilderSectionIds.EDUCATION,
                    ResumeBuilderSectionIds.SKILLS,
                    ResumeBuilderSectionIds.PROJECTS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.VOLUNTEER);
            case EXPERIENCED -> List.of(
                    ResumeBuilderSectionIds.SKILLS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.CERTIFICATIONS,
                    ResumeBuilderSectionIds.EDUCATION);
            case CAREER_CHANGE -> List.of(
                    ResumeBuilderSectionIds.SKILLS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.PROJECTS,
                    ResumeBuilderSectionIds.CERTIFICATIONS,
                    ResumeBuilderSectionIds.EDUCATION);
            case TECHNICAL -> List.of(
                    ResumeBuilderSectionIds.SKILLS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.PROJECTS,
                    ResumeBuilderSectionIds.EDUCATION,
                    ResumeBuilderSectionIds.CERTIFICATIONS);
            case GENERIC_ATS -> List.of(
                    ResumeBuilderSectionIds.SKILLS,
                    ResumeBuilderSectionIds.EXPERIENCE,
                    ResumeBuilderSectionIds.EDUCATION,
                    ResumeBuilderSectionIds.CERTIFICATIONS,
                    ResumeBuilderSectionIds.LANGUAGES);
        };
    }

    public static List<String> suggestedLinkTypes(String templateType) {
        if (parse(templateType) == ResumeBuilderTemplateType.TECHNICAL) {
            return List.of(
                    ResumeBuilderProfessionalLinks.LINKEDIN,
                    ResumeBuilderProfessionalLinks.GITHUB,
                    ResumeBuilderProfessionalLinks.PORTFOLIO);
        }
        return List.of(ResumeBuilderProfessionalLinks.LINKEDIN);
    }

    /** Reorder only: suggested ids first, then any remaining ids in their previous relative order. */
    public static List<String> reorderForTemplate(List<String> currentOrder, String templateType) {
        List<String> suggested = suggestedSectionOrder(templateType);
        List<String> source = currentOrder == null || currentOrder.isEmpty()
                ? new ArrayList<>(ResumeBuilderSectionIds.DEFAULT_ORDER)
                : new ArrayList<>(currentOrder);
        List<String> next = new ArrayList<>();
        for (String id : suggested) {
            if (!next.contains(id)) {
                next.add(id);
            }
        }
        for (String id : source) {
            if (!next.contains(id)) {
                next.add(id);
            }
        }
        for (String id : ResumeBuilderSectionIds.DEFAULT_ORDER) {
            if (!next.contains(id)) {
                next.add(id);
            }
        }
        return next;
    }

    public static void applyForNewDraft(ResumeBuilderDraftContent content, String templateType) {
        if (content == null) {
            return;
        }
        content.normalize();
        List<String> order = reorderForTemplate(content.getSectionOrder(), templateType);
        content.setSectionOrder(order);
        Map<String, Boolean> enabled = content.getSectionEnabled() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(content.getSectionEnabled());
        List<String> suggested = suggestedSectionOrder(templateType);
        for (String id : order) {
            enabled.put(id, suggested.contains(id));
        }
        content.setSectionEnabled(enabled);

        BasicInfo info = content.getBasicInfo();
        List<ProfessionalLink> links = new ArrayList<>();
        for (String type : suggestedLinkTypes(templateType)) {
            links.add(ResumeBuilderProfessionalLinks.empty(type));
        }
        info.setProfessionalLinks(links);
        ResumeBuilderProfessionalLinks.migrateAndSync(info);
    }

    private static ResumeBuilderTemplateType parse(String templateType) {
        if (templateType == null || templateType.isBlank()) {
            return ResumeBuilderTemplateType.GENERIC_ATS;
        }
        try {
            return ResumeBuilderTemplateType.valueOf(templateType);
        } catch (IllegalArgumentException e) {
            return ResumeBuilderTemplateType.GENERIC_ATS;
        }
    }
}
