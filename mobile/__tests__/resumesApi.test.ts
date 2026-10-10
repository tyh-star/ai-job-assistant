import { ApiError, configureApiClient, fileNameFromDisposition } from '../src/api/client'
import { uploadErrorKey } from '../src/api/errors'
import { draftsApi, resumesApi } from '../src/api/endpoints'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const BASE = 'http://api.test/api/v1'
const onUnauthorized = jest.fn()

/** Records what the app puts into a multipart body. */
class RecordingFormData {
  static last: RecordingFormData | null = null
  parts: [string, unknown][] = []
  constructor() {
    RecordingFormData.last = this
  }
  append(name: string, value: unknown) {
    this.parts.push([name, value])
  }
}

type Listener = ((event?: unknown) => void) | null

/** A controllable XMLHttpRequest: tests decide the status, body and progress events. */
class FakeXhr {
  static last: FakeXhr | null = null
  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: unknown
  status = 0
  responseText = ''
  timeout = 0
  onload: Listener = null
  onerror: Listener = null
  ontimeout: Listener = null
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = {
    onprogress: null,
  }
  constructor() {
    FakeXhr.last = this
  }
  open(method: string, url: string) {
    this.method = method
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  send(body: unknown) {
    this.body = body
  }
  respond(status: number, body: unknown) {
    this.status = status
    this.responseText = JSON.stringify(body)
    this.onload?.()
  }
}

const fetchMock = jest.fn()
const FILE = { uri: 'file:///cache/cv.pdf', name: '简历 (1).pdf', mimeType: 'application/pdf' }

beforeEach(() => {
  fetchMock.mockReset()
  onUnauthorized.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
  ;(global as unknown as { XMLHttpRequest: unknown }).XMLHttpRequest = FakeXhr
  ;(global as unknown as { FormData: unknown }).FormData = RecordingFormData
  configureApiClient({ getToken: () => 'jwt-secret', onUnauthorized })
})

describe('upload', () => {
  it('posts multipart with the file in the "file" part, the token only in the header, and reports progress', async () => {
    const progress: number[] = []
    const promise = resumesApi.upload(FILE, (fraction) => progress.push(fraction))
    const xhr = FakeXhr.last as FakeXhr

    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe(`${BASE}/resumes`)
    expect(xhr.url).not.toContain('jwt-secret')
    expect(xhr.headers.Authorization).toBe('Bearer jwt-secret')
    expect(RecordingFormData.last?.parts).toEqual([
      ['file', { uri: FILE.uri, name: '简历 (1).pdf', type: 'application/pdf' }],
    ])

    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 512, total: 1024 })
    xhr.respond(201, { id: 5, fileName: '简历 (1).pdf' })
    await expect(promise).resolves.toMatchObject({ id: 5 })
    expect(progress).toEqual([0.5, 1])
  })

  it('rejects with the server message, mapped to the reason the user sees', async () => {
    const promise = resumesApi.upload(FILE)
    ;(FakeXhr.last as FakeXhr).respond(400, { message: 'Only PDF and DOCX files are allowed' })
    const error = await promise.catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(uploadErrorKey(error)).toBe('resumes.problem.badType')
  })

  it('maps unreadable files and oversize rejections', () => {
    expect(uploadErrorKey(new ApiError('/resumes', 422, { message: 'Failed to extract text' }))).toBe('resumes.errors.unreadable')
    expect(uploadErrorKey(new ApiError('/resumes', 413, null))).toBe('resumes.problem.tooLarge')
  })

  it('signs out on a 401 and reports network failures without a status', async () => {
    const unauthorized = resumesApi.upload(FILE)
    ;(FakeXhr.last as FakeXhr).respond(401, { message: 'Token revoked' })
    await expect(unauthorized).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)

    const offline = resumesApi.upload(FILE)
    ;(FakeXhr.last as FakeXhr).onerror?.()
    await expect(offline).rejects.toMatchObject({ status: null })
  })
})

describe('download', () => {
  it('sends the token as a Bearer header, never in the URL, and reads the UTF-8 file name', async () => {
    fetchMock.mockResolvedValue(
      new Response(new Uint8Array([37, 80, 68, 70]), {
        status: 200,
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': "attachment; filename*=UTF-8''%E7%AE%80%E5%8E%86%20(1).pdf",
        },
      }),
    )
    const file = await resumesApi.download(12)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BASE}/resumes/12/download`)
    expect(url).not.toContain('jwt-secret')
    expect(init.headers.Authorization).toBe('Bearer jwt-secret')
    expect(file.fileName).toBe('简历 (1).pdf')
    expect(Array.from(file.bytes)).toEqual([37, 80, 68, 70])
  })

  it('hands a 401 to the shared sign-out handler', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ message: 'expired' }), { status: 401 }))
    await expect(resumesApi.download(12)).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('parses both RFC 5987 and plain file name parameters', () => {
    expect(fileNameFromDisposition('attachment; filename="cv.docx"')).toBe('cv.docx')
    expect(fileNameFromDisposition('attachment; filename=cv.pdf')).toBe('cv.pdf')
    expect(fileNameFromDisposition(null)).toBeNull()
  })
})

describe('resume and draft routes use their own ids', () => {
  it('reads files, scores, deletes and drafts by the right path', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({}), { status: 200 })))
    await resumesApi.list()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe(`${BASE}/resumes`)
    await resumesApi.qualityScore(12)
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe(`${BASE}/resumes/12/quality-score`)
    await draftsApi.list()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe(`${BASE}/resume-builder/drafts`)
    await draftsApi.get(7)
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe(`${BASE}/resume-builder/drafts/7`)

    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })))
    await resumesApi.remove(12)
    expect(fetchMock.mock.calls.at(-1)).toEqual([`${BASE}/resumes/12`, expect.objectContaining({ method: 'DELETE' })])
    await resumesApi.removeFamily(12)
    expect(fetchMock.mock.calls.at(-1)).toEqual([`${BASE}/resumes/12/family`, expect.objectContaining({ method: 'DELETE' })])
  })
})
