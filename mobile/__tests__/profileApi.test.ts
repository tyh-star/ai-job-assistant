import { configureApiClient } from '../src/api/client'
import { accountErrorKey } from '../src/api/errors'
import { ApiError } from '../src/api/client'
import { feedbackApi, usersApi } from '../src/api/endpoints'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const BASE = 'http://api.test/api/v1'
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

it('reads and changes the signed-in account without any user id in the URL', async () => {
  fetchMock.mockImplementation(() => respond(200, {}))
  await usersApi.me()
  expect(call()).toMatchObject({ url: `${BASE}/users/me`, method: 'GET', auth: 'Bearer jwt-1' })
  await usersApi.updateName({ name: 'nb' })
  expect(call()).toMatchObject({ url: `${BASE}/users/me/name`, method: 'PUT', body: { name: 'nb' } })
  await usersApi.updateEmail({ currentPassword: 'Secret#123', newEmail: 'a@b.test' })
  expect(call()).toMatchObject({
    url: `${BASE}/users/me/email`,
    method: 'PUT',
    body: { currentPassword: 'Secret#123', newEmail: 'a@b.test' },
  })

  fetchMock.mockImplementation(() => respond(204))
  await usersApi.changePassword({ currentPassword: 'Secret#123', newPassword: 'Newer#4567' })
  expect(call()).toMatchObject({ url: `${BASE}/users/me/password`, method: 'PUT' })
  await usersApi.deleteMe()
  expect(call()).toMatchObject({ url: `${BASE}/users/me`, method: 'DELETE', body: undefined })
})

it('reads, saves and deletes the account feedback', async () => {
  fetchMock.mockImplementation(() => respond(200, {}))
  await feedbackApi.me()
  expect(call()).toMatchObject({ url: `${BASE}/feedback/me`, method: 'GET' })
  await feedbackApi.save({ rating: 4, comment: null })
  expect(call()).toMatchObject({ url: `${BASE}/feedback/me`, method: 'PUT', body: { rating: 4, comment: null } })
  fetchMock.mockImplementation(() => respond(204))
  await feedbackApi.remove()
  expect(call()).toMatchObject({ url: `${BASE}/feedback/me`, method: 'DELETE' })
})

it('maps the account errors the server actually returns', () => {
  expect(accountErrorKey(new ApiError('/users/me/email', 400, { message: 'Current password is incorrect' }))).toBe(
    'profile.errors.incorrectPassword',
  )
  expect(accountErrorKey(new ApiError('/users/me/password', 400, { code: 'WEAK_PASSWORD' }))).toBe('errors.weakPassword')
  expect(accountErrorKey(new ApiError('/users/me/email', 409, { message: 'Email already registered' }))).toBe('errors.emailExists')
  expect(accountErrorKey(new ApiError('/users/me', null, null))).toBe('errors.network')
})
