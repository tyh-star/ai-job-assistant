import { api, API_BASE_URL, readApiErrorMessage } from './client'
import { endpoints } from './endpoints'
import { stripApiV1Prefix } from './url'
import type {
  ResumeEvidenceItem,
  ResumeQualityScoreResponse,
  ResumeResponse,
  ResumeStructure,
  SaveResumeEvidenceRequest,
} from './types'

export function listResumes(includeDerived = false) {
  return api.get<ResumeResponse[]>(endpoints.resumes, {
    params: includeDerived ? { includeDerived: true } : undefined,
  })
}

export function uploadResume(file: File) {
  const data = new FormData()
  data.append('file', file)
  return api.post<ResumeResponse>(endpoints.resumes, data)
}

export function deleteResume(id: number) {
  return api.delete(endpoints.resume(id))
}

export function deleteResumeFamily(id: number) {
  return api.delete(endpoints.resumeFamily(id))
}

export function getResumeStructure(id: number) {
  return api.get<ResumeStructure>(endpoints.resumeStructure(id))
}

export function updateResumeStructure(id: number, structure: ResumeStructure) {
  return api.put<ResumeStructure>(endpoints.resumeStructure(id), structure)
}

export function canonicalizeResume(id: number) {
  return api.post<ResumeResponse>(endpoints.resumeCanonicalize(id))
}

/** Consumes no AI quota -- see ResumeQualityScorer's javadoc on the backend. */
export function getResumeQualityScore(id: number) {
  return api.get<ResumeQualityScoreResponse>(endpoints.resumeQualityScore(id))
}

export function listResumeEvidence(resumeId: number) {
  return api.get<ResumeEvidenceItem[]>(endpoints.resumeEvidence(resumeId))
}

export function saveResumeEvidence(resumeId: number, body: SaveResumeEvidenceRequest) {
  return api.post<ResumeEvidenceItem>(endpoints.resumeEvidence(resumeId), body)
}

export function deleteResumeEvidence(resumeId: number, evidenceId: number) {
  return api.delete(endpoints.resumeEvidenceItem(resumeId, evidenceId))
}

export async function downloadByUrl(downloadUrl: string, fallbackName: string) {
  try {
    const path = toApiPath(downloadUrl)
    const response = await api.get(path, { responseType: 'blob' })
    const name = filenameFromDisposition(
      response.headers['content-disposition'],
      fallbackName,
    )
    triggerBlobDownload(response.data as Blob, name)
  } catch (error) {
    throw new Error(await readApiErrorMessage(error))
  }
}

export function toApiPath(downloadUrl: string): string {
  const path = downloadUrl.trim()
  if (/^https?:\/\//i.test(path) && path.startsWith(API_BASE_URL)) {
    return stripApiV1Prefix(path.slice(API_BASE_URL.length) || '/')
  }
  return stripApiV1Prefix(path)
}

function filenameFromDisposition(header: string | undefined, fallback: string): string {
  if (!header) {
    return fallback
  }
  const utf = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf?.[1]) {
    return decodeURIComponent(utf[1])
  }
  const simple = /filename="?([^";]+)"?/i.exec(header)
  return simple?.[1] ?? fallback
}

function triggerBlobDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
