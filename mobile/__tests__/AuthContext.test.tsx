import type { ReactNode } from 'react'
import { act, renderHook, waitFor } from '@testing-library/react-native'
import * as SecureStore from 'expo-secure-store'
import { apiRequest } from '../src/api/client'
import { AuthProvider, SessionStoreError, useAuth } from '../src/auth/AuthContext'

const BASE = 'http://api.test/api/v1'

jest.mock('../src/config/apiBase', () => ({ resolveApiBase: () => 'http://api.test/api/v1' }))

const store = SecureStore as unknown as {
  __reset: () => void
  getItemAsync: jest.Mock
  setItemAsync: jest.Mock
}

function json(status: number, body?: unknown) {
  return Promise.resolve(
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
}

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>
}

const fetchMock = jest.fn()
const USER = { id: 5, name: 'nb', email: 'nb@example.test', role: 'USER', createdAt: '2026-09-01T00:00:00' }

async function signedIn() {
  const hook = await renderHook(() => useAuth(), { wrapper })
  await waitFor(() => expect(hook.result.current.status).toBe('signedOut'))
  fetchMock.mockReturnValueOnce(json(200, { token: 'jwt-1', userId: 5, name: 'nb', email: 'nb@example.test', role: 'USER' }))
  await act(async () => {
    await hook.result.current.login('nb@example.test', 'Secret#123')
  })
  expect(hook.result.current.status).toBe('signedIn')
  return hook
}

function lastRequest() {
  const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1]
  return { url, method: init.method, auth: init.headers.Authorization, body: init.body ? JSON.parse(init.body) : undefined }
}

beforeEach(() => {
  store.__reset()
  jest.clearAllMocks()
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

it('stores the token in SecureStore after login and signs out on a later 401', async () => {
  const { result } = await signedIn()
  expect(await SecureStore.getItemAsync('aijob.token')).toBe('jwt-1')

  fetchMock.mockReturnValueOnce(json(401, { message: 'Token revoked' }))
  await act(async () => {
    await apiRequest('/jobs').catch(() => undefined)
  })
  expect(lastRequest()).toMatchObject({ url: `${BASE}/jobs`, auth: 'Bearer jwt-1' })
  await waitFor(() => expect(result.current.status).toBe('signedOut'))
  expect(result.current.signOutReason).toBe('expired')
  expect(await SecureStore.getItemAsync('aijob.token')).toBeNull()
})

it('restores a saved session on launch', async () => {
  await SecureStore.setItemAsync('aijob.token', 'jwt-saved')
  await SecureStore.setItemAsync('aijob.user', JSON.stringify({ userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' }))
  const { result } = await renderHook(() => useAuth(), { wrapper })
  await waitFor(() => expect(result.current.status).toBe('signedIn'))
  expect(result.current.user?.name).toBe('nb')
})

it('logs in right after registering, since register returns no token', async () => {
  const { result } = await renderHook(() => useAuth(), { wrapper })
  await waitFor(() => expect(result.current.status).toBe('signedOut'))
  fetchMock
    .mockReturnValueOnce(json(201, { id: 9, name: 'New', email: 'new@example.test', role: 'USER', createdAt: '' }))
    .mockReturnValueOnce(json(200, { token: 'jwt-new', userId: 9, name: 'New', email: 'new@example.test', role: 'USER' }))
  await act(async () => {
    await result.current.register('New', 'new@example.test', 'Secret#123')
  })
  expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([`${BASE}/auth/register`, `${BASE}/auth/login`])
  expect(result.current.status).toBe('signedIn')
})

describe('name', () => {
  it('updates the user everywhere it is read, including the stored session', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(200, { ...USER, name: 'Renamed' }))
    await act(async () => {
      await result.current.updateName('Renamed')
    })
    expect(lastRequest()).toMatchObject({ url: `${BASE}/users/me/name`, method: 'PUT', body: { name: 'Renamed' } })
    expect(result.current.user?.name).toBe('Renamed')
    expect(JSON.parse((await SecureStore.getItemAsync('aijob.user')) as string).name).toBe('Renamed')
  })

  it('keeps the old name when the server refuses', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(400, { message: 'Name cannot exceed 100 characters' }))
    await act(async () => {
      await expect(result.current.updateName('x'.repeat(101))).rejects.toMatchObject({ status: 400 })
    })
    expect(result.current.user?.name).toBe('nb')
  })
})

describe('email', () => {
  it('stores the new token before using it, and ignores a 401 that answers the old token', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(200, { token: 'jwt-2', user: { ...USER, email: 'new@example.test' } }))
    await act(async () => {
      await result.current.updateEmail('Secret#123', 'new@example.test')
    })
    expect(lastRequest()).toMatchObject({
      url: `${BASE}/users/me/email`,
      method: 'PUT',
      auth: 'Bearer jwt-1',
      body: { currentPassword: 'Secret#123', newEmail: 'new@example.test' },
    })
    expect(await SecureStore.getItemAsync('aijob.token')).toBe('jwt-2')
    const tokenWrite = store.setItemAsync.mock.calls.findIndex(([key, value]) => key === 'aijob.token' && value === 'jwt-2')
    expect(tokenWrite).toBeGreaterThanOrEqual(0)
    expect(result.current.user?.email).toBe('new@example.test')

    // A slow request that went out with the old token comes back 401: not a session expiry.
    fetchMock.mockReturnValueOnce(json(200, USER))
    await act(async () => {
      await apiRequest('/users/me')
    })
    expect(lastRequest().auth).toBe('Bearer jwt-2')
  })

  it('signs out honestly when the new token cannot be stored', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(200, { token: 'jwt-2', user: { ...USER, email: 'new@example.test' } }))
    store.setItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'))
    await act(async () => {
      await expect(result.current.updateEmail('Secret#123', 'new@example.test')).rejects.toBeInstanceOf(SessionStoreError)
    })
    expect(result.current.status).toBe('signedOut')
    expect(result.current.signOutReason).toBe('sessionStoreFailed')
  })

  it('stays signed in with the old token when the password is wrong', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(400, { message: 'Current password is incorrect' }))
    await act(async () => {
      await expect(result.current.updateEmail('wrong', 'new@example.test')).rejects.toMatchObject({ status: 400 })
    })
    expect(result.current.status).toBe('signedIn')
    expect(await SecureStore.getItemAsync('aijob.token')).toBe('jwt-1')
  })
})

describe('password', () => {
  it('signs out and clears the stored session after a successful change', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(204))
    await act(async () => {
      await result.current.changePassword('Secret#123', 'Newer#4567')
    })
    expect(lastRequest()).toMatchObject({
      url: `${BASE}/users/me/password`,
      method: 'PUT',
      body: { currentPassword: 'Secret#123', newPassword: 'Newer#4567' },
    })
    expect(result.current.status).toBe('signedOut')
    expect(result.current.signOutReason).toBe('passwordChanged')
    expect(await SecureStore.getItemAsync('aijob.token')).toBeNull()
  })

  it('does not treat a rejected change as a sign-out', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(400, { code: 'WEAK_PASSWORD', message: 'Password is too weak' }))
    await act(async () => {
      await expect(result.current.changePassword('Secret#123', 'weak')).rejects.toMatchObject({ status: 400 })
    })
    expect(result.current.status).toBe('signedIn')
    expect(result.current.signOutReason).toBeNull()
  })
})

describe('account deletion', () => {
  it('signs out only after the server confirms, and keeps the account on failure', async () => {
    const { result } = await signedIn()
    fetchMock.mockReturnValueOnce(json(500, { message: 'boom' }))
    await act(async () => {
      await expect(result.current.deleteAccount()).rejects.toMatchObject({ status: 500 })
    })
    expect(result.current.status).toBe('signedIn')

    fetchMock.mockReturnValueOnce(json(204))
    await act(async () => {
      await result.current.deleteAccount()
    })
    expect(lastRequest()).toMatchObject({ url: `${BASE}/users/me`, method: 'DELETE' })
    expect(result.current.status).toBe('signedOut')
    expect(result.current.signOutReason).toBe('accountDeleted')
  })
})
