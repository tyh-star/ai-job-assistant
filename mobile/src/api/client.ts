import { resolveApiBase } from '../config/apiBase'
import type { ApiErrorBody } from './types'

const REQUEST_TIMEOUT_MS = 20_000
const UPLOAD_TIMEOUT_MS = 120_000

export class ApiError extends Error {
  /** null when no HTTP response arrived (offline, timeout, bad base URL). */
  readonly status: number | null
  readonly body: ApiErrorBody | null
  readonly path: string

  constructor(path: string, status: number | null, body: ApiErrorBody | null, message?: string) {
    super(message ?? body?.message ?? `Request failed: ${path}`)
    this.name = 'ApiError'
    this.path = path
    this.status = status
    this.body = body
  }
}

type ClientHooks = {
  getToken: () => string | null
  /**
   * Called once per 401 on an authenticated request, with the token that request
   * carried, so a response to an already-replaced token can be ignored. Never called
   * for login/register.
   */
  onUnauthorized: (tokenUsed: string) => void
}

let hooks: ClientHooks = {
  getToken: () => null,
  onUnauthorized: () => {},
}

export function configureApiClient(next: ClientHooks): void {
  hooks = next
}

export function isPublicAuthPath(path: string): boolean {
  return /^\/auth\/(login|register)\/?$/.test(path.split('?')[0])
}

export type QueryValue = string | number | boolean | null | undefined

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** Blank, null and undefined values are left out of the query string. */
  query?: Record<string, QueryValue>
  baseUrl?: string | null
  /** For server work that takes minutes, such as AI tailoring. Defaults to 20 seconds. */
  timeoutMs?: number
}

export function buildQueryString(query: Record<string, QueryValue> | undefined): string {
  if (!query) {
    return ''
  }
  const parts = Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

function requireBase(path: string, baseUrl: string | null | undefined): string {
  const base = baseUrl === undefined ? resolveApiBase() : baseUrl
  if (!base) {
    throw new ApiError(path, null, null, 'API base URL is not configured')
  }
  return base
}

function authHeaders(path: string): { headers: Record<string, string>; token: string | null } {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = isPublicAuthPath(path) ? null : hooks.getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return { headers, token }
}

function parseBody(text: string): unknown {
  if (!text) {
    return undefined
  }
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/** Shared failure path: a 401 on an authenticated call signs the user out. */
function failure(path: string, status: number, payload: unknown, token: string | null): ApiError {
  if (status === 401 && token && !isPublicAuthPath(path)) {
    hooks.onUnauthorized(token)
  }
  const body = payload && typeof payload === 'object' ? (payload as ApiErrorBody) : null
  return new ApiError(path, status, body)
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const base = requireBase(path, options.baseUrl)
  const { headers, token } = authHeaders(path)
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), options.timeoutMs ?? REQUEST_TIMEOUT_MS)
  let response: Response
  try {
    response = await fetch(`${base}${path}${buildQueryString(options.query)}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    })
  } catch (cause) {
    throw new ApiError(path, null, null, cause instanceof Error ? cause.message : 'Network error')
  } finally {
    clearTimeout(timer)
  }

  const payload = response.status === 204 ? undefined : parseBody(await response.text())
  if (!response.ok) {
    throw failure(path, response.status, payload, token)
  }
  return payload as T
}

export type UploadFile = { uri: string; name: string; mimeType: string }

/**
 * multipart/form-data upload with progress. XMLHttpRequest is used because fetch
 * reports no upload progress; the file part is named explicitly so the server sees
 * the real file name and extension, and the token travels only in the header.
 */
export function apiUpload<T>(
  path: string,
  fieldName: string,
  file: UploadFile,
  onProgress?: (fraction: number) => void,
  baseUrl?: string | null,
): Promise<T> {
  const base = requireBase(path, baseUrl)
  const { headers, token } = authHeaders(path)

  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${base}${path}`)
    xhr.timeout = UPLOAD_TIMEOUT_MS
    for (const [name, value] of Object.entries(headers)) {
      xhr.setRequestHeader(name, value)
    }
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) {
          onProgress(Math.min(1, event.loaded / event.total))
        }
      }
    }
    xhr.onload = () => {
      const payload = parseBody(xhr.responseText ?? '')
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(1)
        resolve(payload as T)
      } else {
        reject(failure(path, xhr.status, payload, token))
      }
    }
    xhr.onerror = () => reject(new ApiError(path, null, null, 'Network error'))
    xhr.ontimeout = () => reject(new ApiError(path, null, null, 'Upload timed out'))

    const form = new FormData()
    // React Native's FormData takes a { uri, name, type } descriptor for a file part.
    form.append(fieldName, { uri: file.uri, name: file.name, type: file.mimeType } as unknown as Blob)
    xhr.send(form)
  })
}

export type DownloadedFile = {
  bytes: Uint8Array
  /** From Content-Disposition when the server sent one. */
  fileName: string | null
  contentType: string | null
}

/** Authenticated binary GET. The token is sent as a header, never in the URL. */
export async function apiDownload(path: string, baseUrl?: string | null): Promise<DownloadedFile> {
  const base = requireBase(path, baseUrl)
  const { token } = authHeaders(path)
  const headers: Record<string, string> = { Accept: '*/*' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(`${base}${path}`, { method: 'GET', headers })
  } catch (cause) {
    throw new ApiError(path, null, null, cause instanceof Error ? cause.message : 'Network error')
  }
  if (!response.ok) {
    throw failure(path, response.status, parseBody(await response.text()), token)
  }
  const buffer = await response.arrayBuffer()
  return {
    bytes: new Uint8Array(buffer),
    fileName: fileNameFromDisposition(response.headers.get('content-disposition')),
    contentType: response.headers.get('content-type'),
  }
}

/** Reads `filename*=UTF-8''...` first (RFC 5987), then a plain `filename="..."`. */
export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) {
    return null
  }
  const extended = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header)
  if (extended) {
    try {
      return decodeURIComponent(extended[2].trim().replace(/^"|"$/g, ''))
    } catch {
      // fall through to the plain parameter
    }
  }
  const plain = /filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(header)
  if (plain) {
    return (plain[2] ?? plain[1]).trim() || null
  }
  return null
}
