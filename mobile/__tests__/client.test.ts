import { ApiError, apiRequest, configureApiClient } from '../src/api/client'
import { apiErrorKey, loginErrorKey } from '../src/api/errors'

const BASE = 'http://api.test/api/v1'

function respond(status: number, body: unknown) {
  return Promise.resolve(
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
}

describe('apiRequest', () => {
  const fetchMock = jest.fn()
  const onUnauthorized = jest.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    onUnauthorized.mockReset()
    global.fetch = fetchMock as unknown as typeof fetch
    configureApiClient({ getToken: () => 'jwt-123', onUnauthorized })
  })

  it('sends the bearer token on authenticated requests', async () => {
    fetchMock.mockReturnValue(respond(200, []))
    await apiRequest('/jobs', { baseUrl: BASE })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BASE}/jobs`)
    expect(init.headers.Authorization).toBe('Bearer jwt-123')
  })

  it('never sends a token to login or register', async () => {
    fetchMock.mockReturnValue(respond(200, { token: 't' }))
    await apiRequest('/auth/login', { method: 'POST', body: { email: 'a@b.co', password: 'x' }, baseUrl: BASE })
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  it('signs out on a 401 from an authenticated request', async () => {
    fetchMock.mockReturnValue(respond(401, { message: 'Token revoked' }))
    await expect(apiRequest('/jobs', { baseUrl: BASE })).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('treats a 401 from login as wrong credentials, not an expired session', async () => {
    fetchMock.mockReturnValue(respond(401, { message: 'Invalid email or password' }))
    const error = await apiRequest('/auth/login', { method: 'POST', body: {}, baseUrl: BASE }).catch((e) => e)
    expect(onUnauthorized).not.toHaveBeenCalled()
    expect(loginErrorKey(error)).toBe('auth.invalidCredentials')
  })

  it('reports a network failure without a status', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'))
    const error = (await apiRequest('/jobs', { baseUrl: BASE }).catch((e: unknown) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBeNull()
    expect(apiErrorKey(error)).toBe('errors.network')
  })

  it('refuses to call anything when no base URL is configured', async () => {
    await expect(apiRequest('/jobs', { baseUrl: null })).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps backend conflicts and weak passwords to their messages', () => {
    expect(apiErrorKey(new ApiError('/auth/register', 409, { message: 'Email already exists' }))).toBe('errors.emailExists')
    expect(apiErrorKey(new ApiError('/auth/register', 400, { code: 'WEAK_PASSWORD' }))).toBe('errors.weakPassword')
  })
})
