import type { ResumeBuilderDraftContent, ResumeBuilderTemplateType } from '../api/types'
import { DEFAULT_SECTION_ORDER, SECTION_IDS, SECTION_TITLE_KEY } from './constants'
import { emptyProfessionalLink, migrateAndSyncProfessionalLinks } from './professionalLinks'

export function suggestedSectionOrder(templateType: string): string[] {
  switch (templateType) {
    case 'STUDENT':
      return [
        SECTION_IDS.EDUCATION,
        SECTION_IDS.PROJECTS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.VOLUNTEER,
        SECTION_IDS.LEADERSHIP,
        SECTION_IDS.SKILLS,
      ]
    case 'INTERNSHIP':
      return [
        SECTION_IDS.EDUCATION,
        SECTION_IDS.SKILLS,
        SECTION_IDS.PROJECTS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.VOLUNTEER,
      ]
    case 'EXPERIENCED':
      return [
        SECTION_IDS.SKILLS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.CERTIFICATIONS,
        SECTION_IDS.EDUCATION,
      ]
    case 'CAREER_CHANGE':
      return [
        SECTION_IDS.SKILLS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.PROJECTS,
        SECTION_IDS.CERTIFICATIONS,
        SECTION_IDS.EDUCATION,
      ]
    case 'TECHNICAL':
      return [
        SECTION_IDS.SKILLS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.PROJECTS,
        SECTION_IDS.EDUCATION,
        SECTION_IDS.CERTIFICATIONS,
      ]
    default:
      return [
        SECTION_IDS.SKILLS,
        SECTION_IDS.EXPERIENCE,
        SECTION_IDS.EDUCATION,
        SECTION_IDS.CERTIFICATIONS,
        SECTION_IDS.LANGUAGES,
      ]
  }
}

export function suggestedLinkTypes(templateType: string): string[] {
  if (templateType === 'TECHNICAL') {
    return ['LINKEDIN', 'GITHUB', 'PORTFOLIO']
  }
  return ['LINKEDIN']
}

/** Reorder only: suggested ids first, then remaining ids in their previous relative order. */
export function reorderForTemplate(currentOrder: string[] | undefined, templateType: string): string[] {
  const suggested = suggestedSectionOrder(templateType)
  const source = currentOrder && currentOrder.length > 0 ? [...currentOrder] : [...DEFAULT_SECTION_ORDER]
  const next: string[] = []
  for (const id of suggested) {
    if (!next.includes(id)) {
      next.push(id)
    }
  }
  for (const id of source) {
    if (!next.includes(id)) {
      next.push(id)
    }
  }
  for (const id of DEFAULT_SECTION_ORDER) {
    if (!next.includes(id)) {
      next.push(id)
    }
  }
  return next
}

export function applyTemplateLayoutForNewDraft(
  content: ResumeBuilderDraftContent,
  templateType: ResumeBuilderTemplateType,
): ResumeBuilderDraftContent {
  const order = reorderForTemplate(content.sectionOrder, templateType)
  // Every section stays shown by default: blank sections are omitted from the resume
  // anyway, and hiding one would silently drop anything the user later fills in. A
  // section the user already turned off stays off.
  const sectionEnabled: Record<string, boolean> = { ...content.sectionEnabled }
  for (const id of order) {
    sectionEnabled[id] = content.sectionEnabled?.[id] ?? true
  }
  const professionalLinks = suggestedLinkTypes(templateType).map((type) => emptyProfessionalLink(type))
  return {
    ...content,
    sectionOrder: order,
    sectionEnabled,
    basicInfo: migrateAndSyncProfessionalLinks({
      ...content.basicInfo,
      professionalLinks,
    }),
  }
}

export function sectionTitleI18nKey(sectionId: string, templateType: string): string {
  if (sectionId === SECTION_IDS.SKILLS) {
    if (templateType === 'TECHNICAL') {
      return 'resumeBuilder.sections.skillsTechnical'
    }
    if (templateType === 'EXPERIENCED') {
      return 'resumeBuilder.sections.skillsExperienced'
    }
    if (templateType === 'CAREER_CHANGE') {
      return 'resumeBuilder.sections.skillsCareerChange'
    }
  }
  if (sectionId === SECTION_IDS.EXPERIENCE) {
    if (templateType === 'STUDENT' || templateType === 'INTERNSHIP') {
      return 'resumeBuilder.sections.experienceInternship'
    }
    if (templateType === 'CAREER_CHANGE') {
      return 'resumeBuilder.sections.experienceCareerChange'
    }
  }
  if (sectionId === SECTION_IDS.CERTIFICATIONS) {
    if (templateType === 'TECHNICAL') {
      return 'resumeBuilder.sections.certificationsTechnical'
    }
    if (templateType === 'CAREER_CHANGE') {
      return 'resumeBuilder.sections.certificationsCareerChange'
    }
  }
  return SECTION_TITLE_KEY[sectionId] ?? sectionId
}
