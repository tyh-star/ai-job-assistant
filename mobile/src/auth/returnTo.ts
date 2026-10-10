import type { SignOutReason } from './AuthContext'

/**
 * Where a visitor goes after logging in. Only same-app paths are accepted -- never a
 * scheme, a scheme-relative path, a backslash, a control character or the auth screens
 * themselves -- so a crafted link cannot send the app somewhere else. Same rules as the
 * website's sanitizeReturnTo.
 */
export const SIGNED_IN_HOME = '/dashboard'

const CONTROL_CHARS = new RegExp('[\\x00-\\x1f\\x7f]')
const NOT_A_TARGET = ['/login', '/register', '/welcome']

function isSafePath(value: string): boolean {
  return (
    !CONTROL_CHARS.test(value) &&
    !value.includes('\\') &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !/:\/\//.test(value) &&
    !/^\/[a-z][a-z0-9+.-]*:/i.test(value)
  )
}

export function sanitizeReturnTo(raw: unknown): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || !value || !isSafePath(value)) {
    return null
  }
  let decoded: string
  try {
    decoded = decodeURIComponent(value)
  } catch {
    return null
  }
  if (decoded !== value && !isSafePath(decoded)) {
    return null
  }
  const path = value.split('?')[0]
  if (NOT_A_TARGET.some((blocked) => path === blocked || path.startsWith(`${blocked}/`))) {
    return null
  }
  return value
}

/** The login screen, carrying where to continue afterwards. */
export function loginHref(returnTo?: string | null): string {
  const safe = sanitizeReturnTo(returnTo)
  return safe ? `/login?returnTo=${encodeURIComponent(safe)}` : '/login'
}

export function afterLoginTarget(raw: unknown): string {
  return sanitizeReturnTo(raw) ?? SIGNED_IN_HOME
}

/**
 * A signed-in-only screen with nobody signed in. An expired session returns to the same
 * screen after logging in again; the app signing out for another reason shows its notice
 * on the login screen; a plain logout (or a visitor) lands on the public welcome page.
 */
export function signedOutRedirect(reason: SignOutReason | null, pathname: string): string {
  if (reason === 'expired') {
    return loginHref(pathname)
  }
  if (reason) {
    return '/login'
  }
  return '/welcome'
}
