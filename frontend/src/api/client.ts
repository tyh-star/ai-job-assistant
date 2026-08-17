import axios, { type AxiosError } from 'axios'
import type { ApiErrorBody } from './types'
import { TOKEN_KEY, clearSession } from '../auth/session'

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ||
  'http://localhost:8080/api/v1'

export const api = axios.create({
  baseURL: API_BASE_URL,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
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
    return 'errors.notFound'
  }
  if (status === 409) {
    return message.toLowerCase().includes('email')
      ? 'errors.emailExists'
      : 'errors.conflict'
  }
  if (status === 400) {
    return 'errors.badRequest'
  }
  if (status === 502) {
    const lower = message.toLowerCase()
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
