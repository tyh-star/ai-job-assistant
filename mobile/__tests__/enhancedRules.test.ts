import { ApiError, configureApiClient } from '../src/api/client'
import { enhancedApi, evidenceApi } from '../src/api/endpoints'
import {
  answerPayload,
  answersToSubmit,
  applyEntryText,
  applySkillText,
  buildEnhancedConfirmBody,
  canSubmitAnswers,
  enhancedErrorKey,
  isEnhancedResponse,
  selectedEntryKey,
  suggestionMap,
  validateEnhancedEdits,
  visibleQuestions,
} from '../src/tailor/enhanced/rules'
import { pendingProposalKey } from '../src/tailor/pendingRecord'
import { enhancedPendingKey, knownResultKey, parseEnhancedPending, parseKnownResult } from '../src/tailor/records'
import type { TailoredResumeResponse, V3EnhancedQuestion } from '../src/tailor/types'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const UUID = '0f8fad5b-d9cb-469f-a165-70867728950e'

function question(id: string, patch: Partial<V3EnhancedQuestion> = {}): V3EnhancedQuestion {
  return { id, canonicalEntryKey: 'exp-1', entryTitle: 'Analyst', topic: 'dashboards', promptEn: 'Q', promptZh: '问', skipAllowed: true, ...patch }
}

const reviews: Pick<TailoredResumeResponse, 'entryReviews' | 'skillReviews'> = {
  entryReviews: [
    {
      canonicalEntryKey: 'exp-1',
      entryTitle: 'Analyst',
      originalNodes: [{ nodeId: 'p-1', text: 'a' }, { nodeId: 'p-2', text: 'b' }],
      proposedNodes: [{ nodeId: 'p-1', text: 'A1' }, { nodeId: 'p-2', text: 'B1' }],
      whyBetter: '',
      suggestionType: 'EVIDENCE_ENHANCED',
      usedEvidenceItemIds: [],
    },
    {
      canonicalEntryKey: 'proj-1',
      entryTitle: 'Project',
      originalNodes: [{ nodeId: 'p-5', text: 'c' }],
      proposedNodes: [{ nodeId: 'p-5', text: 'C1' }],
      whyBetter: '',
      suggestionType: 'JD_TARGETED',
      usedEvidenceItemIds: [],
    },
  ],
  skillReviews: [
    {
      skillReviewId: 'skills:technical',
      originalSkills: 'SQL',
      proposedSkills: 'SQL, Power BI',
      proposedNodes: [{ nodeId: 's-1', text: 'SQL, Power BI' }],
    },
  ],
}

describe('answers (website enhancedAnswers.ts rules)', () => {
  it('shows at most 5 questions and needs a real description for "I did this"', () => {
    expect(visibleQuestions(Array.from({ length: 7 }, (_, i) => question(`q${i}`)))).toHaveLength(5)
    const qs = [question('q1'), question('q2', { topic: 'maintain' })]
    expect(canSubmitAnswers(qs, { q1: { intent: 'used', text: '' } })).toEqual({ ok: false, questionId: 'q1' })
    expect(canSubmitAnswers(qs, { q1: { intent: 'used', text: '做过。' } })).toEqual({ ok: false, questionId: 'q1' })
    expect(canSubmitAnswers(qs, { q1: { intent: 'used', text: 'Built dashboards' }, q2: { intent: 'used', text: 'maintained it' } })).toEqual({
      ok: false,
      questionId: 'q2',
    })
    expect(canSubmitAnswers(qs, { q1: { intent: 'not_used', text: '' }, q2: { intent: 'used', text: 'Patched the ETL jobs every week' } })).toEqual({ ok: true })
  })

  it('submits unanswered questions as SKIPPED and limits the entry to allowedEntryKeys', () => {
    const q = question('q1', { allowedEntryKeys: ['exp-1', 'proj-1'] })
    expect(answerPayload(q, undefined)).toEqual({ questionId: 'q1', answerStatus: 'SKIPPED', entryKey: 'exp-1' })
    expect(selectedEntryKey(q, { intent: null, text: '', entryKey: 'other' })).toBe('exp-1')
    expect(answerPayload(q, { intent: 'used', text: '  Built it  ', entryKey: 'proj-1' })).toEqual({
      questionId: 'q1',
      answerStatus: 'CONFIRMED_USED',
      answerText: 'Built it',
      entryKey: 'proj-1',
    })
    expect(answerPayload(q, { intent: 'not_used', text: 'ignored' })).toEqual({ questionId: 'q1', answerStatus: 'CONFIRMED_NOT_USED', entryKey: 'exp-1' })
  })

  it('resends only answers that never succeeded or changed since', () => {
    const qs = [question('q1'), question('q2')]
    const saved = { intent: 'not_used' as const, text: '' }
    const signature = JSON.stringify(answerPayload(qs[0], saved))
    expect(answersToSubmit(qs, { q1: { ...saved, submittedSignature: signature } }).map((item) => item.question.id)).toEqual(['q2'])
    expect(answersToSubmit(qs, { q1: { intent: 'skipped', text: '', submittedSignature: signature } }).map((item) => item.question.id)).toEqual(['q1', 'q2'])
  })
})

describe('confirm body (website enhancedConfirm.ts)', () => {
  const ai = suggestionMap(reviews)

  it('sends only selected keys, no edits when nothing changed, and never selectedNodeIds', () => {
    const body = buildEnhancedConfirmBody(reviews, ['exp-1'], ['skills:technical'], ai, ai)
    expect(body).toEqual({ selectedEntryKeys: ['exp-1'], selectedSkillReviewIds: ['skills:technical'] })
    expect('selectedNodeIds' in body).toBe(false)
  })

  it('maps edited lines onto the real proposed nodes of selected items only', () => {
    let texts = applyEntryText(ai, reviews.entryReviews![0], 'first line\nsecond line\nthird line')
    texts = applyEntryText(texts, reviews.entryReviews![1], 'unselected edit')
    texts = applySkillText(texts, reviews.skillReviews![0], 'SQL, Tableau')
    expect(texts['p-2']).toBe('second line\nthird line')
    const body = buildEnhancedConfirmBody(reviews, ['exp-1'], ['skills:technical'], texts, ai)
    expect(body.editedEntries).toEqual([
      { canonicalEntryKey: 'exp-1', nodes: [{ nodeId: 'p-1', newText: 'first line' }, { nodeId: 'p-2', newText: 'second line\nthird line' }] },
    ])
    expect(body.editedSkillReviews).toEqual([
      { skillReviewId: 'skills:technical', proposedSkills: 'SQL, Tableau', nodes: [{ nodeId: 's-1', newText: 'SQL, Tableau' }] },
    ])
  })

  it('checks blank and over-long node text like the server', () => {
    const blank = buildEnhancedConfirmBody(reviews, ['exp-1'], [], applyEntryText(ai, reviews.entryReviews![0], 'only one line'), ai)
    expect(validateEnhancedEdits(blank)).toEqual({ key: 'tailor.enhanced.errors.editBlank' })
    const long = buildEnhancedConfirmBody(reviews, ['proj-1'], [], applyEntryText(ai, reviews.entryReviews![1], 'x'.repeat(181)), ai)
    expect(validateEnhancedEdits(long)).toEqual({ key: 'tailor.enhanced.errors.editTooLong', params: { max: 180 } })
  })
})

describe('mode identification and local records', () => {
  it('identifies ENHANCED by productMode even though mode is CONSERVATIVE', () => {
    expect(isEnhancedResponse({ productMode: 'ENHANCED' })).toBe(true)
    expect(isEnhancedResponse({ productMode: null })).toBe(false)
  })

  it('keeps ENHANCED and older AGGRESSIVE records apart', () => {
    expect(enhancedPendingKey(1, 9)).not.toBe(pendingProposalKey(1, 9))
    const legacy = JSON.stringify({ proposalId: 31, jobId: 9, resumeId: 4, expiresAt: null })
    expect(parseEnhancedPending(legacy)).toBeNull()
    expect(parseEnhancedPending(JSON.stringify({ productMode: 'ENHANCED', proposalId: 51, jobId: 9, resumeId: 5, tailorResumeId: 77, expiresAt: null, stale: true }))).toEqual({
      productMode: 'ENHANCED',
      proposalId: 51,
      jobId: 9,
      resumeId: 5,
      tailorResumeId: 77,
      expiresAt: null,
      stale: true,
      staleReason: null,
      savedAt: null,
      preparedAt: null,
    })
  })

  it('accepts a known result only with this job’s tailored download path', () => {
    const record = { mode: 'ENHANCED', jobId: 9, resumeId: 4, downloadUrl: `/api/v1/jobs/9/tailored/${UUID}.docx`, pdfDownloadUrl: '/api/v1/resumes/4/download', appliedChangeCount: 2, rejectedChangeCount: 0, savedAt: '2026-09-14T00:00:00.000Z' }
    expect(parseKnownResult(JSON.stringify(record), 9, 'ENHANCED')).toMatchObject({ downloadUrl: record.downloadUrl, pdfDownloadUrl: null })
    expect(parseKnownResult(JSON.stringify(record), 9, 'AGGRESSIVE')).toBeNull()
    expect(parseKnownResult(JSON.stringify(record), 8, 'ENHANCED')).toBeNull()
    expect(parseKnownResult(JSON.stringify({ ...record, downloadUrl: '/api/v1/resumes/4/download' }), 9, 'ENHANCED')).toBeNull()
    expect(knownResultKey(1, 9, 'ENHANCED')).toBe('aijob.tailorResult.1.9.ENHANCED')
  })
})

it('maps the ENHANCED and evidence errors the server returns', () => {
  const e = (status: number, message: string) => new ApiError('/x', status, { message })
  expect(enhancedErrorKey(e(400, 'Describe the actual work. A yes/no answer is not enough.'))).toBe('tailor.enhanced.errors.needRealDetails')
  expect(enhancedErrorKey(e(400, 'questionId is not valid for this resume and job'))).toBe('tailor.enhanced.errors.questionStale')
  expect(enhancedErrorKey(e(400, 'ENHANCED mode is not available'))).toBe('tailor.enhanced.errors.unavailable')
  expect(enhancedErrorKey(e(400, 'ENHANCED requires a canonical or native DOCX'))).toBe('tailor.errors.docxOnly')
  expect(enhancedErrorKey(e(400, 'edited nodeId must be selected'))).toBe('tailor.enhanced.errors.editRejected')
  expect(enhancedErrorKey(e(400, 'EVIDENCE_ENTRY_MISMATCH: entryKey is not a valid catalog option'))).toBe('tailor.enhanced.errors.evidenceInvalid')
  expect(enhancedErrorKey(e(409, 'You already saved evidence of using this skill. Delete the existing evidence before marking it as not used.'))).toBe(
    'tailor.enhanced.errors.evidenceConflict',
  )
  expect(enhancedErrorKey(e(409, 'Tailor proposal is already being confirmed'))).toBe('tailor.errors.confirmInProgress')
})

describe('API contract', () => {
  const BASE = 'http://api.test/api/v1'
  const fetchMock = jest.fn()
  const call = (index = -1) => {
    const [url, init] = fetchMock.mock.calls.at(index)
    return { url, method: init.method, auth: init.headers.Authorization, body: init.body ? JSON.parse(init.body) : undefined }
  }

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({}), { status: 201 })))
    global.fetch = fetchMock as unknown as typeof fetch
    configureApiClient({ getToken: () => 'jwt-1', onUnauthorized: jest.fn() })
  })

  it('uses the JobController ENHANCED routes', async () => {
    await enhancedApi.scan(9, 77)
    expect(call()).toEqual({ url: `${BASE}/jobs/9/tailor-enhanced/opportunity?resumeId=77`, method: 'POST', auth: 'Bearer jwt-1', body: {} })
    await enhancedApi.answer(9, 77, { questionId: 'q1', answerStatus: 'CONFIRMED_USED', answerText: 'Built it', entryKey: 'exp-1' })
    expect(call()).toMatchObject({
      url: `${BASE}/jobs/9/tailor-enhanced/answers?resumeId=77`,
      method: 'POST',
      body: { questionId: 'q1', answerStatus: 'CONFIRMED_USED', answerText: 'Built it', entryKey: 'exp-1' },
    })
    await enhancedApi.createProposal(9, 77)
    expect(call()).toMatchObject({ url: `${BASE}/jobs/9/tailor-proposals?resumeId=77&productMode=ENHANCED`, method: 'POST', body: undefined })
    await enhancedApi.confirm(9, 51, { selectedEntryKeys: ['exp-1'] })
    expect(call()).toMatchObject({ url: `${BASE}/jobs/9/tailor-proposals/51/confirm`, method: 'POST', body: { selectedEntryKeys: ['exp-1'] } })
  })

  it('uses the ResumeEvidenceController routes', async () => {
    await evidenceApi.list(4)
    expect(call()).toMatchObject({ url: `${BASE}/resumes/4/evidence`, method: 'GET' })
    await evidenceApi.save(4, {
      topic: 'dashboards', category: 'HARD_SKILL', answerStatus: 'CONFIRMED_USED', entryKey: 'exp-1', relatedNodeIds: [],
      context: '', action: 'Built it', result: '', metric: '', confirmed: true,
    })
    expect(call()).toMatchObject({ url: `${BASE}/resumes/4/evidence`, method: 'POST', body: { action: 'Built it', confirmed: true } })
    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })))
    await evidenceApi.remove(4, 81)
    expect(call()).toMatchObject({ url: `${BASE}/resumes/4/evidence/81`, method: 'DELETE' })
  })
})
