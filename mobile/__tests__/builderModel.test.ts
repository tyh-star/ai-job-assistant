import { ApiError } from '../src/api/client'
import { builderErrorKey } from '../src/api/errors'
import {
  FIXED_SECTION_IDS,
  addCustomSection,
  buildUpdateRequest,
  draftReadiness,
  formFromResponse,
  formSignature,
  hasEntryContent,
  isAmbiguousFailure,
  linesToList,
  newIdempotencyKey,
  normalizeContent,
  parseCount,
  removeCustomSection,
  syncProfessionalLinks,
  validateForm,
  type DraftForm,
} from '../src/builder/model'
import { builderDraft, readyDraft } from '../test-utils/builderFixtures'

function formWith(change: (form: DraftForm) => DraftForm = (form) => form): DraftForm {
  return change(formFromResponse(builderDraft(1)))
}

describe('normalizeContent mirrors the server defaults', () => {
  it('fills a blank draft without inventing any facts', () => {
    const content = normalizeContent({})
    expect(content.basicInfo).toEqual({
      fullName: '', city: '', country: '', phone: '', email: '', linkedin: '', github: '', website: '',
      professionalLinks: [], legallyEligibleToWork: null,
    })
    expect(content.experience).toEqual([])
    expect(content.hobbies).toEqual([])
    expect(content.sectionOrder).toEqual([...FIXED_SECTION_IDS])
    expect(Object.values(content.sectionEnabled).every(Boolean)).toBe(true)
    expect(content.careerMaterials.oneYearGoal).toBe('')
  })

  it('keeps what the website saved, including hidden sections and career materials', () => {
    const content = normalizeContent({
      sectionOrder: ['EDUCATION'],
      sectionEnabled: { HOBBIES: false },
      customSections: [{ id: 'c1', title: 'Awards', items: ['A'] }],
      careerMaterials: { oneYearGoal: 'Learn SQL', futureField: 'kept' },
      projects: [{ name: 'P', teamSize: 4, userCount: 'many' }],
    })
    expect(content.sectionOrder[0]).toBe('EDUCATION')
    expect(content.sectionOrder).toContain('CUSTOM:c1')
    expect(content.sectionEnabled.HOBBIES).toBe(false)
    expect(content.careerMaterials).toMatchObject({ oneYearGoal: 'Learn SQL', futureField: 'kept' })
    expect(content.projects[0]).toMatchObject({ name: 'P', teamSize: 4, userCount: null, techStack: [] })
  })

  it('migrates old scalar links, and keeps an explicitly empty list empty', () => {
    const base = normalizeContent({}).basicInfo
    const migrated = syncProfessionalLinks({ ...base, github: 'https://github.com/x' }, null)
    expect(migrated.professionalLinks).toEqual([{ type: 'GITHUB', label: '', url: 'https://github.com/x' }])
    const cleared = syncProfessionalLinks({ ...base, github: 'https://github.com/x' }, [])
    expect(cleared.professionalLinks).toEqual([])
    expect(cleared.github).toBe('')
    expect(syncProfessionalLinks(base, [{ type: 'website', label: ' Site ', url: ' https://a.test ' }])).toMatchObject({
      professionalLinks: [{ type: 'PERSONAL_WEBSITE', label: 'Site', url: 'https://a.test' }],
      website: 'https://a.test',
    })
  })
})

describe('the save request', () => {
  it('treats the list target role and the job target title as one fact', () => {
    const form = formFromResponse(builderDraft(1, { targetRole: null }, { jobTarget: { targetTitle: 'Analyst' } }))
    expect(form.targetRole).toBe('Analyst')
    const body = buildUpdateRequest({ ...form, targetRole: ' Data Analyst ' }, 3, 'Untitled resume')
    expect(body.targetRole).toBe('Data Analyst')
    expect(body.content.jobTarget.targetTitle).toBe('Data Analyst')
  })

  it('sends the loaded version, a non-blank title and every section untouched', () => {
    const form = formWith((f) => ({ ...f, title: '   ', targetRole: '' }))
    form.content.careerMaterials.oneYearGoal = 'kept as is'
    const body = buildUpdateRequest(form, 7, '未命名简历')
    expect(body).toMatchObject({ title: '未命名简历', version: 7, targetRole: null, templateType: 'GENERIC_ATS', locale: 'zh-CN' })
    expect(body.content.careerMaterials.oneYearGoal).toBe('kept as is')
    expect(body.content.sectionOrder).toEqual(form.content.sectionOrder)
  })

  it('notices any change through the signature', () => {
    const form = formWith()
    expect(formSignature(form)).toBe(formSignature(formWith()))
    expect(formSignature({ ...form, title: 'Changed' })).not.toBe(formSignature(form))
  })
})

describe('validateForm uses the backend limits', () => {
  it('accepts a blank form', () => {
    expect(validateForm(formWith())).toEqual({})
  })

  it('reports lengths, links, numbers, line counts and control characters by field', () => {
    const form = formWith((f) => ({
      ...f,
      title: 'x'.repeat(201),
      content: {
        ...f.content,
        basicInfo: { ...f.content.basicInfo, fullName: `A${String.fromCharCode(1)}`, professionalLinks: [{ type: 'OTHER', label: '', url: 'example.com' }] },
        projects: [{ ...normalizeContent({ projects: [{}] }).projects[0], teamSize: 100001 }],
        experience: [{ ...normalizeContent({ experience: [{}] }).experience[0], responsibilities: Array(31).fill('duty') }],
      },
    }))
    const problems = validateForm(form)
    expect(problems.title).toEqual({ key: 'builder.validation.tooLong', params: { max: 200 } })
    expect(problems['basicInfo.fullName'].key).toBe('builder.validation.illegalCharacters')
    expect(problems['basicInfo.professionalLinks.0.url'].key).toBe('builder.validation.url')
    expect(problems['projects.0.teamSize']).toEqual({ key: 'builder.validation.tooLarge', params: { max: 100000 } })
    expect(problems['experience.0.responsibilities']).toEqual({ key: 'builder.validation.tooManyLines', params: { max: 30 } })
  })

  it('checks dates the way the server does', () => {
    const entry = normalizeContent({ education: [{}], experience: [{}] })
    const check = (education: object, experience: object = {}) =>
      validateForm(
        formWith((f) => ({
          ...f,
          content: { ...f.content, education: [{ ...entry.education[0], ...education }], experience: [{ ...entry.experience[0], ...experience }] },
        })),
      )
    expect(check({ startDate: '2024-13' })['education.0.startDate'].key).toBe('builder.validation.date')
    expect(check({ startDate: '2024-02-30' })['education.0.startDate'].key).toBe('builder.validation.date')
    expect(check({ startDate: '2024-09', endDate: '至今' })).toEqual({})
    expect(check({ startDate: '2024-09', endDate: '2023-06-01' })['education.0.endDate'].key).toBe('builder.validation.endBeforeStart')
    expect(check({}, { startDate: '2024-09', endDate: '2020-01', current: true })).toEqual({})
  })
})

it('needs a name, a contact and a target title before generating, like the website', () => {
  expect(draftReadiness(formFromResponse(readyDraft(1))).ready).toBe(true)
  const noContact = formFromResponse(readyDraft(1))
  noContact.content.basicInfo.email = ' '
  expect(draftReadiness(noContact)).toMatchObject({ hasName: true, hasContact: false, hasTarget: true, ready: false })
})

it('parses list and number inputs', () => {
  expect(linesToList(' first \n\n second\n')).toEqual(['first', 'second'])
  expect(parseCount('')).toBeNull()
  expect(parseCount('1,200')).toBe(1200)
})

it('registers and unregisters custom sections in the order', () => {
  const added = addCustomSection(normalizeContent({}))
  const ref = `CUSTOM:${added.customSections[0].id}`
  expect(added.sectionOrder).toContain(ref)
  expect(added.sectionEnabled[ref]).toBe(true)
  const removed = removeCustomSection(added, 0)
  expect(removed.sectionOrder).not.toContain(ref)
  expect(ref in removed.sectionEnabled).toBe(false)
  expect(hasEntryContent(added.customSections[0])).toBe(false)
})

describe('generate requests', () => {
  it('makes a fresh key per action within the 100 character limit, even without randomUUID', () => {
    const first = newIdempotencyKey()
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(newIdempotencyKey()).not.toBe(first)

    const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true })
    try {
      expect(newIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/)
    } finally {
      if (original) {
        Object.defineProperty(globalThis, 'crypto', original)
      }
    }
  })

  it('drops the key only for responses the server returns before creating anything', () => {
    for (const status of [null, 500, 502, 503, 504, 429]) {
      expect(isAmbiguousFailure(new ApiError('/g', status, null))).toBe(true)
    }
    for (const status of [400, 401, 403, 404, 409]) {
      expect(isAmbiguousFailure(new ApiError('/g', status, null))).toBe(false)
    }
    expect(isAmbiguousFailure(new TypeError('boom'))).toBe(true)
  })

  it('maps the messages the builder endpoints return', () => {
    const notReady = new ApiError('/g', 400, { message: 'Add at least a name or a way to contact you before generating' })
    expect(builderErrorKey(notReady)).toBe('builder.errors.notReady')
    expect(builderErrorKey(new ApiError('/d', 400, { message: 'basicInfo.email is too long (max 200 characters)' }))).toBe(
      'builder.errors.invalid',
    )
    expect(builderErrorKey(new ApiError('/d', 409, { message: 'This draft changed elsewhere since it was last loaded.' }))).toBe(
      'builder.errors.conflict',
    )
    expect(builderErrorKey(new ApiError('/d', 404, { message: 'Draft not found' }))).toBe('builder.errors.draftGone')
    expect(builderErrorKey(new ApiError('/d', null, null))).toBe('errors.network')
  })
})
