import { ApiError } from '../api/client'
import {
  PROFESSIONAL_LINK_TYPES,
  RESUME_LOCALES,
  TEMPLATE_TYPES,
  type BasicInfo,
  type BuilderDraftResponse,
  type CareerMaterials,
  type DraftContent,
  type EntrySectionKey,
  type ProfessionalLink,
  type ProfessionalLinkType,
  type ResumeLocale,
  type TemplateType,
  type UpdateDraftRequest,
} from './types'

/** Mirrors aijob.dto.builder.ResumeBuilderSectionIds. */
export const FIXED_SECTION_IDS = [
  'SKILLS',
  'EXPERIENCE',
  'EDUCATION',
  'PROJECTS',
  'VOLUNTEER',
  'CERTIFICATIONS',
  'LANGUAGES',
  'LEADERSHIP',
  'PUBLICATIONS',
  'HOBBIES',
] as const
export const CUSTOM_PREFIX = 'CUSTOM:'

/** Limits copied from aijob.util.ResumeBuilderDraftValidator. */
export const LIMITS = {
  short: 200,
  medium: 1000,
  long: 4000,
  url: 500,
  gpa: 20,
  title: 200,
  entries: {
    education: 30,
    skillCategories: 30,
    experience: 50,
    projects: 100,
    volunteer: 50,
    certifications: 50,
    languages: 30,
    leadership: 50,
    publications: 50,
    customSections: 20,
  } satisfies Record<EntrySectionKey, number>,
  skillsPerCategory: 50,
  hobbies: 50,
  itemsPerCustomSection: 100,
  linesPerEntry: 30,
  courses: 100,
  professionalLinks: 12,
  teamSize: 100_000,
  userCount: 2_000_000_000,
  serviceHours: 100_000,
} as const

const ONGOING_MARKERS = new Set(['present', 'current', 'ongoing', '至今', '现在', '进行中'])

// ---------------------------------------------------------------------------
// normalize: the same defaults ResumeBuilderDraftContent.normalize() applies

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function records(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    : []
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
}

/** Random RFC 4122 v4 id. Uses the platform CSPRNG when it exists. */
export function randomId(): string {
  const cryptoApi = (globalThis as { crypto?: { randomUUID?: () => string; getRandomValues?: (a: Uint8Array) => Uint8Array } })
    .crypto
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (cryptoApi?.getRandomValues) {
    cryptoApi.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** One key per generate action, resent unchanged when the same action is retried. */
export function newIdempotencyKey(): string {
  return randomId()
}

export function canonicalLinkType(type: unknown): ProfessionalLinkType {
  const upper = str(type).trim().toUpperCase().replace(/[\s-]/g, '_')
  if (!upper) {
    return 'OTHER'
  }
  if (upper === 'WEBSITE' || upper === 'PERSONALWEBSITE') {
    return 'PERSONAL_WEBSITE'
  }
  return (PROFESSIONAL_LINK_TYPES as readonly string[]).includes(upper) ? (upper as ProfessionalLinkType) : 'OTHER'
}

function firstUrlOfType(links: ProfessionalLink[], type: ProfessionalLinkType): string {
  return links.find((link) => link.type === type && link.url.trim())?.url ?? ''
}

/** ResumeBuilderProfessionalLinks.migrateAndSync: old scalar links become list entries. */
export function syncProfessionalLinks(info: BasicInfo, rawLinks: unknown = info.professionalLinks): BasicInfo {
  let source: unknown[]
  if (rawLinks == null) {
    source = [
      { type: 'LINKEDIN', url: info.linkedin },
      { type: 'GITHUB', url: info.github },
      { type: 'PERSONAL_WEBSITE', url: info.website },
    ].filter((link) => link.url.trim())
  } else {
    source = Array.isArray(rawLinks) ? rawLinks : []
  }
  const links = records(source).map((link) => ({
    type: canonicalLinkType(link.type),
    label: str(link.label).trim(),
    url: str(link.url).trim(),
  }))
  return {
    ...info,
    professionalLinks: links,
    linkedin: firstUrlOfType(links, 'LINKEDIN'),
    github: firstUrlOfType(links, 'GITHUB'),
    website: firstUrlOfType(links, 'PERSONAL_WEBSITE'),
  }
}

function normalizeCareerMaterials(value: unknown): CareerMaterials {
  const raw = record(value)
  return {
    ...raw,
    selfIntroFacts: strings(raw.selfIntroFacts),
    aiUsageScenarios: strings(raw.aiUsageScenarios),
    aiStrengths: strings(raw.aiStrengths),
    aiRisks: strings(raw.aiRisks),
    aiVerificationApproach: strings(raw.aiVerificationApproach),
    oneYearGoal: str(raw.oneYearGoal),
    threeYearGoal: str(raw.threeYearGoal),
    skillsToImprove: strings(raw.skillsToImprove),
  }
}

export function normalizeContent(value: unknown): DraftContent {
  const raw = record(value)
  const info = record(raw.basicInfo)
  const target = record(raw.jobTarget)
  const basicInfo = syncProfessionalLinks(
    {
      fullName: str(info.fullName),
      city: str(info.city),
      country: str(info.country),
      phone: str(info.phone),
      email: str(info.email),
      linkedin: str(info.linkedin),
      github: str(info.github),
      website: str(info.website),
      professionalLinks: [],
      legallyEligibleToWork: typeof info.legallyEligibleToWork === 'boolean' ? info.legallyEligibleToWork : null,
    },
    info.professionalLinks,
  )

  const customSections = records(raw.customSections).map((section) => ({
    id: str(section.id).trim() || randomId(),
    title: str(section.title),
    items: strings(section.items),
  }))

  const sectionOrder = strings(raw.sectionOrder)
  const sectionEnabled: Record<string, boolean> = {}
  const rawEnabled = record(raw.sectionEnabled)
  for (const [key, enabled] of Object.entries(rawEnabled)) {
    if (typeof enabled === 'boolean') {
      sectionEnabled[key] = enabled
    }
  }
  const known = [...FIXED_SECTION_IDS, ...customSections.map((section) => `${CUSTOM_PREFIX}${section.id}`)]
  for (const id of known) {
    if (!sectionOrder.includes(id)) {
      sectionOrder.push(id)
    }
    if (!(id in sectionEnabled)) {
      sectionEnabled[id] = true
    }
  }

  return {
    schemaVersion: 1,
    basicInfo,
    jobTarget: {
      targetTitle: str(target.targetTitle),
      jobLevel: str(target.jobLevel),
      targetIndustry: str(target.targetIndustry),
      workMode: str(target.workMode),
      targetCity: str(target.targetCity),
      careerSummary: str(target.careerSummary),
    },
    education: records(raw.education).map((e) => ({
      school: str(e.school),
      degree: str(e.degree),
      major: str(e.major),
      startDate: str(e.startDate),
      endDate: str(e.endDate),
      gpa: str(e.gpa),
      honors: str(e.honors),
      courses: strings(e.courses),
    })),
    skillCategories: records(raw.skillCategories).map((c) => ({
      id: str(c.id).trim() || randomId(),
      name: str(c.name),
      skills: strings(c.skills),
    })),
    experience: records(raw.experience).map((e) => ({
      company: str(e.company),
      title: str(e.title),
      location: str(e.location),
      startDate: str(e.startDate),
      endDate: str(e.endDate),
      current: e.current === true,
      responsibilities: strings(e.responsibilities),
      toolsUsed: strings(e.toolsUsed),
      outcomes: strings(e.outcomes),
    })),
    projects: records(raw.projects).map((p) => ({
      name: str(p.name),
      purpose: str(p.purpose),
      startDate: str(p.startDate),
      endDate: str(p.endDate),
      actualDuration: str(p.actualDuration),
      techStack: strings(p.techStack),
      role: str(p.role),
      teamSize: num(p.teamSize),
      features: strings(p.features),
      challenges: strings(p.challenges),
      solutions: strings(p.solutions),
      userCount: num(p.userCount),
      outcomes: strings(p.outcomes),
      githubUrl: str(p.githubUrl),
      demoUrl: str(p.demoUrl),
    })),
    volunteer: records(raw.volunteer).map((v) => ({
      organization: str(v.organization),
      role: str(v.role),
      location: str(v.location),
      startDate: str(v.startDate),
      endDate: str(v.endDate),
      serviceHours: num(v.serviceHours),
      workContent: strings(v.workContent),
      impact: strings(v.impact),
    })),
    certifications: records(raw.certifications).map((c) => ({
      name: str(c.name),
      issuer: str(c.issuer),
      issueDate: str(c.issueDate),
      expiryDate: str(c.expiryDate),
      credentialId: str(c.credentialId),
      credentialUrl: str(c.credentialUrl),
    })),
    languages: records(raw.languages).map((l) => ({ language: str(l.language), proficiency: str(l.proficiency) })),
    leadership: records(raw.leadership).map((l) => ({
      organization: str(l.organization),
      role: str(l.role),
      startDate: str(l.startDate),
      endDate: str(l.endDate),
      description: strings(l.description),
    })),
    publications: records(raw.publications).map((p) => ({
      title: str(p.title),
      venue: str(p.venue),
      date: str(p.date),
      url: str(p.url),
      authors: str(p.authors),
    })),
    hobbies: strings(raw.hobbies),
    customSections,
    sectionOrder,
    sectionEnabled,
    careerMaterials: normalizeCareerMaterials(raw.careerMaterials),
  }
}

/** Blank entries the form adds. Never pre-filled with anything. */
export function emptyEntry(section: EntrySectionKey): Record<string, unknown> {
  switch (section) {
    case 'education':
      return { school: '', degree: '', major: '', startDate: '', endDate: '', gpa: '', honors: '', courses: [] }
    case 'experience':
      return { company: '', title: '', location: '', startDate: '', endDate: '', current: false, responsibilities: [], toolsUsed: [], outcomes: [] }
    case 'projects':
      return {
        name: '', purpose: '', startDate: '', endDate: '', actualDuration: '', techStack: [], role: '', teamSize: null,
        features: [], challenges: [], solutions: [], userCount: null, outcomes: [], githubUrl: '', demoUrl: '',
      }
    case 'volunteer':
      return { organization: '', role: '', location: '', startDate: '', endDate: '', serviceHours: null, workContent: [], impact: [] }
    case 'certifications':
      return { name: '', issuer: '', issueDate: '', expiryDate: '', credentialId: '', credentialUrl: '' }
    case 'languages':
      return { language: '', proficiency: '' }
    case 'leadership':
      return { organization: '', role: '', startDate: '', endDate: '', description: [] }
    case 'publications':
      return { title: '', venue: '', date: '', url: '', authors: '' }
    case 'skillCategories':
      return { id: randomId(), name: '', skills: [] }
    case 'customSections':
      return { id: randomId(), title: '', items: [] }
  }
}

/** Adding a custom section also registers it in the order, as the website does. */
export function addCustomSection(content: DraftContent): DraftContent {
  const section = emptyEntry('customSections') as DraftContent['customSections'][number]
  const ref = `${CUSTOM_PREFIX}${section.id}`
  return {
    ...content,
    customSections: [...content.customSections, section],
    sectionOrder: [...content.sectionOrder, ref],
    sectionEnabled: { ...content.sectionEnabled, [ref]: true },
  }
}

export function removeCustomSection(content: DraftContent, index: number): DraftContent {
  const section = content.customSections[index]
  if (!section) {
    return content
  }
  const ref = `${CUSTOM_PREFIX}${section.id}`
  const { [ref]: _removed, ...sectionEnabled } = content.sectionEnabled
  return {
    ...content,
    customSections: content.customSections.filter((_, i) => i !== index),
    sectionOrder: content.sectionOrder.filter((id) => id !== ref),
    sectionEnabled,
  }
}

/** "One per line" fields: trimmed, blank lines dropped (GenericListField on the website). */
export function linesToList(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

/** Digits only; blank means "not entered". */
export function parseCount(text: string): number | null {
  const digits = text.replace(/[^0-9]/g, '')
  return digits ? Number(digits) : null
}

export function hasEntryContent(entry: Record<string, unknown>): boolean {
  return Object.entries(entry).some(([key, value]) => {
    if (key === 'id') {
      return false
    }
    if (typeof value === 'string') {
      return value.trim().length > 0
    }
    if (Array.isArray(value)) {
      return value.length > 0
    }
    return typeof value === 'number' || value === true
  })
}

// ---------------------------------------------------------------------------
// the editable form

export type DraftForm = {
  title: string
  templateType: string
  targetRole: string
  locale: string
  content: DraftContent
}

export function isTemplateType(value: string): value is TemplateType {
  return (TEMPLATE_TYPES as readonly string[]).includes(value)
}

export function isResumeLocale(value: string): value is ResumeLocale {
  return (RESUME_LOCALES as readonly string[]).includes(value)
}

/** The list's targetRole and jobTarget.targetTitle are one fact (see syncTargetRole on the server). */
export function formFromResponse(draft: BuilderDraftResponse): DraftForm {
  const content = normalizeContent(draft.content)
  const role = (draft.targetRole ?? '').trim() || content.jobTarget.targetTitle.trim()
  return {
    title: draft.title ?? '',
    templateType: draft.templateType,
    targetRole: role,
    locale: draft.locale,
    content: { ...content, jobTarget: { ...content.jobTarget, targetTitle: role } },
  }
}

export function setTargetTitle(form: DraftForm, value: string): DraftForm {
  return { ...form, targetRole: value, content: { ...form.content, jobTarget: { ...form.content.jobTarget, targetTitle: value } } }
}

/** A stable fingerprint for "has anything changed since the last successful save". */
export function formSignature(form: DraftForm): string {
  return JSON.stringify([form.title, form.templateType, form.targetRole, form.locale, form.content])
}

export function buildUpdateRequest(form: DraftForm, version: number, untitled: string): UpdateDraftRequest {
  const role = form.targetRole.trim()
  const content = {
    ...form.content,
    basicInfo: syncProfessionalLinks(form.content.basicInfo),
    jobTarget: { ...form.content.jobTarget, targetTitle: role },
  }
  return {
    title: form.title.trim() || untitled,
    templateType: form.templateType,
    targetRole: role || null,
    locale: form.locale,
    content,
    version,
  }
}

export type Readiness = { hasName: boolean; hasContact: boolean; hasTarget: boolean; ready: boolean }

/** The website's generate gate: a name, an email or phone, and a target job title. */
export function draftReadiness(form: DraftForm): Readiness {
  const info = form.content.basicInfo
  const hasName = Boolean(info.fullName.trim())
  const hasContact = Boolean(info.email.trim() || info.phone.trim())
  const hasTarget = Boolean(form.targetRole.trim())
  return { hasName, hasContact, hasTarget, ready: hasName && hasContact && hasTarget }
}

// ---------------------------------------------------------------------------
// validation mirroring ResumeBuilderDraftValidator, reported per field path

export type FieldProblem = { key: string; params?: Record<string, number> }
export type FieldProblems = Record<string, FieldProblem>

function hasIllegalCharacters(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code <= 8 || code === 11 || code === 12 || (code >= 14 && code <= 31) || code === 0xfffe || code === 0xffff) {
      return true
    }
  }
  return false
}

type Collector = { add: (path: string, problem: FieldProblem) => void }

function text(c: Collector, path: string, value: string, max: number) {
  if (value.length > max) {
    c.add(path, { key: 'builder.validation.tooLong', params: { max } })
  } else if (hasIllegalCharacters(value)) {
    c.add(path, { key: 'builder.validation.illegalCharacters' })
  }
}

function list(c: Collector, path: string, values: string[], maxItems: number, maxLength: number) {
  if (values.length > maxItems) {
    c.add(path, { key: 'builder.validation.tooManyLines', params: { max: maxItems } })
    return
  }
  const longest = values.find((value) => value.length > maxLength)
  if (longest !== undefined) {
    c.add(path, { key: 'builder.validation.lineTooLong', params: { max: maxLength } })
  } else if (values.some(hasIllegalCharacters)) {
    c.add(path, { key: 'builder.validation.illegalCharacters' })
  }
}

function url(c: Collector, path: string, value: string) {
  if (!value.trim()) {
    return
  }
  if (value.length > LIMITS.url) {
    c.add(path, { key: 'builder.validation.tooLong', params: { max: LIMITS.url } })
  } else if (hasIllegalCharacters(value)) {
    c.add(path, { key: 'builder.validation.illegalCharacters' })
  } else if (!/^https?:\/\//i.test(value.trim())) {
    c.add(path, { key: 'builder.validation.url' })
  }
}

function count(c: Collector, path: string, value: number | null, max: number) {
  if (value === null) {
    return
  }
  if (value < 0 || !Number.isInteger(value)) {
    c.add(path, { key: 'builder.validation.wholeNumber' })
  } else if (value > max) {
    c.add(path, { key: 'builder.validation.tooLarge', params: { max } })
  }
}

/** Returns the day number for a real date, null for blank or "present", and reports anything else. */
function date(c: Collector, path: string, value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed || ONGOING_MARKERS.has(trimmed.toLowerCase())) {
    return null
  }
  const match = /^(\d{4})-(\d{2})(?:-(\d{2}))?$/.exec(trimmed)
  if (!match) {
    c.add(path, { key: 'builder.validation.date' })
    return null
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = match[3] ? Number(match[3]) : 1
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) {
    c.add(path, { key: 'builder.validation.date' })
    return null
  }
  return parsed.getTime()
}

function range(c: Collector, prefix: string, start: string, end: string, ongoing = false) {
  const from = date(c, `${prefix}.startDate`, start)
  if (ongoing) {
    return
  }
  const to = date(c, `${prefix}.endDate`, end)
  if (from !== null && to !== null && from > to) {
    c.add(`${prefix}.endDate`, { key: 'builder.validation.endBeforeStart' })
  }
}

function entries(c: Collector, path: string, size: number, max: number) {
  if (size > max) {
    c.add(path, { key: 'builder.validation.tooManyEntries', params: { max } })
  }
}

export function validateForm(form: DraftForm): FieldProblems {
  const problems: FieldProblems = {}
  const c: Collector = {
    add: (path, problem) => {
      if (!problems[path]) {
        problems[path] = problem
      }
    },
  }
  const { content } = form
  const s = LIMITS.short
  const m = LIMITS.medium
  const lines = LIMITS.linesPerEntry

  text(c, 'title', form.title, LIMITS.title)
  text(c, 'targetRole', form.targetRole, LIMITS.title)

  const info = content.basicInfo
  text(c, 'basicInfo.fullName', info.fullName, s)
  text(c, 'basicInfo.city', info.city, s)
  text(c, 'basicInfo.country', info.country, s)
  text(c, 'basicInfo.phone', info.phone, s)
  text(c, 'basicInfo.email', info.email, s)
  entries(c, 'basicInfo.professionalLinks', info.professionalLinks.length, LIMITS.professionalLinks)
  info.professionalLinks.forEach((link, i) => {
    text(c, `basicInfo.professionalLinks.${i}.label`, link.label, s)
    url(c, `basicInfo.professionalLinks.${i}.url`, link.url)
  })

  const target = content.jobTarget
  text(c, 'jobTarget.jobLevel', target.jobLevel, s)
  text(c, 'jobTarget.targetIndustry', target.targetIndustry, s)
  text(c, 'jobTarget.workMode', target.workMode, s)
  text(c, 'jobTarget.targetCity', target.targetCity, s)
  text(c, 'jobTarget.careerSummary', target.careerSummary, LIMITS.long)

  entries(c, 'education', content.education.length, LIMITS.entries.education)
  content.education.forEach((e, i) => {
    const p = `education.${i}`
    text(c, `${p}.school`, e.school, s)
    text(c, `${p}.degree`, e.degree, s)
    text(c, `${p}.major`, e.major, s)
    text(c, `${p}.gpa`, e.gpa, LIMITS.gpa)
    text(c, `${p}.honors`, e.honors, m)
    range(c, p, e.startDate, e.endDate)
    list(c, `${p}.courses`, e.courses, LIMITS.courses, s)
  })

  entries(c, 'skillCategories', content.skillCategories.length, LIMITS.entries.skillCategories)
  content.skillCategories.forEach((e, i) => {
    text(c, `skillCategories.${i}.name`, e.name, s)
    list(c, `skillCategories.${i}.skills`, e.skills, LIMITS.skillsPerCategory, s)
  })

  entries(c, 'experience', content.experience.length, LIMITS.entries.experience)
  content.experience.forEach((e, i) => {
    const p = `experience.${i}`
    text(c, `${p}.company`, e.company, s)
    text(c, `${p}.title`, e.title, s)
    text(c, `${p}.location`, e.location, s)
    range(c, p, e.startDate, e.current ? '' : e.endDate, e.current)
    list(c, `${p}.responsibilities`, e.responsibilities, lines, m)
    list(c, `${p}.toolsUsed`, e.toolsUsed, lines, s)
    list(c, `${p}.outcomes`, e.outcomes, lines, m)
  })

  entries(c, 'projects', content.projects.length, LIMITS.entries.projects)
  content.projects.forEach((e, i) => {
    const p = `projects.${i}`
    text(c, `${p}.name`, e.name, s)
    text(c, `${p}.purpose`, e.purpose, LIMITS.long)
    text(c, `${p}.actualDuration`, e.actualDuration, s)
    text(c, `${p}.role`, e.role, s)
    range(c, p, e.startDate, e.endDate)
    list(c, `${p}.techStack`, e.techStack, lines, s)
    list(c, `${p}.features`, e.features, lines, m)
    list(c, `${p}.challenges`, e.challenges, lines, m)
    list(c, `${p}.solutions`, e.solutions, lines, m)
    list(c, `${p}.outcomes`, e.outcomes, lines, m)
    url(c, `${p}.githubUrl`, e.githubUrl)
    url(c, `${p}.demoUrl`, e.demoUrl)
    count(c, `${p}.teamSize`, e.teamSize, LIMITS.teamSize)
    count(c, `${p}.userCount`, e.userCount, LIMITS.userCount)
  })

  entries(c, 'volunteer', content.volunteer.length, LIMITS.entries.volunteer)
  content.volunteer.forEach((e, i) => {
    const p = `volunteer.${i}`
    text(c, `${p}.organization`, e.organization, s)
    text(c, `${p}.role`, e.role, s)
    text(c, `${p}.location`, e.location, s)
    range(c, p, e.startDate, e.endDate)
    list(c, `${p}.workContent`, e.workContent, lines, m)
    list(c, `${p}.impact`, e.impact, lines, m)
    count(c, `${p}.serviceHours`, e.serviceHours, LIMITS.serviceHours)
  })

  entries(c, 'certifications', content.certifications.length, LIMITS.entries.certifications)
  content.certifications.forEach((e, i) => {
    const p = `certifications.${i}`
    text(c, `${p}.name`, e.name, s)
    text(c, `${p}.issuer`, e.issuer, s)
    text(c, `${p}.credentialId`, e.credentialId, s)
    url(c, `${p}.credentialUrl`, e.credentialUrl)
    date(c, `${p}.issueDate`, e.issueDate)
    date(c, `${p}.expiryDate`, e.expiryDate)
  })

  entries(c, 'languages', content.languages.length, LIMITS.entries.languages)
  content.languages.forEach((e, i) => {
    text(c, `languages.${i}.language`, e.language, s)
    text(c, `languages.${i}.proficiency`, e.proficiency, s)
  })

  entries(c, 'leadership', content.leadership.length, LIMITS.entries.leadership)
  content.leadership.forEach((e, i) => {
    const p = `leadership.${i}`
    text(c, `${p}.organization`, e.organization, s)
    text(c, `${p}.role`, e.role, s)
    range(c, p, e.startDate, e.endDate)
    list(c, `${p}.description`, e.description, lines, m)
  })

  entries(c, 'publications', content.publications.length, LIMITS.entries.publications)
  content.publications.forEach((e, i) => {
    const p = `publications.${i}`
    text(c, `${p}.title`, e.title, s)
    text(c, `${p}.venue`, e.venue, s)
    text(c, `${p}.authors`, e.authors, m)
    url(c, `${p}.url`, e.url)
    date(c, `${p}.date`, e.date)
  })

  list(c, 'hobbies', content.hobbies, LIMITS.hobbies, s)

  entries(c, 'customSections', content.customSections.length, LIMITS.entries.customSections)
  content.customSections.forEach((e, i) => {
    text(c, `customSections.${i}.title`, e.title, s)
    list(c, `customSections.${i}.items`, e.items, LIMITS.itemsPerCustomSection, m)
  })

  return problems
}

// ---------------------------------------------------------------------------
// request outcomes

/**
 * Responses that ResumeBuilderServiceImpl.generateFromDraft provably returns before any
 * file or resume row exists (or after its cleanup finished): auth failures, a missing
 * draft, validation and "not ready" (400), conflicts.
 */
const NOTHING_CREATED = new Set([400, 401, 403, 404, 409])

/**
 * True when a generate request may have created a file. That includes every 5xx: the
 * service has no transaction, a failed cleanup or an error after the claim row is written
 * still surfaces as a 500. Keeping the key is always safe -- the server replays the file
 * for a key it already used -- so only the statuses above drop it.
 */
export function isAmbiguousFailure(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status === null) {
    return true
  }
  return !NOTHING_CREATED.has(error.status)
}

export function isSaveConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}
