import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiErrorBody } from './types'
import { loginPathAfterSessionExpiry } from '../auth/returnTo'
import { TOKEN_KEY, clearSession } from '../auth/session'
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
 * A 401 is not one uniform event -- where it happened and what it was FOR decide
 * what happens next:
 *   1. Merely browsing a public page (/jobs search/detail/cities, or "/", which is
 *      never rendered itself) -- clear the stale token and do nothing else. The
 *      visitor stays exactly where they are, now anonymous; content that never
 *      needed login keeps working.
 *   2. A protected page's own token went stale -- to /login?returnTo=<that exact
 *      page>, so logging back in returns them there.
 *   3. Still on /jobs, but the specific action just clicked (save / mark applied /
 *      the saved-applied tabs) needed login and the token turned out stale -- same
 *      redirect-with-return as #2, always back to /jobs, with an honest notice.
 *   4. /auth/login or /auth/register's OWN 401 (wrong credentials) is not a session
 *      expiry at all -- skipped entirely, so the page's own error handling shows the
 *      real message instead of this being mistaken for an expired session.
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
  window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))

  const pathname = window.location.pathname
  if (isProtectedPath(pathname)) {
    window.location.assign(loginPathAfterSessionExpiry(`${pathname}${window.location.search}`))
    return Promise.reject(error)
  }

  if ((pathname === '/jobs' || pathname === '/') && isLoginRequiredJobsAction(requestUrl)) {
    window.location.assign(loginPathAfterSessionExpiry('/jobs'))
    return Promise.reject(error)
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
