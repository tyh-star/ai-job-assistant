/** Same-origin API prefix used in production (Caddy → backend, no strip). */
export const API_PREFIX = '/api/v1'

export const PRODUCTION_API_BASE = '/api/v1'

export type ViteEnvLike = {
  VITE_API_BASE_URL?: string
  DEV?: boolean
  PROD?: boolean
  MODE?: string
}

/**
 * Scheme A: axios baseURL is `/api/v1` (or an override). Endpoints are relative
 * (`/auth/register`). Never default to a loopback backend URL so a missed Docker ARG
 * cannot bake that host into the public bundle.
 */
export function resolveApiBase(env: ViteEnvLike = {}): string {
  const fromEnv = env.VITE_API_BASE_URL?.trim()
  if (fromEnv) {
    return fromEnv.replace(/\/+$/, '')
  }
  return PRODUCTION_API_BASE
}

export function stripApiV1Prefix(path: string): string {
  let value = path.trim()
  if (/^https?:\/\//i.test(value)) {
    try {
      const url = new URL(value)
      value = `${url.pathname}${url.search}`
    } catch {
      const index = value.indexOf(API_PREFIX)
      value = index >= 0 ? value.slice(index + API_PREFIX.length) || '/' : value
    }
  }
  while (value === API_PREFIX || value.startsWith(`${API_PREFIX}/`)) {
    value = value === API_PREFIX ? '/' : value.slice(API_PREFIX.length)
  }
  if (!value.startsWith('/')) {
    value = `/${value}`
  }
  return value
}

export function joinApiUrl(base: string, endpoint: string): string {
  if (/^https?:\/\//i.test(endpoint)) {
    return endpoint
  }
  const relative = stripApiV1Prefix(endpoint)
  const normalizedBase = base.replace(/\/+$/, '')
  if (relative === '/') {
    return normalizedBase
  }
  return `${normalizedBase}${relative}`
}

export function isPublicAuthEndpoint(url: string): boolean {
  const path = url.split('?')[0].replace(/\/+$/, '')
  return /(^|\/)auth\/(login|register)$/.test(path)
}

export function hasDuplicateApiPrefix(url: string): boolean {
  return /\/api\/v1\/api\/v1(?:\/|$)/.test(url)
}
