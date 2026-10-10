import { ApiError, configureApiClient } from '../src/api/client'
import { draftsApi } from '../src/api/endpoints'
import { buildUpdateRequest, formFromResponse } from '../src/builder/model'
import { builderDraft } from '../test-utils/builderFixtures'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const BASE = 'http://api.test/api/v1/resume-builder/drafts'
const fetchMock = jest.fn()

function respond(status: number, body?: unknown) {
  return Promise.resolve(new Response(body === undefined ? null : JSON.stringify(body), { status }))
}

function call(index = -1) {
  const [url, init] = fetchMock.mock.calls.at(index)
  return { url, method: init.method, auth: init.headers.Authorization, body: init.body ? JSON.parse(init.body) : undefined }
}

beforeEach(() => {
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
  configureApiClient({ getToken: () => 'jwt-1', onUnauthorized: jest.fn() })
})

it('creates, reads, updates and deletes drafts on the routes ResumeBuilderController maps', async () => {
  fetchMock.mockImplementation(() => respond(201, builderDraft(9)))
  await draftsApi.create({ title: '未命名简历', templateType: 'STUDENT', locale: 'zh-CN' })
  expect(call()).toEqual({
    url: BASE,
    method: 'POST',
    auth: 'Bearer jwt-1',
    body: { title: '未命名简历', templateType: 'STUDENT', locale: 'zh-CN' },
  })

  fetchMock.mockImplementation(() => respond(200, []))
  await draftsApi.list()
  expect(call()).toMatchObject({ url: BASE, method: 'GET' })

  fetchMock.mockImplementation(() => respond(200, builderDraft(9)))
  await draftsApi.get(9)
  expect(call()).toMatchObject({ url: `${BASE}/9`, method: 'GET' })

  const request = buildUpdateRequest(formFromResponse(builderDraft(9)), 3, 'Untitled resume')
  await draftsApi.update(9, request)
  const put = call()
  expect(put).toMatchObject({ url: `${BASE}/9`, method: 'PUT' })
  expect(Object.keys(put.body).sort()).toEqual(['content', 'locale', 'targetRole', 'templateType', 'title', 'version'])
  expect(Object.keys(put.body.content).sort()).toEqual(
    [
      'basicInfo', 'careerMaterials', 'certifications', 'customSections', 'education', 'experience', 'hobbies', 'jobTarget',
      'languages', 'leadership', 'projects', 'publications', 'schemaVersion', 'sectionEnabled', 'sectionOrder',
      'skillCategories', 'volunteer',
    ].sort(),
  )

  fetchMock.mockImplementation(() => respond(204))
  await draftsApi.remove(9)
  expect(call()).toMatchObject({ url: `${BASE}/9`, method: 'DELETE', body: undefined })
})

it('generates with only the idempotency key in the body', async () => {
  fetchMock.mockImplementation(() => respond(201, { id: 40, fileName: '简历.docx', origin: 'GENERATED' }))
  const resume = await draftsApi.generate(9, { idempotencyKey: 'key-1' })
  expect(call()).toMatchObject({ url: `${BASE}/9/generate`, method: 'POST', body: { idempotencyKey: 'key-1' } })
  expect(resume.id).toBe(40)
})

it('surfaces the version conflict and a dropped connection distinctly', async () => {
  fetchMock.mockImplementation(() => respond(409, { status: 409, message: 'This draft changed elsewhere since it was last loaded. Reload before saving.' }))
  await expect(draftsApi.update(9, buildUpdateRequest(formFromResponse(builderDraft(9)), 1, 'x'))).rejects.toMatchObject({
    status: 409,
  })

  fetchMock.mockImplementation(() => Promise.reject(new TypeError('Network request failed')))
  const failure = await draftsApi.generate(9, { idempotencyKey: 'key-2' }).catch((error: unknown) => error)
  expect(failure).toBeInstanceOf(ApiError)
  expect((failure as ApiError).status).toBeNull()
})
