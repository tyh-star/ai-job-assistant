import type { FieldDef } from './RepeatableSection'
import { SECTION_IDS } from './constants'

type TFunc = (key: string) => string

export interface SectionSchema {
  fields: FieldDef[]
  emptyEntry: () => Record<string, unknown>
  headingFor: (entry: Record<string, unknown>) => string
}

function str(entry: Record<string, unknown>, key: string): string {
  const value = entry[key]
  return typeof value === 'string' ? value : ''
}

/** One schema per repeatable-entry section id (fixed sections only -- skill
 * categories and custom sections are built separately since their shape differs).
 * `t` is the i18next translate function, so field labels/placeholders come from
 * `resumeBuilder.fields.*`. */
export function buildSectionSchemas(t: TFunc): Record<string, SectionSchema> {
  return {
    [SECTION_IDS.EDUCATION]: {
      fields: [
        { key: 'school', label: t('resumeBuilder.fields.school'), kind: 'text' },
        { key: 'degree', label: t('resumeBuilder.fields.degree'), kind: 'text' },
        { key: 'major', label: t('resumeBuilder.fields.major'), kind: 'text' },
        { key: 'startDate', label: t('resumeBuilder.fields.startDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'endDate', label: t('resumeBuilder.fields.endDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'gpa', label: t('resumeBuilder.fields.gpa'), kind: 'text' },
        { key: 'honors', label: t('resumeBuilder.fields.honors'), kind: 'text' },
        { key: 'courses', label: t('resumeBuilder.fields.courses'), kind: 'list' },
      ],
      emptyEntry: () => ({
        school: '', degree: '', major: '', startDate: '', endDate: '', gpa: '', honors: '', courses: [],
      }),
      headingFor: (e) => [str(e, 'degree'), str(e, 'school')].filter(Boolean).join(' — '),
    },
    [SECTION_IDS.EXPERIENCE]: {
      fields: [
        { key: 'company', label: t('resumeBuilder.fields.company'), kind: 'text' },
        { key: 'title', label: t('resumeBuilder.fields.jobTitle'), kind: 'text' },
        { key: 'location', label: t('resumeBuilder.fields.location'), kind: 'text' },
        { key: 'startDate', label: t('resumeBuilder.fields.startDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'endDate', label: t('resumeBuilder.fields.endDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'current', label: t('resumeBuilder.fields.current'), kind: 'boolean' },
        { key: 'responsibilities', label: t('resumeBuilder.fields.responsibilities'), kind: 'list' },
        { key: 'toolsUsed', label: t('resumeBuilder.fields.toolsUsed'), kind: 'list' },
        { key: 'outcomes', label: t('resumeBuilder.fields.outcomes'), kind: 'list' },
      ],
      emptyEntry: () => ({
        company: '', title: '', location: '', startDate: '', endDate: '', current: false,
        responsibilities: [], toolsUsed: [], outcomes: [],
      }),
      headingFor: (e) => [str(e, 'title'), str(e, 'company')].filter(Boolean).join(' — '),
    },
    [SECTION_IDS.PROJECTS]: {
      fields: [
        { key: 'name', label: t('resumeBuilder.fields.projectName'), kind: 'text' },
        { key: 'purpose', label: t('resumeBuilder.fields.purpose'), kind: 'textarea' },
        { key: 'startDate', label: t('resumeBuilder.fields.startDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'endDate', label: t('resumeBuilder.fields.endDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'actualDuration', label: t('resumeBuilder.fields.actualDuration'), kind: 'text' },
        { key: 'role', label: t('resumeBuilder.fields.role'), kind: 'text' },
        { key: 'teamSize', label: t('resumeBuilder.fields.teamSize'), kind: 'number' },
        { key: 'userCount', label: t('resumeBuilder.fields.userCount'), kind: 'number' },
        { key: 'techStack', label: t('resumeBuilder.fields.techStack'), kind: 'list' },
        { key: 'features', label: t('resumeBuilder.fields.features'), kind: 'list' },
        { key: 'challenges', label: t('resumeBuilder.fields.challenges'), kind: 'list' },
        { key: 'solutions', label: t('resumeBuilder.fields.solutions'), kind: 'list' },
        { key: 'outcomes', label: t('resumeBuilder.fields.outcomes'), kind: 'list' },
        { key: 'githubUrl', label: t('resumeBuilder.fields.githubUrl'), kind: 'text', placeholder: 'https://github.com/...' },
        { key: 'demoUrl', label: t('resumeBuilder.fields.demoUrl'), kind: 'text', placeholder: 'https://...' },
      ],
      emptyEntry: () => ({
        name: '', purpose: '', startDate: '', endDate: '', actualDuration: '', techStack: [], role: '',
        teamSize: null, features: [], challenges: [], solutions: [], userCount: null, outcomes: [],
        githubUrl: '', demoUrl: '',
      }),
      headingFor: (e) => str(e, 'name'),
    },
    [SECTION_IDS.VOLUNTEER]: {
      fields: [
        { key: 'organization', label: t('resumeBuilder.fields.organization'), kind: 'text' },
        { key: 'role', label: t('resumeBuilder.fields.role'), kind: 'text' },
        { key: 'location', label: t('resumeBuilder.fields.location'), kind: 'text' },
        { key: 'startDate', label: t('resumeBuilder.fields.startDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'endDate', label: t('resumeBuilder.fields.endDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'serviceHours', label: t('resumeBuilder.fields.serviceHours'), kind: 'number' },
        { key: 'workContent', label: t('resumeBuilder.fields.workContent'), kind: 'list' },
        { key: 'impact', label: t('resumeBuilder.fields.impact'), kind: 'list' },
      ],
      emptyEntry: () => ({
        organization: '', role: '', location: '', startDate: '', endDate: '', serviceHours: null,
        workContent: [], impact: [],
      }),
      headingFor: (e) => [str(e, 'role'), str(e, 'organization')].filter(Boolean).join(' — '),
    },
    [SECTION_IDS.CERTIFICATIONS]: {
      fields: [
        { key: 'name', label: t('resumeBuilder.fields.certificateName'), kind: 'text' },
        { key: 'issuer', label: t('resumeBuilder.fields.issuer'), kind: 'text' },
        { key: 'issueDate', label: t('resumeBuilder.fields.issueDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'expiryDate', label: t('resumeBuilder.fields.expiryDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'credentialId', label: t('resumeBuilder.fields.credentialId'), kind: 'text' },
        { key: 'credentialUrl', label: t('resumeBuilder.fields.credentialUrl'), kind: 'text' },
      ],
      emptyEntry: () => ({
        name: '', issuer: '', issueDate: '', expiryDate: '', credentialId: '', credentialUrl: '',
      }),
      headingFor: (e) => str(e, 'name'),
    },
    [SECTION_IDS.LANGUAGES]: {
      fields: [
        { key: 'language', label: t('resumeBuilder.fields.language'), kind: 'text' },
        { key: 'proficiency', label: t('resumeBuilder.fields.proficiency'), kind: 'text' },
      ],
      emptyEntry: () => ({ language: '', proficiency: '' }),
      headingFor: (e) => str(e, 'language'),
    },
    [SECTION_IDS.LEADERSHIP]: {
      fields: [
        { key: 'organization', label: t('resumeBuilder.fields.organization'), kind: 'text' },
        { key: 'role', label: t('resumeBuilder.fields.role'), kind: 'text' },
        { key: 'startDate', label: t('resumeBuilder.fields.startDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'endDate', label: t('resumeBuilder.fields.endDate'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'description', label: t('resumeBuilder.fields.description'), kind: 'list' },
      ],
      emptyEntry: () => ({ organization: '', role: '', startDate: '', endDate: '', description: [] }),
      headingFor: (e) => [str(e, 'role'), str(e, 'organization')].filter(Boolean).join(' — '),
    },
    [SECTION_IDS.PUBLICATIONS]: {
      fields: [
        { key: 'title', label: t('resumeBuilder.fields.publicationTitle'), kind: 'text' },
        { key: 'venue', label: t('resumeBuilder.fields.venue'), kind: 'text' },
        { key: 'date', label: t('resumeBuilder.fields.date'), kind: 'text', placeholder: 'YYYY-MM' },
        { key: 'authors', label: t('resumeBuilder.fields.authors'), kind: 'text' },
        { key: 'url', label: t('resumeBuilder.fields.url'), kind: 'text' },
      ],
      emptyEntry: () => ({ title: '', venue: '', date: '', authors: '', url: '' }),
      headingFor: (e) => str(e, 'title'),
    },
  }
}

/** Skill categories -- a repeatable entry of {name, skills[]}, reusing the same
 * generic RepeatableSection component. */
export function skillCategorySchema(t: TFunc): SectionSchema {
  return {
    fields: [
      { key: 'name', label: t('resumeBuilder.fields.categoryName'), kind: 'text' },
      { key: 'skills', label: t('resumeBuilder.fields.skills'), kind: 'list' },
    ],
    emptyEntry: () => ({ id: crypto.randomUUID(), name: '', skills: [] }),
    headingFor: (e) => str(e, 'name'),
  }
}

/** Custom sections -- a repeatable entry of {title, items[]}. */
export function customSectionSchema(t: TFunc): SectionSchema {
  return {
    fields: [
      { key: 'title', label: t('resumeBuilder.fields.customSectionTitle'), kind: 'text' },
      { key: 'items', label: t('resumeBuilder.fields.customSectionItems'), kind: 'list' },
    ],
    emptyEntry: () => ({ id: crypto.randomUUID(), title: '', items: [] }),
    headingFor: (e) => str(e, 'title'),
  }
}
