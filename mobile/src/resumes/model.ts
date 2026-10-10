/**
 * Pure rules for the resumes module. Two id spaces exist and never mix:
 *   uploaded / generated file   ResumeResponse.id              GET /resumes
 *   resume-builder draft        ResumeBuilderDraftSummary.id   GET /resume-builder/drafts
 * Quality score, download and delete only ever take a file id.
 */
import type { ResumeBuilderDraftContent, ResumeResponse } from '../api/types'

/** Matches spring.servlet.multipart.max-file-size. */
export const MAX_RESUME_BYTES = 10 * 1024 * 1024
export const MAX_FILE_NAME_LENGTH = 255

export const PDF_MIME = 'application/pdf'
export const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
/** The server also accepts this generic type; some Android pickers report it for DOCX. */
const GENERIC_MIME = 'application/octet-stream'

export type PickedDocument = {
  uri: string
  name: string | null | undefined
  size: number | null | undefined
  mimeType: string | null | undefined
}

export type ValidFile = { uri: string; name: string; size: number; mimeType: string; kind: 'pdf' | 'docx' }

export type FileProblem = 'noName' | 'badType' | 'badMime' | 'empty' | 'tooLarge' | 'unknownSize'

export function extensionOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index < 0 ? '' : name.slice(index + 1).toLowerCase()
}

/** Checks name, extension, MIME type and size before anything is sent. */
export function validatePickedFile(file: PickedDocument): { ok: true; file: ValidFile } | { ok: false; problem: FileProblem } {
  const name = baseName(file.name ?? '').trim()
  if (!name) {
    return { ok: false, problem: 'noName' }
  }
  const extension = extensionOf(name)
  if (extension !== 'pdf' && extension !== 'docx') {
    return { ok: false, problem: 'badType' }
  }
  const expected = extension === 'pdf' ? PDF_MIME : DOCX_MIME
  const reported = file.mimeType?.trim().toLowerCase()
  if (reported && reported !== expected && reported !== GENERIC_MIME) {
    return { ok: false, problem: 'badMime' }
  }
  if (typeof file.size !== 'number' || Number.isNaN(file.size)) {
    return { ok: false, problem: 'unknownSize' }
  }
  if (file.size <= 0) {
    return { ok: false, problem: 'empty' }
  }
  if (file.size > MAX_RESUME_BYTES) {
    return { ok: false, problem: 'tooLarge' }
  }
  return {
    ok: true,
    file: {
      uri: file.uri,
      name: name.length > MAX_FILE_NAME_LENGTH ? name.slice(0, MAX_FILE_NAME_LENGTH) : name,
      size: file.size,
      // Always send the precise type the server lists, even when the picker was vague.
      mimeType: expected,
      kind: extension,
    },
  }
}

function baseName(name: string): string {
  return name.split(/[\\/]/).pop() ?? ''
}

/**
 * A name that is safe to create in the app cache: no path parts, no characters the
 * file system or share sheet rejects, never "." or "..", never hidden, and the
 * extension the server says the file actually has.
 */
export function safeDownloadName(preferred: string | null | undefined, fileType: string | null | undefined): string {
  const extension = (fileType ?? '').toLowerCase() === 'pdf' ? 'pdf' : 'docx'
  let name = baseName(preferred ?? '')
    // control characters plus the characters file systems and share sheets reject
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f<>:"|?*]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (extensionOf(name) === extension) {
    name = name.slice(0, -(extension.length + 1))
  }
  name = name
    .replace(/^[.\s]+/, '')
    .replace(/[.\s]+$/, '')
    .slice(0, 120)
    .trim()
  return `${name || 'resume'}.${extension}`
}

export function mimeForFileType(fileType: string | null | undefined): string {
  return (fileType ?? '').toLowerCase() === 'pdf' ? PDF_MIME : DOCX_MIME
}

export function utiForFileType(fileType: string | null | undefined): string {
  return (fileType ?? '').toLowerCase() === 'pdf' ? 'com.adobe.pdf' : 'org.openxmlformats.wordprocessingml.document'
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Files the list shows: the same rows the website shows. */
export function visibleFiles(resumes: ResumeResponse[]): ResumeResponse[] {
  return resumes
    .filter((resume) => resume.origin !== 'TEMPLATE')
    .sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
}

export type FileStatus = 'unavailable' | 'needsReview' | 'ready'

export function fileStatus(resume: ResumeResponse): FileStatus {
  if (!resume.fileAvailable) {
    return 'unavailable'
  }
  if (resume.fileType?.toLowerCase() === 'pdf' && resume.reviewStatus === 'PENDING_REVIEW') {
    return 'needsReview'
  }
  return 'ready'
}

/** A PDF with an internal Word working copy is deleted as a family, like the website does. */
export function deletesFamily(resume: ResumeResponse): boolean {
  return resume.fileType?.toLowerCase() === 'pdf' && resume.canonicalResumeId !== null
}

export function parseResourceId(raw: string | string[] | undefined): number | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value || !/^\d+$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export type SectionCount = { key: string; count: number }

const SECTION_KEYS = [
  'education',
  'experience',
  'projects',
  'skillCategories',
  'certifications',
  'languages',
  'volunteer',
  'leadership',
  'publications',
  'customSections',
] as const

/** How many entries each section of a draft really has; empty sections are left out. */
export function draftSectionCounts(content: ResumeBuilderDraftContent | null | undefined): SectionCount[] {
  if (!content) {
    return []
  }
  return SECTION_KEYS.map((key) => {
    const value = content[key]
    return { key, count: Array.isArray(value) ? value.length : 0 }
  }).filter((section) => section.count > 0)
}
