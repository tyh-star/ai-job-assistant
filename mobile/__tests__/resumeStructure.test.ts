import { configureApiClient } from '../src/api/client'
import { resumesApi } from '../src/api/endpoints'
import {
  missingTailorContent,
  normalizeStructure,
  structureChangedSince,
  validateStructure,
  withSkillNames,
  withSummaryTexts,
} from '../src/resumes/structure'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const RAW = {
  resumeId: 5,
  reviewStatus: 'PENDING_REVIEW',
  llmClassified: true,
  structureFormatVersion: 2,
  contact: { fullName: null, email: 'person@example.test', links: null },
  summary: '',
  summaryBullets: [{ text: 'Analyst', sourceBlockIds: ['b1'], confidence: 'HIGH', provenance: 'LLM' }],
  skills: [{ name: 'SQL', sourceBlockIds: ['b2'] }, { name: 'Excel', sourceBlockIds: ['b3'] }],
  experience: [{ company: 'Example Co.', bullets: null, sourceBlockIds: ['b4'] }],
  projects: null,
  unassigned: [{ id: 'b9', page: 1, x: 3, text: 'References' }],
  fieldProvenance: { summary: 'LLM' },
  layoutProfile: { version: 1, pageSize: 'LETTER', confidence: 'HIGH' },
}

it('fills only the defaults the website fills and keeps every other field', () => {
  const doc = normalizeStructure(RAW)
  expect(doc.contact).toEqual({ fullName: '', email: 'person@example.test', phone: '', location: '', links: [] })
  expect(doc.experience[0]).toEqual({ company: 'Example Co.', title: '', startDate: '', endDate: '', location: '', bullets: [], sourceBlockIds: ['b4'] })
  expect(doc.projects).toEqual([])
  expect(doc.education).toEqual([])
  expect(doc.layoutProfile).toEqual(RAW.layoutProfile)
  expect(doc.fieldProvenance).toEqual(RAW.fieldProvenance)
  expect(doc.unassigned).toEqual(RAW.unassigned)
  expect(doc.llmClassified).toBe(true)
})

it('keeps the n-th item source data when lines are edited, like the website merge', () => {
  const doc = normalizeStructure(RAW)
  expect(withSkillNames(doc, ['PostgreSQL', 'Excel', 'Python']).skills).toEqual([
    { name: 'PostgreSQL', sourceBlockIds: ['b2'] },
    { name: 'Excel', sourceBlockIds: ['b3'] },
    { name: 'Python' },
  ])
  expect(withSummaryTexts(doc, []).summaryBullets).toEqual([])
})

it('requires a name to save and warns when tailoring would still be refused', () => {
  const doc = normalizeStructure(RAW)
  expect(validateStructure(doc)).toBe('resumes.structure.fullNameRequired')
  expect(validateStructure({ ...doc, contact: { ...doc.contact, fullName: ' Test Person ' } })).toBeNull()
  expect(missingTailorContent(doc)).toBe(false)
  expect(missingTailorContent({ ...doc, skills: [{ name: ' ' }] })).toBe(true)
  expect(missingTailorContent({ ...doc, skills: [], experience: [{ ...doc.experience[0], bullets: ['Built reports'] }] })).toBe(false)
})

it('treats a proposal as outdated only when a structure was saved after it', () => {
  expect(structureChangedSince(null, '2026-09-14T10:00:00.000Z')).toBe(false)
  expect(structureChangedSince('2026-09-14T10:00:00.000Z', '2026-09-14T09:00:00.000Z')).toBe(true)
  expect(structureChangedSince('2026-09-14T10:00:00.000Z', '2026-09-14T11:00:00.000Z')).toBe(false)
  expect(structureChangedSince('2026-09-14T10:00:00.000Z', null)).toBe(true)
})

describe('API contract', () => {
  const fetchMock = jest.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(RAW), { status: 200 })))
    global.fetch = fetchMock as unknown as typeof fetch
    configureApiClient({ getToken: () => 'jwt-1', onUnauthorized: jest.fn() })
  })

  it('reads and replaces the structure of a PDF by its file id', async () => {
    await resumesApi.structure(5)
    let [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://api.test/api/v1/resumes/5/structure')
    expect(init.method).toBe('GET')
    expect(init.headers.Authorization).toBe('Bearer jwt-1')

    const doc = normalizeStructure(RAW)
    await resumesApi.updateStructure(5, doc)
    ;[url, init] = fetchMock.mock.calls[1]
    expect(url).toBe('http://api.test/api/v1/resumes/5/structure')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body)).toEqual(doc)
  })
})
