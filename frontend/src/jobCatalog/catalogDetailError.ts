import type { AxiosError } from 'axios'

export type CatalogDetailLoadError = {
  kind: 'gone' | 'rateLimited' | 'unavailable'
  retryAfter?: string
}

function headerValue(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== 'object') {
    return undefined
  }
  const record = headers as Record<string, unknown>
  const direct = record[name] ?? record[name.toLowerCase()] ?? record['Retry-After']
  if (typeof direct === 'string' && direct.trim()) {
    return direct.trim()
  }
  if (Array.isArray(direct) && typeof direct[0] === 'string' && direct[0].trim()) {
    return direct[0].trim()
  }
  return undefined
}

export function catalogDetailLoadError(error: unknown): CatalogDetailLoadError {
  const axiosError = error as AxiosError
  const status = axiosError.response?.status
  if (status === 404) {
    return { kind: 'gone' }
  }
  if (status === 429) {
    return { kind: 'rateLimited', retryAfter: headerValue(axiosError.response?.headers, 'retry-after') }
  }
  return { kind: 'unavailable' }
}

/** Only http(s) listing URLs are offered; javascript: and other schemes are dropped. */
export function safeHttpUrl(url: string | null | undefined): string | null {
  if (!url) {
    return null
  }
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'https:' || parsed.protocol === 'http:') {
      return parsed.href
    }
  } catch {
    /* reject unparseable or relative junk */
  }
  return null
}
