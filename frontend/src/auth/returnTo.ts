/**
 * The `?returnTo=` query param that carries a visitor back to whatever protected page
 * they were trying to reach before /login. Centralized here so every place that reads
 * or writes it agrees on the same safety rule.
 */

const DEFAULT_RETURN_TO = '/dashboard'

/** The profile card that holds site ratings. Old /feedback URLs redirect here. */
export const PROFILE_FEEDBACK_RETURN_TO = '/profile#feedback'

// Built via `new RegExp` from an escaped string, rather than a `/.../` literal, so the
// control-character escapes stay literal source text rather than risking being turned
// into the actual control byte by some tool in the chain -- which would be invisible
// in a diff and indistinguishable from a harmless space at a glance.
const CONTROL_CHAR_PATTERN = new RegExp('[\\x00-\\x1f\\x7f]')

export const LOGIN_REASON_REQUIRED = 'login_required'
export const LOGIN_REASON_EXPIRED = 'session_expired'

/** Builds the `/login` path a protected route redirects an anonymous visitor to. */
export function loginPathWithReturnTo(currentPath: string, reason?: string): string {
  if (!currentPath || currentPath === '/login' || currentPath.startsWith('/login?')) {
    return reason ? `/login?reason=${encodeURIComponent(reason)}` : '/login'
  }
  let path = `/login?returnTo=${encodeURIComponent(currentPath)}`
  if (reason) {
    path += `&reason=${encodeURIComponent(reason)}`
  }
  return path
}

/** First-time anonymous click on a signed-in feature -- never "session expired". */
export function loginPathForAnonymousFeature(currentPath: string): string {
  return loginPathWithReturnTo(currentPath, LOGIN_REASON_REQUIRED)
}

/**
 * Same as {@link loginPathWithReturnTo}, for the axios interceptor's OWN forced
 * redirect after a 401 proves the session is actually gone (see api/client.ts) --
 * distinct from an anonymous visitor merely clicking a protected feature for the
 * first time, so it carries an explicit reason the login page surfaces as an honest
 * notice rather than a blank form.
 */
export function loginPathAfterSessionExpiry(currentPath: string): string {
  return loginPathWithReturnTo(currentPath, LOGIN_REASON_EXPIRED)
}

function isSafeRelativePath(candidate: string): boolean {
  if (CONTROL_CHAR_PATTERN.test(candidate)) {
    return false
  }
  if (candidate.includes('\\')) {
    // Browsers normalize a backslash to a forward slash while parsing a URL, so
    // "/\evil.com" parses identically to "//evil.com" even though the raw string
    // itself doesn't start with "//" -- reject any backslash outright rather than
    // trying to enumerate every way it can combine with a leading slash.
    return false
  }
  if (!candidate.startsWith('/') || candidate.startsWith('//')) {
    // Not a same-site relative path (bare "evil.com"), or scheme-relative
    // ("//evil.com", which a browser resolves against its OWN protocol, not this
    // origin) -- both become an off-site redirect once handed to Navigate/navigate().
    return false
  }
  if (/:\/\//.test(candidate) || /^\/[a-z][a-z0-9+.-]*:/i.test(candidate)) {
    // An embedded absolute URL/scheme ("/http://evil.com", "/javascript:...") --
    // still passes startsWith('/') but is not actually a same-site path.
    return false
  }
  return true
}

/**
 * Validates a `returnTo` value read back off the URL before ever passing it to
 * `navigate()`/`<Navigate>`. Must be same-site and relative -- never a scheme
 * (`https://evil.com`), never scheme-relative (`//evil.com`), never a backslash path,
 * and never a control character, any of which would turn a login redirect into an
 * open redirect off this site or a request-smuggling-style trick.
 */
export function sanitizeReturnTo(raw: string | null | undefined): string {
  if (!raw) {
    return DEFAULT_RETURN_TO
  }
  if (!isSafeRelativePath(raw)) {
    return DEFAULT_RETURN_TO
  }
  // Defends against a SECOND layer of percent-encoding surviving the one decode
  // URLSearchParams already performed (e.g. raw="%2F%2Fevil.com" looks like a
  // harmless relative path here, but decodes to "//evil.com" if anything downstream
  // ever decodes it again) -- decode once more and re-validate; malformed encoding is
  // rejected rather than guessed at.
  let decodedAgain: string
  try {
    decodedAgain = decodeURIComponent(raw)
  } catch {
    return DEFAULT_RETURN_TO
  }
  if (decodedAgain !== raw && !isSafeRelativePath(decodedAgain)) {
    return DEFAULT_RETURN_TO
  }
  // Going "back" to the login or register page after logging in would bounce between
  // the two forever; send the user to the default page instead.
  const target = decodedAgain.split(/[?#]/)[0].replace(/\/+$/, '')
  if (target === '/login' || target === '/register') {
    return DEFAULT_RETURN_TO
  }
  return raw
}
