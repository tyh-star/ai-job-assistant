import type { StoredUser } from '../api/types'

export const TOKEN_KEY = 'aijob.token'
export const USER_KEY = 'aijob.user'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as StoredUser
  } catch {
    return null
  }
}

export function saveSession(token: string, user: StoredUser): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * Set when a session ended because the token stopped working (401, or an expired JWT
 * found at startup), so the next login redirect says "登录状态已过期" rather than the
 * first-visit "登录后即可使用". Per tab; cleared by a successful login or a logout.
 */
export const SESSION_EXPIRED_MARK_KEY = 'aijob.sessionExpired'

export function markSessionExpired(): void {
  try {
    sessionStorage.setItem(SESSION_EXPIRED_MARK_KEY, '1')
  } catch {
    // storage unavailable: the notice is only a nicety
  }
}

export function hasSessionExpiredMark(): boolean {
  try {
    return sessionStorage.getItem(SESSION_EXPIRED_MARK_KEY) === '1'
  } catch {
    return false
  }
}

export function clearSessionExpiredMark(): void {
  try {
    sessionStorage.removeItem(SESSION_EXPIRED_MARK_KEY)
  } catch {
    // ignore
  }
}

/**
 * True only for a readable JWT whose `exp` is in the past. Anything that cannot be read
 * is left to the server, which answers 401 for it.
 */
export function isTokenExpired(token: string, nowMs: number = Date.now()): boolean {
  const parts = token.split('.')
  if (parts.length !== 3) {
    return false
  }
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const payload = JSON.parse(atob(padded)) as { exp?: unknown }
    return typeof payload.exp === 'number' && payload.exp * 1000 <= nowMs
  } catch {
    return false
  }
}
