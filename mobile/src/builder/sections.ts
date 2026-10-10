import type { EntrySectionKey } from './types'

export type FieldKind = 'text' | 'multiline' | 'list' | 'count' | 'date' | 'url' | 'boolean' | 'email' | 'phone'

/** `label` is an i18n key. */
export type FieldDef = { key: string; label: string; kind: FieldKind }

export type EntrySection = {
  key: EntrySectionKey
  /** The id used in content.sectionEnabled; custom sections carry their own. */
  sectionId: string | null
  title: string
  addLabel: string
  fields: FieldDef[]
  /** Fields joined into the entry heading, first non-empty ones win. */
  heading: string[]
}

const f = (key: string, kind: FieldKind, label = key): FieldDef => ({ key, kind, label: `builder.fields.${label}` })

/** Field lists follow the website's sectionSchemas.ts, which follow the backend DTO. */
export const ENTRY_SECTIONS: EntrySection[] = [
  {
    key: 'skillCategories',
    sectionId: 'SKILLS',
    title: 'builder.sections.skills',
    addLabel: 'builder.addCategory',
    fields: [f('name', 'text', 'categoryName'), f('skills', 'list')],
    heading: ['name'],
  },
  {
    key: 'experience',
    sectionId: 'EXPERIENCE',
    title: 'builder.sections.experience',
    addLabel: 'builder.addEntry',
    fields: [
      f('company', 'text'),
      f('title', 'text', 'jobTitle'),
      f('location', 'text'),
      f('startDate', 'date'),
      f('current', 'boolean'),
      f('endDate', 'date'),
      f('responsibilities', 'list'),
      f('toolsUsed', 'list'),
      f('outcomes', 'list'),
    ],
    heading: ['title', 'company'],
  },
  {
    key: 'education',
    sectionId: 'EDUCATION',
    title: 'builder.sections.education',
    addLabel: 'builder.addEntry',
    fields: [
      f('school', 'text'),
      f('degree', 'text'),
      f('major', 'text'),
      f('startDate', 'date'),
      f('endDate', 'date'),
      f('gpa', 'text'),
      f('honors', 'text'),
      f('courses', 'list'),
    ],
    heading: ['degree', 'school'],
  },
  {
    key: 'projects',
    sectionId: 'PROJECTS',
    title: 'builder.sections.projects',
    addLabel: 'builder.addEntry',
    fields: [
      f('name', 'text', 'projectName'),
      f('purpose', 'multiline'),
      f('startDate', 'date'),
      f('endDate', 'date'),
      f('actualDuration', 'text'),
      f('role', 'text'),
      f('teamSize', 'count'),
      f('userCount', 'count'),
      f('techStack', 'list'),
      f('features', 'list'),
      f('challenges', 'list'),
      f('solutions', 'list'),
      f('outcomes', 'list'),
      f('githubUrl', 'url'),
      f('demoUrl', 'url'),
    ],
    heading: ['name'],
  },
  {
    key: 'volunteer',
    sectionId: 'VOLUNTEER',
    title: 'builder.sections.volunteer',
    addLabel: 'builder.addEntry',
    fields: [
      f('organization', 'text'),
      f('role', 'text'),
      f('location', 'text'),
      f('startDate', 'date'),
      f('endDate', 'date'),
      f('serviceHours', 'count'),
      f('workContent', 'list'),
      f('impact', 'list'),
    ],
    heading: ['role', 'organization'],
  },
  {
    key: 'certifications',
    sectionId: 'CERTIFICATIONS',
    title: 'builder.sections.certifications',
    addLabel: 'builder.addEntry',
    fields: [
      f('name', 'text', 'certificateName'),
      f('issuer', 'text'),
      f('issueDate', 'date'),
      f('expiryDate', 'date'),
      f('credentialId', 'text'),
      f('credentialUrl', 'url'),
    ],
    heading: ['name'],
  },
  {
    key: 'languages',
    sectionId: 'LANGUAGES',
    title: 'builder.sections.languages',
    addLabel: 'builder.addEntry',
    fields: [f('language', 'text'), f('proficiency', 'text')],
    heading: ['language'],
  },
  {
    key: 'leadership',
    sectionId: 'LEADERSHIP',
    title: 'builder.sections.leadership',
    addLabel: 'builder.addEntry',
    fields: [f('organization', 'text'), f('role', 'text'), f('startDate', 'date'), f('endDate', 'date'), f('description', 'list')],
    heading: ['role', 'organization'],
  },
  {
    key: 'publications',
    sectionId: 'PUBLICATIONS',
    title: 'builder.sections.publications',
    addLabel: 'builder.addEntry',
    fields: [
      f('title', 'text', 'publicationTitle'),
      f('venue', 'text'),
      f('date', 'date'),
      f('authors', 'text'),
      f('url', 'url'),
    ],
    heading: ['title'],
  },
]

export const CUSTOM_SECTION: EntrySection = {
  key: 'customSections',
  sectionId: null,
  title: 'builder.sections.customSections',
  addLabel: 'builder.addCustomSection',
  fields: [f('title', 'text', 'customSectionTitle'), f('items', 'list', 'customSectionItems')],
  heading: ['title'],
}
