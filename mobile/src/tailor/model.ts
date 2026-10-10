import { ApiError } from '../api/client'
import { apiErrorKey } from '../api/errors'
import type { JobResponse, ResumeResponse } from '../api/types'
import type { TailorChange } from './types'

export type ResumeEligibility = 'ok' | 'unavailable' | 'unsupported'

/** The server tailors DOCX, and PDF through its internal Word copy. Nothing else. */
export function resumeEligibility(resume: Pick<ResumeResponse, 'fileType' | 'fileAvailable'>): ResumeEligibility {
  const type = (resume.fileType ?? '').toLowerCase()
  if (type !== 'pdf' && type !== 'docx') {
    return 'unsupported'
  }
  return resume.fileAvailable ? 'ok' : 'unavailable'
}

/** The server answers 422 "Job description is not available" for an empty description. */
export function jobHasDescription(job: Pick<JobResponse, 'description'>): boolean {
  return Boolean(job.description?.trim())
}

/** expiresAt is a server LocalDateTime in UTC without an offset. */
export function parseServerUtc(value: string | null | undefined): number | null {
  if (!value) {
    return null
  }
  const withZone = /[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`
  const time = Date.parse(withZone)
  return Number.isNaN(time) ? null : time
}

export function isProposalExpired(expiresAt: string | null | undefined, now = Date.now()): boolean {
  const time = parseServerUtc(expiresAt)
  return time !== null && time <= now
}

export function formatLocalDateTime(expiresAt: string | null | undefined): string | null {
  const time = parseServerUtc(expiresAt)
  if (time === null) {
    return null
  }
  const date = new Date(time)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * The confirm request needs a non-empty selectedNodeIds. The app confirms the proposal
 * as a whole, so it sends every proposed node once.
 */
export function confirmNodeIds(changes: TailorChange[] | null | undefined): string[] {
  const ids: string[] = []
  for (const change of changes ?? []) {
    const id = change?.nodeId?.trim()
    if (id && !ids.includes(id)) {
      ids.push(id)
    }
  }
  return ids
}

export function whyBetter(change: TailorChange, language: string): string | null {
  const chinese = language.toLowerCase().startsWith('zh')
  const text = chinese
    ? change.improvementSummaryZh || change.reasonZh || change.reason
    : change.improvementSummaryEn || change.reason || change.reasonZh
  return text?.trim() || null
}

/**
 * Statuses the tailoring service returns before writing any file: validation (400),
 * auth (401/403), missing job/resume/proposal (404), proposal state (409) and a missing
 * description or unreadable resume (422). Anything else -- no response, 5xx, gateways --
 * may have come after the server already generated something.
 */
const NOTHING_GENERATED = new Set([400, 401, 403, 404, 409, 422])

export function isAmbiguousTailorFailure(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status === null) {
    return true
  }
  return !NOTHING_GENERATED.has(error.status)
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? (error.body?.message ?? '').toLowerCase() : ''
}

export function isConfirmInProgress(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && messageOf(error).includes('already being confirmed')
}

/** 404 "not found" or 409 "no longer pending": the proposal can no longer be confirmed. */
export function isProposalUnavailable(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return false
  }
  return error.status === 404 || (error.status === 409 && messageOf(error).includes('no longer pending'))
}

export function tailorErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status !== null) {
    const message = messageOf(error)
    if (error.status === 400 && (message.includes('docx only') || message.includes('canonical or native docx'))) {
      return 'tailor.errors.docxOnly'
    }
    if (error.status === 422 && message.includes('job description')) {
      return 'tailor.errors.noDescription'
    }
    if (error.status === 422) {
      return 'tailor.errors.unreadable'
    }
    if (isConfirmInProgress(error)) {
      return 'tailor.errors.confirmInProgress'
    }
    if (error.status === 404 && message.includes('tailor proposal')) {
      return 'tailor.errors.proposalGone'
    }
    if (error.status === 409 && message.includes('no longer pending')) {
      return 'tailor.errors.proposalGone'
    }
  }
  return apiErrorKey(error)
}

/** Preparing a PDF: a 400 from canonicalize means the recognized structure is incomplete. */
export function prepareErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) {
    return 'tailor.errors.incompleteRecognition'
  }
  return tailorErrorKey(error)
}
