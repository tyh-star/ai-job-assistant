import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import { SESSION_EXPIRED_EVENT } from '../api/client'
import { AuthProvider, useAuth } from './AuthContext'
import { clearSession, getToken, saveSession } from './session'

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
