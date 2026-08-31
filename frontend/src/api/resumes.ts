import { api, API_BASE_URL, readApiErrorMessage } from './client'
import type {
  ResumeEvidenceItem,
  ResumeResponse,
  ResumeStructure,
  SaveResumeEvidenceRequest,
} from './types'

export function listResumes(includeDerived = false) {
  return api.get<ResumeResponse[]>('/resumes', {
    params: includeDerived ? { includeDerived: true } : undefined,
  })
}

export function uploadResume(file: File) {
  const data = new FormData()
  data.append('file', file)
  return api.post<ResumeResponse>('/resumes', data)
}

export function deleteResume(id: number) {
  return api.delete(`/resumes/${id}`)
}

export function deleteResumeFamily(id: number) {
  return api.delete(`/resumes/${id}/family`)
}

export function getResumeStructure(id: number) {
  return api.get<ResumeStructure>(`/resumes/${id}/structure`)
}

export function updateResumeStructure(id: number, structure: ResumeStructure) {
  return api.put<ResumeStructure>(`/resumes/${id}/structure`, structure)
}

export function canonicalizeResume(id: number) {
  return api.post<ResumeResponse>(`/resumes/${id}/canonicalize`)
}

export function listResumeEvidence(resumeId: number) {
  return api.get<ResumeEvidenceItem[]>(`/resumes/${resumeId}/evidence`)
}

export function saveResumeEvidence(resumeId: number, body: SaveResumeEvidenceRequest) {
  return api.post<ResumeEvidenceItem>(`/resumes/${resumeId}/evidence`, body)
}

export function deleteResumeEvidence(resumeId: number, evidenceId: number) {
  return api.delete(`/resumes/${resumeId}/evidence/${evidenceId}`)
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
  let path = downloadUrl.trim()
  if (path.startsWith('http://') || path.startsWith('https://')) {
    if (path.startsWith(API_BASE_URL)) {
      path = path.slice(API_BASE_URL.length)
    } else {
      const marker = '/api/v1'
      const index = path.indexOf(marker)
      path = index >= 0 ? path.slice(index + marker.length) : path
    }
  }
  if (path.startsWith('/api/v1/')) {
    path = path.slice('/api/v1'.length)
  } else if (path === '/api/v1') {
    path = '/'
  }
  if (!path.startsWith('/')) {
    path = `/${path}`
  }
  return path
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
