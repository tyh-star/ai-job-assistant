import type {
  ResumeBuilderCareerMaterials,
  ResumeBuilderDraftContent,
  ResumeBuilderTemplateType,
} from '../api/types'

/** Mirrors aijob.dto.builder.ResumeBuilderSectionIds on the backend -- keep in sync. */
export const SECTION_IDS = {
  SKILLS: 'SKILLS',
  EXPERIENCE: 'EXPERIENCE',
  EDUCATION: 'EDUCATION',
  PROJECTS: 'PROJECTS',
  VOLUNTEER: 'VOLUNTEER',
  CERTIFICATIONS: 'CERTIFICATIONS',
  LANGUAGES: 'LANGUAGES',
  LEADERSHIP: 'LEADERSHIP',
  PUBLICATIONS: 'PUBLICATIONS',
  HOBBIES: 'HOBBIES',
} as const

export const DEFAULT_SECTION_ORDER: string[] = [
  SECTION_IDS.SKILLS,
  SECTION_IDS.EXPERIENCE,
  SECTION_IDS.EDUCATION,
  SECTION_IDS.PROJECTS,
  SECTION_IDS.VOLUNTEER,
  SECTION_IDS.CERTIFICATIONS,
  SECTION_IDS.LANGUAGES,
  SECTION_IDS.LEADERSHIP,
  SECTION_IDS.PUBLICATIONS,
  SECTION_IDS.HOBBIES,
]

export const CUSTOM_PREFIX = 'CUSTOM:'

export function customSectionRef(id: string): string {
  return `${CUSTOM_PREFIX}${id}`
}

export function isCustomSectionRef(id: string): boolean {
  return id.startsWith(CUSTOM_PREFIX)
}

export function customSectionIdFromRef(ref: string): string {
  return ref.slice(CUSTOM_PREFIX.length)
}

export const TEMPLATE_TYPES: ResumeBuilderTemplateType[] = [
  'GENERIC_ATS',
  'STUDENT',
  'INTERNSHIP',
  'EXPERIENCED',
  'CAREER_CHANGE',
  'TECHNICAL',
]

/** Section-title i18n key for each fixed section id, under `resumeBuilder.sections.*`. */
export const SECTION_TITLE_KEY: Record<string, string> = {
  [SECTION_IDS.SKILLS]: 'resumeBuilder.sections.skills',
  [SECTION_IDS.EXPERIENCE]: 'resumeBuilder.sections.experience',
  [SECTION_IDS.EDUCATION]: 'resumeBuilder.sections.education',
  [SECTION_IDS.PROJECTS]: 'resumeBuilder.sections.projects',
  [SECTION_IDS.VOLUNTEER]: 'resumeBuilder.sections.volunteer',
  [SECTION_IDS.CERTIFICATIONS]: 'resumeBuilder.sections.certifications',
  [SECTION_IDS.LANGUAGES]: 'resumeBuilder.sections.languages',
  [SECTION_IDS.LEADERSHIP]: 'resumeBuilder.sections.leadership',
  [SECTION_IDS.PUBLICATIONS]: 'resumeBuilder.sections.publications',
  [SECTION_IDS.HOBBIES]: 'resumeBuilder.sections.hobbies',
}

function blankCareerMaterials(): ResumeBuilderCareerMaterials {
  return {
    selfIntroFacts: [],
    aiUsageScenarios: [],
    aiStrengths: [],
    aiRisks: [],
    aiVerificationApproach: [],
    oneYearGoal: '',
    threeYearGoal: '',
    skillsToImprove: [],
  }
}

/** A completely blank content object -- used only as a client-side fallback before the
 * server's own blank draft has loaded. Never pre-filled with any real person's data. */
export function blankDraftContent(): ResumeBuilderDraftContent {
  const sectionEnabled: Record<string, boolean> = {}
  DEFAULT_SECTION_ORDER.forEach((id) => {
    sectionEnabled[id] = true
  })
  return {
    schemaVersion: 1,
    basicInfo: {
      fullName: '',
      city: '',
      country: '',
      phone: '',
      email: '',
      linkedin: '',
      github: '',
      website: '',
      legallyEligibleToWork: null,
    },
    jobTarget: {
      targetTitle: '',
      jobLevel: '',
      targetIndustry: '',
      workMode: '',
      targetCity: '',
      careerSummary: '',
    },
    education: [],
    skillCategories: [],
    experience: [],
    projects: [],
    volunteer: [],
    certifications: [],
    languages: [],
    leadership: [],
    publications: [],
    hobbies: [],
    customSections: [],
    sectionOrder: [...DEFAULT_SECTION_ORDER],
    sectionEnabled,
    careerMaterials: blankCareerMaterials(),
  }
}
