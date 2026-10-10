import { tailorStore } from '../tailor/store'

/**
 * A PDF's recognized structure (aijob.dto.ResumeStructure). PUT /resumes/{id}/structure
 * stores exactly the object it receives -- there is no merge and no version -- so the
 * app always sends back the whole object it loaded, with only the edited fields changed.
 * Unknown fields (layoutProfile, fieldProvenance, source block ids, ...) ride along.
 */
type Extra = { [key: string]: unknown }

export type StructureContact = Extra & { fullName: string; email: string; phone: string; location: string; links: string[] }
export type StructureBullet = Extra & { text: string }
export type StructureSkill = Extra & { name: string }
export type StructureExperience = Extra & {
  company: string
  title: string
  startDate: string
  endDate: string
  location: string
  bullets: string[]
}
export type StructureEducation = Extra & { school: string; degree: string; dates: string; details: string }
export type StructureProject = Extra & { name: string; organizationOrType: string; dates: string; bullets: string[]; links: string[] }
export type StructureTextBlock = Extra & { id?: string; page?: number; text?: string }

export type ResumeStructure = Extra & {
  resumeId?: number
  reviewStatus?: string
  contact: StructureContact
  summaryBullets: StructureBullet[]
  skills: StructureSkill[]
  experience: StructureExperience[]
  education: StructureEducation[]
  projects: StructureProject[]
  unassigned: StructureTextBlock[]
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function record(value: unknown): Extra {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Extra) : {}
}

function records(value: unknown): Extra[] {
  return Array.isArray(value) ? value.filter((item): item is Extra => Boolean(item) && typeof item === 'object') : []
}

/** The same defaults the website's normalizeStructure and ResumeStructure.normalize() apply; nothing else changes. */
export function normalizeStructure(raw: unknown): ResumeStructure {
  const value = record(raw)
  const contact = record(value.contact)
  return {
    ...value,
    contact: {
      ...contact,
      fullName: str(contact.fullName),
      email: str(contact.email),
      phone: str(contact.phone),
      location: str(contact.location),
      links: strings(contact.links),
    },
    summaryBullets: records(value.summaryBullets).map((item) => ({ ...item, text: str(item.text) })),
    skills: records(value.skills).map((item) => ({ ...item, name: str(item.name) })),
    experience: records(value.experience).map((item) => ({
      ...item,
      company: str(item.company),
      title: str(item.title),
      startDate: str(item.startDate),
      endDate: str(item.endDate),
      location: str(item.location),
      bullets: strings(item.bullets),
    })),
    education: records(value.education).map((item) => ({
      ...item,
      school: str(item.school),
      degree: str(item.degree),
      dates: str(item.dates),
      details: str(item.details),
    })),
    projects: records(value.projects).map((item) => ({
      ...item,
      name: str(item.name),
      organizationOrType: str(item.organizationOrType),
      dates: str(item.dates),
      bullets: strings(item.bullets),
      links: strings(item.links),
    })),
    unassigned: records(value.unassigned),
  }
}

/** Website mergeBulletTexts: the n-th line keeps the n-th bullet's source data. */
export function withSummaryTexts(doc: ResumeStructure, texts: string[]): ResumeStructure {
  return { ...doc, summaryBullets: texts.map((text, index) => ({ ...(doc.summaryBullets[index] ?? {}), text })) }
}

/** Website mergeSkillNames. */
export function withSkillNames(doc: ResumeStructure, names: string[]): ResumeStructure {
  return { ...doc, skills: names.map((name, index) => ({ ...(doc.skills[index] ?? {}), name })) }
}

export function emptyExperience(): StructureExperience {
  return { company: '', title: '', startDate: '', endDate: '', location: '', bullets: [] }
}

export function emptyProject(): StructureProject {
  return { name: '', organizationOrType: '', dates: '', bullets: [], links: [] }
}

export function emptyEducation(): StructureEducation {
  return { school: '', degree: '', dates: '', details: '' }
}

const TEXT_FIELDS = ['company', 'title', 'startDate', 'endDate', 'location', 'name', 'organizationOrType', 'dates', 'school', 'degree', 'details']

export function entryHasContent(entry: Extra): boolean {
  return (
    TEXT_FIELDS.some((key) => str(entry[key]).trim().length > 0) ||
    strings(entry.bullets).some((line) => line.trim()) ||
    strings(entry.links).some((line) => line.trim())
  )
}

export function structureSignature(doc: ResumeStructure): string {
  return JSON.stringify(doc)
}

/** The website's only save rule. Returns an i18n key when the structure cannot be saved. */
export function validateStructure(doc: ResumeStructure): string | null {
  return doc.contact.fullName.trim() ? null : 'resumes.structure.fullNameRequired'
}

/**
 * validateCanonicalStructure also needs a skill or an experience/project bullet. Saving is
 * still allowed (the website allows it); the screen only warns that tailoring would fail.
 */
export function missingTailorContent(doc: ResumeStructure): boolean {
  const hasSkill = doc.skills.some((skill) => skill.name.trim())
  const hasBullet = [...doc.experience, ...doc.projects].some((entry) => entry.bullets.some((line) => line.trim()))
  return !hasSkill && !hasBullet
}

// ---------------------------------------------------------------------------
// "saved on this device" marker. The server keeps no structure version, so the app
// records when it saved a structure and treats proposals made before then as outdated.

export function structureSavedKey(userId: number, resumeId: number): string {
  return `aijob.structureSavedAt.${userId}.${resumeId}`
}

export async function loadStructureSavedAt(userId: number, resumeId: number): Promise<string | null> {
  const value = await tailorStore.get(structureSavedKey(userId, resumeId))
  return value && !Number.isNaN(Date.parse(value)) ? value : null
}

export async function saveStructureSavedAt(userId: number, resumeId: number, savedAt: string): Promise<void> {
  await tailorStore.set(structureSavedKey(userId, resumeId), savedAt)
}

/** True when a structure was saved after `since`; an unknown `since` counts as older. */
export function structureChangedSince(savedAt: string | null, since: string | null | undefined): boolean {
  const saved = savedAt ? Date.parse(savedAt) : Number.NaN
  if (Number.isNaN(saved)) {
    return false
  }
  const from = since ? Date.parse(since) : Number.NaN
  return Number.isNaN(from) || saved > from
}
