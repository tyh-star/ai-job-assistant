import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiErrorBody } from './types'
import { loginPathAfterSessionExpiry, PROFILE_FEEDBACK_RETURN_TO } from '../auth/returnTo'
import { TOKEN_KEY, clearSession, markSessionExpired } from '../auth/session'
import {
  isPublicAuthEndpoint,
  resolveApiBase,
  stripApiV1Prefix,
} from './url'

export const API_BASE_URL = resolveApiBase(import.meta.env)

export { joinApiUrl, resolveApiBase, stripApiV1Prefix } from './url'

export const api = axios.create({
  baseURL: API_BASE_URL,
})

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (config.url && !/^https?:\/\//i.test(config.url)) {
    config.url = stripApiV1Prefix(config.url)
  }
  const requestUrl = `${config.baseURL ?? ''}${config.url ?? ''}`
  if (!isPublicAuthEndpoint(config.url ?? '') && !isPublicAuthEndpoint(requestUrl)) {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
})

// Pages that require an account -- a 401 here means THIS page's own token just went
// stale, so the visitor is sent to /login?returnTo=<this exact page>, never silently
// left on a page that can't actually do anything for them.
const PROTECTED_PATH_PREFIXES = ['/dashboard', '/resumes', '/profile', '/tailor']

export function isProtectedPath(pathname: string): boolean {
  return PROTECTED_PATH_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`))
}

/**
 * The specific /jobs actions that need an account (save, mark/clear applied, and the
 * personal-jobs list GET /jobs that backs the 已保存/已投递 tabs) -- as opposed to
 * search/detail/cities, which are public and must never be treated as a session
 * expiry just because a stale token happened to be attached to the request.
 */
export function isLoginRequiredJobsAction(requestUrl: string): boolean {
  const path = requestUrl.split('?')[0]
  return /^\/jobs\/?$/.test(path)
    || /^\/job-catalog\/[^/]+\/save\/?$/.test(path)
    || /^\/job-catalog\/[^/]+\/applied\/?$/.test(path)
}

/**
 * GET/PUT/DELETE /feedback/me are the only site-feedback calls that need an account.
 * GET /feedback/summary is public -- a 401 there (e.g. a revoked token attached to a
 * public browse) must never be treated as "this page requires login".
 */
export function isLoginRequiredFeedbackAction(requestUrl: string): boolean {
  const path = requestUrl.split('?')[0]
  return /^\/feedback\/me\/?$/.test(path)
}

// The only API routes the backend serves without an account (SecurityConfig permitAll):
// GET job-catalog search/detail/cities and GET feedback/summary. /auth/** is handled
// separately. Everything else needs a valid token.
const PUBLIC_GET_PATHS = [/^\/job-catalog\/search\/?$/, /^\/job-catalog\/[^/]+\/?$/, /^\/feedback\/summary\/?$/]

function apiPathOf(requestUrl: string): string {
  let path = requestUrl.split(/[?#]/)[0]
  if (/^https?:\/\//i.test(path)) {
    try {
      path = new URL(path).pathname
    } catch {
      return path
    }
  }
  return stripApiV1Prefix(path)
}

/** A request the backend would also answer for a visitor, so its 401 says nothing about this page. */
export function isPublicApiRequest(method: string | undefined, requestUrl: string): boolean {
  const path = apiPathOf(requestUrl)
  if (isPublicAuthEndpoint(path)) {
    return true
  }
  return (method ?? 'get').toLowerCase() === 'get' && PUBLIC_GET_PATHS.some((pattern) => pattern.test(path))
}

/**
 * Fired after any 401 clears the session, so AuthContext can flip its live `user`
 * state to signed-out even on paths where nothing below navigates away (this module
 * has no React context of its own to update directly). See AuthContext's listener.
 */
export const SESSION_EXPIRED_EVENT = 'aijob:session-expired'

/**
 * Exported as a plain function (rather than only living inline in the
 * `interceptors.response.use` call) so a test can invoke it directly with a
 * synthetic 401 -- provoking a REAL 401 through the full axios + MockMvc stack, just
 * to exercise this one handler, would be its own kind of over-elaborate test.
 *
 * Only 401 means "this token no longer works" (missing, malformed, expired or
 * revoked -- see the backend's RestSecurityErrorHandler). 403 is a real permission
 * error and never signs anyone out. For a 401:
 *   1. /auth/login or /auth/register's OWN 401 (wrong credentials) is not a session
 *      expiry -- skipped entirely, so the page shows the real message.
 *   2. Otherwise the token and cached user are cleared, the expiry is remembered for
 *      the login notice, and AuthContext is told.
 *   3. Already on /login or /register -- no navigation, so there is no redirect loop.
 *   4. On /profile, a rating-card call (/feedback/me) -- to /login, back to
 *      /profile#feedback.
 *   5. On a page that needs an account, or the request itself needed one (the signed-in
 *      dashboard at "/", saving a job on /jobs, ...) -- to /login with returnTo=<this
 *      page> and the session-expired notice.
 *   6. A public request on a public page (job search or detail) -- stay put, now as a
 *      visitor; that content never needed an account.
 */
export function handleResponseError(error: AxiosError<ApiErrorBody>): Promise<never> {
  if (error.response?.status !== 401) {
    return Promise.reject(error)
  }

  const requestUrl = error.config?.url ?? ''
  if (isPublicAuthEndpoint(requestUrl)) {
    return Promise.reject(error)
  }

  clearSession()
  markSessionExpired()
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))

  const pathname = window.location.pathname
  if (pathname === '/login' || pathname === '/register') {
    return Promise.reject(error)
  }

  // Must run before the generic protected-path branch, which would drop the #feedback
  // hash and land the visitor on the top of the profile instead of the rating card.
  if (pathname === '/profile' && isLoginRequiredFeedbackAction(requestUrl)) {
    window.location.assign(loginPathAfterSessionExpiry(PROFILE_FEEDBACK_RETURN_TO))
    return Promise.reject(error)
  }

  if (isProtectedPath(pathname) || !isPublicApiRequest(error.config?.method, requestUrl)) {
    window.location.assign(loginPathAfterSessionExpiry(`${pathname}${window.location.search}`))
  }

  return Promise.reject(error)
}

api.interceptors.response.use((response) => response, handleResponseError)

export function apiErrorKey(error: unknown): string {
  const axiosError = error as AxiosError<ApiErrorBody>
  if (!axiosError.response) {
    return 'errors.network'
  }
  const status = axiosError.response.status
  const message = axiosError.response.data?.message ?? ''
  if (status === 401) {
    return 'errors.unauthorized'
  }
  if (status === 403) {
    return 'errors.forbidden'
  }
  if (status === 404) {
    if (message.toLowerCase().includes('tailor proposal')) {
      return 'tailor.proposalExpired'
    }
    if (message.toLowerCase().includes('search session')) {
      return 'errors.jobSearchSessionNotFound'
    }
    // The upstream posting's OWN detail lookup 404'd (see
    // JobDetailUnavailableException) -- a distinct, stable code from this site's own
    // 404s ('errors.notFound' below), never conflated: retrying a save can never help
    // when the posting itself is gone, so this must never look like a generic failure.
    if (message.toUpperCase().includes('JOB_DETAIL_NO_LONGER_AVAILABLE')) {
      return 'errors.jobDetailUnavailable'
    }
    return 'errors.notFound'
  }
  if (status === 410) {
    return 'errors.jobSearchSessionExpired'
  }
    if (status === 409) {
    if (message.toLowerCase().includes('email')) {
      return 'errors.emailExists'
    }
    if (message.toLowerCase().includes('search page')) {
      return 'errors.jobSearchPageConflict'
    }
    if (message.toLowerCase().includes('changed elsewhere')) {
      return 'resumeBuilder.saveConflict'
    }
    if (
      message.toLowerCase().includes('already saved evidence')
      || message.toLowerCase().includes('delete the existing evidence')
    ) {
      return 'tailor.evidenceConflict'
    }
    if (message.toLowerCase().includes('already being confirmed')) {
      return 'tailor.confirmInProgress'
    }
    if (
      message.toLowerCase().includes('no longer pending')
      || message.toLowerCase().includes('expired')
    ) {
      return 'tailor.proposalExpired'
    }
    return 'errors.conflict'
  }
  if (status === 400) {
    const code = axiosError.response.data?.code
    if (code === 'WEAK_PASSWORD') {
      return 'errors.weakPassword'
    }
    const lower = message.toLowerCase()
    if (lower.includes('current password is incorrect')) {
      return 'errors.incorrectPassword'
    }
    if (lower.includes('location is not supported')) {
      return 'errors.jobCatalogLocationUnsupported'
    }
    if (lower.includes('city and country cannot both be set')) {
      return 'errors.jobCatalogCityCountryConflict'
    }
    if (lower.includes('employmenttype') && lower.includes('is not supported')) {
      return 'errors.jobCatalogEmploymentTypeUnsupported'
    }
    if (lower.includes('keywords')) {
      return 'errors.jobSearchKeywordsRequired'
    }
    if (
      lower.includes('select at least')
      || lower.includes('at most 20')
    ) {
      return 'errors.jobSearchImportSelectionInvalid'
    }
    if (
      lower.includes('yes/no')
      || lower.includes('actual maintenance')
      || lower.includes('not enough')
      || lower.includes('answertext is required')
    ) {
      return 'tailor.needRealDetails'
    }
    if (
      lower.includes('snippet')
      || lower.includes('full job description')
      || lower.includes('fulldescription')
    ) {
      return 'errors.jobSearchFullDescriptionRequired'
    }
    if (lower.includes('add at least a name') || lower.includes('way to contact you')) {
      return 'resumeBuilder.notReadyToGenerate'
    }
    return 'errors.badRequest'
  }
  if (status === 429) {
    return 'errors.jobSearchRateLimited'
  }
  if (status === 504) {
    return 'errors.jobSearchTimeout'
  }
  if (status === 502) {
    const lower = message.toLowerCase()
    if (lower.includes('job search')) {
      return 'errors.jobSearchUnavailable'
    }
    if (lower.includes('greenhouse')) {
      return 'errors.greenhouseUnavailable'
    }
    return 'errors.aiUnavailable'
  }
  return 'errors.requestFailed'
}

export async function readApiErrorMessage(error: unknown): Promise<string> {
  const axiosError = error as AxiosError<ApiErrorBody | Blob>
  const data = axiosError.response?.data
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const parsed = JSON.parse(await data.text()) as ApiErrorBody
      if (parsed?.message) {
        return parsed.message
      }
    } catch {
      /* ignore non-JSON blob */
    }
  } else if (data && typeof data === 'object' && 'message' in data) {
    const message = (data as ApiErrorBody).message
    if (message) {
      return message
    }
  }
  return ''
}
