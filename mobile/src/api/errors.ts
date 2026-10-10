import { ApiError } from './client'

/** The backend's stable code for a catalog posting whose upstream detail is gone. */
export const JOB_DETAIL_GONE_CODE = 'JOB_DETAIL_NO_LONGER_AVAILABLE'

/** i18n key for a failed request, mirroring the website's wording where it applies. */
export function apiErrorKey(error: unknown): string {
  if (!(error instanceof ApiError) || error.status === null) {
    return 'errors.network'
  }
  const message = (error.body?.message ?? '').toLowerCase()
  switch (error.status) {
    case 400:
      return error.body?.code === 'WEAK_PASSWORD' ? 'errors.weakPassword' : 'errors.badRequest'
    case 401:
      return 'errors.unauthorized'
    case 403:
      return 'errors.forbidden'
    case 404:
      return isDetailGone(error) ? 'errors.detailGone' : 'errors.notFound'
    case 409:
      return message.includes('email') ? 'errors.emailExists' : 'errors.conflict'
    case 429:
      return 'errors.rateLimited'
    case 502:
    case 503:
    case 504:
      return 'errors.serviceUnavailable'
    default:
      return 'errors.requestFailed'
  }
}

export function isDetailGone(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    (error.body?.message ?? '').toUpperCase().includes(JOB_DETAIL_GONE_CODE)
  )
}

/**
 * "HTTP 500 · /resume-builder/drafts": what the phone received, for telling a server
 * problem apart from a network one. Status and path only -- never the body, the query
 * string or any header.
 */
export function requestDiagnostic(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status === null) {
    return null
  }
  return `HTTP ${error.status} · ${error.path.split('?')[0]}`
}

/**
 * A route the deployed server may not have: Spring answers an unknown route with the
 * catch-all 500 ("Internal server error"), or 404/405 depending on the version.
 */
export function mayBeMissingOnServer(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 405 || error.status === 500)
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

/** A failed resume upload, worded by what the server actually rejected. */
export function uploadErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status !== null) {
    const message = (error.body?.message ?? '').toLowerCase()
    if (error.status === 413 || message.includes('maximum upload size') || message.includes('size exceeded')) {
      return 'resumes.problem.tooLarge'
    }
    if (error.status === 400) {
      if (message.includes('only pdf and docx')) {
        return 'resumes.problem.badType'
      }
      if (message.includes('cannot be empty')) {
        return 'resumes.problem.empty'
      }
      if (message.includes('file name is required')) {
        return 'resumes.problem.noName'
      }
    }
    if (error.status === 422) {
      return 'resumes.errors.unreadable'
    }
  }
  return apiErrorKey(error)
}

/** Account and feedback changes, worded by what the server actually rejected. */
export function accountErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    const message = (error.body?.message ?? '').toLowerCase()
    if (error.body?.code === 'WEAK_PASSWORD') {
      return 'errors.weakPassword'
    }
    if (message.includes('current password is incorrect')) {
      return 'profile.errors.incorrectPassword'
    }
  }
  return apiErrorKey(error)
}

/** Resume-builder saves and generation, worded by what the server actually rejected. */
export function builderErrorKey(error: unknown): string {
  if (error instanceof ApiError) {
    const message = (error.body?.message ?? '').toLowerCase()
    if (error.status === 409) {
      return 'builder.errors.conflict'
    }
    if (error.status === 404) {
      return 'builder.errors.draftGone'
    }
    if (error.status === 400) {
      if (message.includes('add at least a name') || message.includes('way to contact you')) {
        return 'builder.errors.notReady'
      }
      return 'builder.errors.invalid'
    }
    if (error.status === 500) {
      return 'builder.errors.generateFailed'
    }
  }
  return apiErrorKey(error)
}

/** Login's own 401 means wrong credentials, never an expired session. */
export function loginErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return 'auth.invalidCredentials'
  }
  return apiErrorKey(error)
}
