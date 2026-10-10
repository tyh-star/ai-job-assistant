import { ApiError, configureApiClient } from '../src/api/client'
import { tailorApi } from '../src/api/endpoints'
import { tailoredFileFromUrl, tailoredShareName } from '../src/tailor/files'
import {
  confirmNodeIds,
  isAmbiguousTailorFailure,
  isProposalExpired,
  jobHasDescription,
  parseServerUtc,
  prepareErrorKey,
  resumeEligibility,
  tailorErrorKey,
} from '../src/tailor/model'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const BASE = 'http://api.test/api/v1'
const UUID = '0f8fad5b-d9cb-469f-a165-70867728950e'
const fetchMock = jest.fn()

function respond(status: number, body?: unknown, headers?: Record<string, string>) {
  return Promise.resolve(new Response(body === undefined ? null : JSON.stringify(body), { status, headers }))
}

function call(index = -1) {
  const [url, init] = fetchMock.mock.calls.at(index)
  return { url, method: init.method, auth: init.headers.Authorization, body: init.body ? JSON.parse(init.body) : undefined }
}

describe('API contract', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    global.fetch = fetchMock as unknown as typeof fetch
    configureApiClient({ getToken: () => 'jwt-1', onUnauthorized: jest.fn() })
  })

  it('calls the JobController and ResumeController routes the website uses', async () => {
    fetchMock.mockImplementation(() => respond(201, {}))
    await tailorApi.run(9, 4, 'CONSERVATIVE')
    expect(call()).toEqual({ url: `${BASE}/jobs/9/tailor?resumeId=4&mode=CONSERVATIVE`, method: 'POST', auth: 'Bearer jwt-1', body: undefined })
    await tailorApi.run(9, 4, 'AGGRESSIVE')
    expect(call().url).toBe(`${BASE}/jobs/9/tailor?resumeId=4&mode=AGGRESSIVE`)
    await tailorApi.confirm(9, 31, { selectedNodeIds: ['p-1', 'p-2'] })
    expect(call()).toMatchObject({ url: `${BASE}/jobs/9/tailor-proposals/31/confirm`, method: 'POST', body: { selectedNodeIds: ['p-1', 'p-2'] } })
    await tailorApi.proposal(9, 31)
    expect(call()).toMatchObject({ url: `${BASE}/jobs/9/tailor-proposals/31`, method: 'GET' })
    await tailorApi.structure(5)
    expect(call()).toMatchObject({ url: `${BASE}/resumes/5/structure`, method: 'GET' })
    await tailorApi.canonicalize(5)
    expect(call()).toMatchObject({ url: `${BASE}/resumes/5/canonicalize`, method: 'POST' })
  })

  it('downloads a tailored file with the Bearer header and no token in the URL', async () => {
    fetchMock.mockImplementation(() => respond(200, 'bytes', { 'content-disposition': `attachment; filename="${UUID}.docx"` }))
    const file = tailoredFileFromUrl(`/api/v1/jobs/9/tailored/${UUID}.docx`, 9)
    expect(file).toEqual({ path: `/jobs/9/tailored/${UUID}.docx`, fileType: 'docx' })
    await tailorApi.download(file!.path)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BASE}/jobs/9/tailored/${UUID}.docx`)
    expect(url).not.toContain('jwt-1')
    expect(init.headers.Authorization).toBe('Bearer jwt-1')
  })
})

describe('tailored file paths', () => {
  it('accepts only the tailored-file route of the same job', () => {
    expect(tailoredFileFromUrl(`/api/v1/jobs/9/tailored/${UUID}.pdf`, 9)).toEqual({ path: `/jobs/9/tailored/${UUID}.pdf`, fileType: 'pdf' })
    expect(tailoredFileFromUrl(`/api/v1/jobs/8/tailored/${UUID}.docx`, 9)).toBeNull()
    expect(tailoredFileFromUrl('/api/v1/resumes/4/download', 9)).toBeNull()
    expect(tailoredFileFromUrl(`https://elsewhere.test/api/v1/jobs/9/tailored/${UUID}.docx`, 9)).toBeNull()
    expect(tailoredFileFromUrl('/api/v1/jobs/9/tailored/../secret.docx', 9)).toBeNull()
    expect(tailoredFileFromUrl(`/api/v1/jobs/9/tailored/${UUID}.exe`, 9)).toBeNull()
    expect(tailoredFileFromUrl(null, 9)).toBeNull()
  })

  it('names the shared file after the resume and job, keeping Chinese', () => {
    expect(tailoredShareName('%E7%AE%80%E5%8E%86.pdf', '数据分析师', 'docx')).toBe('简历 - 数据分析师.docx')
    expect(tailoredShareName('cv.docx', 'Analyst / BI', 'pdf')).toBe('cv - Analyst BI.pdf')
  })
})

describe('rules', () => {
  it('allows only available PDF and DOCX files, and jobs with a description', () => {
    expect(resumeEligibility({ fileType: 'docx', fileAvailable: true })).toBe('ok')
    expect(resumeEligibility({ fileType: 'PDF', fileAvailable: true })).toBe('ok')
    expect(resumeEligibility({ fileType: 'pdf', fileAvailable: false })).toBe('unavailable')
    expect(resumeEligibility({ fileType: 'doc', fileAvailable: true })).toBe('unsupported')
    expect(jobHasDescription({ description: '  ' })).toBe(false)
    expect(jobHasDescription({ description: 'Build dashboards' })).toBe(true)
  })

  it('reads the server UTC time without an offset', () => {
    expect(parseServerUtc('2026-09-14T10:00:00')).toBe(Date.UTC(2026, 8, 14, 10, 0, 0))
    expect(isProposalExpired('2026-09-14T10:00:00', Date.UTC(2026, 8, 14, 9, 59))).toBe(false)
    expect(isProposalExpired('2026-09-14T10:00:00', Date.UTC(2026, 8, 14, 10, 0))).toBe(true)
    expect(isProposalExpired(null)).toBe(false)
  })

  it('confirms every proposed node once', () => {
    expect(confirmNodeIds([
      { nodeId: 'p-1', originalText: 'a', newText: 'b' },
      { nodeId: 'p-1', originalText: 'a', newText: 'b' },
      { nodeId: ' ', originalText: 'a', newText: 'b' },
      { nodeId: 'p-2', originalText: 'c', newText: 'd' },
    ])).toEqual(['p-1', 'p-2'])
    expect(confirmNodeIds(null)).toEqual([])
  })

  it('treats only responses returned before writing anything as definite', () => {
    for (const status of [null, 500, 502, 504]) {
      expect(isAmbiguousTailorFailure(new ApiError('/t', status, null))).toBe(true)
    }
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(isAmbiguousTailorFailure(new ApiError('/t', status, null))).toBe(false)
    }
  })

  it('maps the messages the tailoring service returns', () => {
    const e = (status: number, message: string) => new ApiError('/t', status, { message })
    expect(tailorErrorKey(e(400, 'Resume tailoring currently supports DOCX only'))).toBe('tailor.errors.docxOnly')
    expect(tailorErrorKey(e(400, 'V3 BASIC requires a canonical or native DOCX'))).toBe('tailor.errors.docxOnly')
    expect(tailorErrorKey(e(422, 'Job description is not available for matching'))).toBe('tailor.errors.noDescription')
    expect(tailorErrorKey(e(409, 'Tailor proposal is already being confirmed'))).toBe('tailor.errors.confirmInProgress')
    expect(tailorErrorKey(e(404, 'Tailor proposal not found'))).toBe('tailor.errors.proposalGone')
    expect(tailorErrorKey(e(502, 'AI failed'))).toBe('errors.serviceUnavailable')
    expect(prepareErrorKey(e(400, 'Full name is required'))).toBe('tailor.errors.incompleteRecognition')
  })
})
