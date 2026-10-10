import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import { SESSION_EXPIRED_EVENT } from '../api/client'
import { AuthProvider, useAuth } from './AuthContext'
import {
  clearSession,
  clearSessionExpiredMark,
  getToken,
  hasSessionExpiredMark,
  isTokenExpired,
  saveSession,
} from './session'

function renderAuth() {
  return renderHook(() => useAuth(), { wrapper: AuthProvider })
}

describe('AuthContext session-expiry listener', () => {
  beforeEach(() => {
    saveSession('stale-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
  })

  it('starts authenticated from a saved session', () => {
    const { result } = renderAuth()
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.user?.name).toBe('Alice')
  })

  it('flips to signed-out the moment the axios interceptor reports a session expiry -- no reload needed', () => {
    const { result } = renderAuth()
    expect(result.current.isAuthenticated).toBe(true)

    // This is exactly what handleResponseError in api/client.ts dispatches after a
    // 401 -- clearSession() already happened there; this proves the LIVE React state
    // (and therefore the nav bar, and every isAuthenticated gate) follows it without
    // requiring a page navigation to reconcile.
    act(() => {
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
    })

    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.user).toBeNull()
  })

  it('leaves localStorage as the real caller (handleResponseError) leaves it: cleared BEFORE the event fires', () => {
    const { result } = renderAuth()
    // Mirrors api/client.ts's real order of operations: clearSession() happens first,
    // then the event fires. This context's own listener only touches React state --
    // it must never need to clear storage itself, and must never re-save anything.
    act(() => {
      clearSession()
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
    })
    expect(result.current.isAuthenticated).toBe(false)
    expect(getToken()).toBeNull()
  })
})

function jwtWithExp(expSeconds: number) {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${encode({ alg: 'HS256' })}.${encode({ sub: 'alice@example.com', exp: expSeconds })}.signature`
}

describe('AuthContext startup with a stored JWT', () => {
  afterEach(() => {
    cleanup()
    localStorage.clear()
    clearSessionExpiredMark()
  })

  it('drops an already expired token before the cached name is ever shown', () => {
    saveSession(jwtWithExp(Math.floor(Date.now() / 1000) - 60), { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    const { result } = renderAuth()
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.user).toBeNull()
    expect(getToken()).toBeNull()
    expect(localStorage.getItem('aijob.user')).toBeNull()
    expect(hasSessionExpiredMark()).toBe(true)
  })

  it('keeps a token that has not expired', () => {
    saveSession(jwtWithExp(Math.floor(Date.now() / 1000) + 3600), { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    const { result } = renderAuth()
    expect(result.current.user?.name).toBe('Alice')
    expect(hasSessionExpiredMark()).toBe(false)
  })
})

describe('isTokenExpired', () => {
  it('reads exp and leaves unreadable tokens to the server', () => {
    expect(isTokenExpired(jwtWithExp(100), 101_000)).toBe(true)
    expect(isTokenExpired(jwtWithExp(100), 99_000)).toBe(false)
    expect(isTokenExpired('stale-token')).toBe(false)
    expect(isTokenExpired('a.%%%.c')).toBe(false)
  })
})
