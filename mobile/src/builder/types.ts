/**
 * Resume-builder DTOs, copied field for field from the backend
 * (aijob.dto.builder.ResumeBuilderDraftContent and the create/update/generate requests).
 * The server normalizes every draft before returning it, so these are the full shapes.
 */

export const TEMPLATE_TYPES = ['GENERIC_ATS', 'STUDENT', 'INTERNSHIP', 'EXPERIENCED', 'CAREER_CHANGE', 'TECHNICAL'] as const
export type TemplateType = (typeof TEMPLATE_TYPES)[number]

export const RESUME_LOCALES = ['zh-CN', 'en'] as const
export type ResumeLocale = (typeof RESUME_LOCALES)[number]

export const PROFESSIONAL_LINK_TYPES = ['LINKEDIN', 'PORTFOLIO', 'GITHUB', 'PERSONAL_WEBSITE', 'BEHANCE', 'OTHER'] as const
export type ProfessionalLinkType = (typeof PROFESSIONAL_LINK_TYPES)[number]

export type ProfessionalLink = { type: string; label: string; url: string }

export type BasicInfo = {
  fullName: string
  city: string
  country: string
  phone: string
  email: string
  linkedin: string
  github: string
  website: string
  professionalLinks: ProfessionalLink[]
  /** null means "not stated"; the form only ever sets true or null. */
  legallyEligibleToWork: boolean | null
}

export type JobTarget = {
  targetTitle: string
  jobLevel: string
  targetIndustry: string
  workMode: string
  targetCity: string
  careerSummary: string
}

export type EducationEntry = {
  school: string
  degree: string
  major: string
  startDate: string
  endDate: string
  gpa: string
  honors: string
  courses: string[]
}

export type SkillCategory = { id: string; name: string; skills: string[] }

export type WorkExperienceEntry = {
  company: string
  title: string
  location: string
  startDate: string
  endDate: string
  current: boolean
  responsibilities: string[]
  toolsUsed: string[]
  outcomes: string[]
}

export type ProjectEntry = {
  name: string
  purpose: string
  startDate: string
  endDate: string
  actualDuration: string
  techStack: string[]
  role: string
  teamSize: number | null
  features: string[]
  challenges: string[]
  solutions: string[]
  userCount: number | null
  outcomes: string[]
  githubUrl: string
  demoUrl: string
}

export type VolunteerEntry = {
  organization: string
  role: string
  location: string
  startDate: string
  endDate: string
  serviceHours: number | null
  workContent: string[]
  impact: string[]
}

export type Certification = {
  name: string
  issuer: string
  issueDate: string
  expiryDate: string
  credentialId: string
  credentialUrl: string
}

export type LanguageEntry = { language: string; proficiency: string }

export type LeadershipEntry = {
  organization: string
  role: string
  startDate: string
  endDate: string
  description: string[]
}

export type Publication = { title: string; venue: string; date: string; url: string; authors: string }

export type CustomSection = { id: string; title: string; items: string[] }

/** Edited on the website's career-materials tab; the app keeps it untouched. */
export type CareerMaterials = {
  selfIntroFacts: string[]
  aiUsageScenarios: string[]
  aiStrengths: string[]
  aiRisks: string[]
  aiVerificationApproach: string[]
  oneYearGoal: string
  threeYearGoal: string
  skillsToImprove: string[]
}

export type DraftContent = {
  schemaVersion: number
  basicInfo: BasicInfo
  jobTarget: JobTarget
  education: EducationEntry[]
  skillCategories: SkillCategory[]
  experience: WorkExperienceEntry[]
  projects: ProjectEntry[]
  volunteer: VolunteerEntry[]
  certifications: Certification[]
  languages: LanguageEntry[]
  leadership: LeadershipEntry[]
  publications: Publication[]
  hobbies: string[]
  customSections: CustomSection[]
  sectionOrder: string[]
  sectionEnabled: Record<string, boolean>
  careerMaterials: CareerMaterials
}

/** The repeatable sections whose entries share one editor. */
export type EntrySectionKey =
  | 'education'
  | 'experience'
  | 'projects'
  | 'volunteer'
  | 'certifications'
  | 'languages'
  | 'leadership'
  | 'publications'
  | 'skillCategories'
  | 'customSections'

export type CreateDraftRequest = { title: string; templateType: TemplateType; locale: ResumeLocale }

export type UpdateDraftRequest = {
  title: string
  templateType: string
  targetRole: string | null
  locale: string
  content: DraftContent
  version: number
}

export type GenerateDraftRequest = { idempotencyKey: string }

export type BuilderDraftResponse = {
  id: number
  title: string
  templateType: string
  targetRole: string | null
  locale: string
  schemaVersion: number
  version: number
  content: DraftContent
  createdAt: string
  updatedAt: string
}
