import { configureApiClient } from '../src/api/client'
import { catalogApi, jobsApi } from '../src/api/endpoints'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const BASE = 'http://api.test/api/v1'
const fetchMock = jest.fn()
const onUnauthorized = jest.fn()

function respond(status: number, body?: unknown) {
  return Promise.resolve(
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
}

function lastCall(): { url: string; method: string; auth: string | undefined } {
  const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1]
  return { url, method: init.method, auth: init.headers.Authorization }
}

beforeEach(() => {
  fetchMock.mockReset()
  onUnauthorized.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
  configureApiClient({ getToken: () => 'jwt-1', onUnauthorized })
})

describe('catalog endpoints (string catalog reference)', () => {
  it('sends only the filters that are set, with 1-based paging', async () => {
    fetchMock.mockImplementation(() => respond(200, { page: 2, size: 20, totalCount: 0, totalPages: 0, hasMore: false, results: [] }))
    await catalogApi.search({ q: 'data analyst', city: 'Toronto', cityCountry: 'ca', page: 2, size: 20 })
    expect(lastCall()).toEqual({
      url: `${BASE}/job-catalog/search?q=data%20analyst&city=Toronto&cityCountry=ca&page=2&size=20`,
      method: 'GET',
      auth: 'Bearer jwt-1',
    })
    await catalogApi.search({ page: 1, size: 20 })
    expect(lastCall().url).toBe(`${BASE}/job-catalog/search?page=1&size=20`)
  })

  it('encodes the catalog reference in detail, save and applied paths', async () => {
    fetchMock.mockImplementation(() => respond(200, {}))
    await catalogApi.detail('freehire:senior-dev')
    expect(lastCall()).toMatchObject({ url: `${BASE}/job-catalog/freehire%3Asenior-dev`, method: 'GET' })
    await catalogApi.save('freehire:senior-dev')
    expect(lastCall()).toMatchObject({ url: `${BASE}/job-catalog/freehire%3Asenior-dev/save`, method: 'POST' })
    await catalogApi.markApplied('freehire:senior-dev')
    expect(lastCall()).toMatchObject({ url: `${BASE}/job-catalog/freehire%3Asenior-dev/applied`, method: 'POST' })
    await catalogApi.clearApplied('freehire:senior-dev')
    expect(lastCall()).toMatchObject({ url: `${BASE}/job-catalog/freehire%3Asenior-dev/applied`, method: 'DELETE' })
  })

  it('looks up cities with the typed prefix', async () => {
    fetchMock.mockReturnValue(respond(200, []))
    await catalogApi.cities('Tor')
    expect(lastCall().url).toBe(`${BASE}/job-catalog/cities?q=Tor`)
  })
})

describe('personal job endpoints (numeric id)', () => {
  it('reads, marks, clears and deletes by the personal id', async () => {
    fetchMock.mockImplementation(() => respond(200, {}))
    await jobsApi.list()
    expect(lastCall()).toMatchObject({ url: `${BASE}/jobs`, method: 'GET' })
    await jobsApi.get(42)
    expect(lastCall()).toMatchObject({ url: `${BASE}/jobs/42`, method: 'GET' })
    await jobsApi.markApplied(42)
    expect(lastCall()).toMatchObject({ url: `${BASE}/jobs/42/applied`, method: 'POST' })
    await jobsApi.clearApplied(42)
    expect(lastCall()).toMatchObject({ url: `${BASE}/jobs/42/applied`, method: 'DELETE' })
  })

  it('treats the 204 from delete as success', async () => {
    fetchMock.mockReturnValue(Promise.resolve(new Response(null, { status: 204 })))
    await expect(jobsApi.remove(42)).resolves.toBeUndefined()
    expect(lastCall()).toMatchObject({ url: `${BASE}/jobs/42`, method: 'DELETE' })
  })

  it('hands a 401 to the shared sign-out handler', async () => {
    fetchMock.mockReturnValue(respond(401, { message: 'Token revoked' }))
    await expect(jobsApi.list()).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
