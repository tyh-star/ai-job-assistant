/**
 * The `?returnTo=` query param that carries a visitor back to whatever protected page
 * they were trying to reach before /login. Centralized here so every place that reads
 * or writes it agrees on the same safety rule.
 */

// "/" is always just a redirect to /jobs (see App.tsx) -- defaulting straight to
// /jobs avoids that extra hop rather than changing the destination.
const DEFAULT_RETURN_TO = '/jobs'

// Built via `new RegExp` from an escaped string, rather than a `/.../` literal, so the
// control-character escapes stay literal source text rather than risking being turned
// into the actual control byte by some tool in the chain -- which would be invisible
// in a diff and indistinguishable from a harmless space at a glance.
const CONTROL_CHAR_PATTERN = new RegExp('[\\x00-\\x1f\\x7f]')

/** Builds the `/login` path a protected route redirects an anonymous visitor to. */
export function loginPathWithReturnTo(currentPath: string): string {
  if (!currentPath || currentPath === '/login' || currentPath.startsWith('/login?')) {
    return '/login'
  }
  return `/login?returnTo=${encodeURIComponent(currentPath)}`
}

/**
 * Same as {@link loginPathWithReturnTo}, for the axios interceptor's OWN forced
 * redirect after a 401 proves the session is actually gone (see api/client.ts) --
 * distinct from an anonymous visitor merely clicking a protected feature for the
 * first time, so it carries an explicit reason the login page surfaces as an honest
 * notice rather than a blank form.
 */
export function loginPathAfterSessionExpiry(currentPath: string): string {
  const base = loginPathWithReturnTo(currentPath)
  const separator = base.includes('?') ? '&' : '?'
  return `${base}${separator}reason=session_expired`
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
  return raw
}
