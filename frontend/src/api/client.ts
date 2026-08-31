import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiErrorBody } from './types'
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

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorBody>) => {
    if (error.response?.status === 401) {
      clearSession()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.assign('/login')
      }
    }
    return Promise.reject(error)
  },
)

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
