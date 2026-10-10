import { tailoredFileFromUrl } from './files'
import { tailorStore } from './store'
import type { TailorMode, TailoredResumeResponse } from './types'

/** Product modes shown in the app. ENHANCED is its own mode, never a TailorMode value. */
export type ProductMode = TailorMode | 'ENHANCED'

function positiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

/**
 * An ENHANCED proposal waiting for review. Kept under its own key, so the older
 * AGGRESSIVE record (`aijob.tailor.<user>.<job>`, no productMode) is never read as one
 * of these or overwritten by one. Identifiers and flags only -- no answers or text.
 */
export type EnhancedPendingRecord = {
  productMode: 'ENHANCED'
  proposalId: number
  jobId: number
  /** The file the user picked. */
  resumeId: number
  /** The prepared DOCX the proposal was made from (a PDF's internal copy). */
  tailorResumeId: number
  expiresAt: string | null
  /** Saved evidence changed after this proposal was made; it must not be confirmed. */
  stale: boolean
  staleReason?: 'evidence' | 'structure' | null
  /** When this device received the proposal, and when its DOCX was prepared. Older records have neither. */
  savedAt?: string | null
  preparedAt?: string | null
}

export function enhancedPendingKey(userId: number, jobId: number): string {
  return `aijob.tailor.${userId}.${jobId}.ENHANCED`
}

export function parseEnhancedPending(raw: string | null): EnhancedPendingRecord | null {
  if (!raw) {
    return null
  }
  try {
    const value = JSON.parse(raw) as Partial<EnhancedPendingRecord>
    if (value.productMode !== 'ENHANCED') {
      return null
    }
    if (![value.proposalId, value.jobId, value.resumeId, value.tailorResumeId].every(positiveId)) {
      return null
    }
    return {
      productMode: 'ENHANCED',
      proposalId: value.proposalId as number,
      jobId: value.jobId as number,
      resumeId: value.resumeId as number,
      tailorResumeId: value.tailorResumeId as number,
      expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
      stale: value.stale === true,
      staleReason: value.staleReason === 'evidence' || value.staleReason === 'structure' ? value.staleReason : null,
      savedAt: typeof value.savedAt === 'string' ? value.savedAt : null,
      preparedAt: typeof value.preparedAt === 'string' ? value.preparedAt : null,
    }
  } catch {
    return null
  }
}

export async function loadEnhancedPending(userId: number, jobId: number): Promise<EnhancedPendingRecord | null> {
  const record = parseEnhancedPending(await tailorStore.get(enhancedPendingKey(userId, jobId)))
  return record && record.jobId === jobId ? record : null
}

export async function saveEnhancedPending(userId: number, record: EnhancedPendingRecord): Promise<void> {
  await tailorStore.set(enhancedPendingKey(userId, record.jobId), JSON.stringify(record))
}

export async function clearEnhancedPending(userId: number, jobId: number): Promise<void> {
  await tailorStore.remove(enhancedPendingKey(userId, jobId))
}

/**
 * The download entry of a file this device received in a successful response. It is a
 * local shortcut, not server history: the server has no list of tailored files.
 */
export type KnownResult = {
  mode: ProductMode
  jobId: number
  resumeId: number
  downloadUrl: string
  pdfDownloadUrl: string | null
  appliedChangeCount: number
  rejectedChangeCount: number
  savedAt: string
}

const MODES: ProductMode[] = ['CONSERVATIVE', 'AGGRESSIVE', 'ENHANCED']

export function knownResultKey(userId: number, jobId: number, mode: ProductMode): string {
  return `aijob.tailorResult.${userId}.${jobId}.${mode}`
}

export function knownResultFrom(
  mode: ProductMode,
  resumeId: number,
  result: TailoredResumeResponse,
  now = new Date(),
): KnownResult | null {
  if (!positiveId(resumeId) || !tailoredFileFromUrl(result.downloadUrl, result.jobId)) {
    return null
  }
  const pdfReady = result.pdfExportStatus === 'SUCCESS' && tailoredFileFromUrl(result.pdfDownloadUrl, result.jobId)
  return {
    mode,
    jobId: result.jobId,
    resumeId,
    downloadUrl: result.downloadUrl as string,
    pdfDownloadUrl: pdfReady ? (result.pdfDownloadUrl as string) : null,
    appliedChangeCount: result.appliedChangeCount,
    rejectedChangeCount: result.rejectedChangeCount,
    savedAt: now.toISOString(),
  }
}

export function parseKnownResult(raw: string | null, jobId: number, mode: ProductMode): KnownResult | null {
  if (!raw) {
    return null
  }
  try {
    const value = JSON.parse(raw) as Partial<KnownResult>
    if (value.mode !== mode || !MODES.includes(value.mode) || value.jobId !== jobId || !positiveId(value.resumeId)) {
      return null
    }
    if (!tailoredFileFromUrl(value.downloadUrl, jobId)) {
      return null
    }
    const pdf = value.pdfDownloadUrl && tailoredFileFromUrl(value.pdfDownloadUrl, jobId) ? value.pdfDownloadUrl : null
    const count = (n: unknown) => (typeof n === 'number' && n >= 0 ? n : 0)
    return {
      mode,
      jobId,
      resumeId: value.resumeId,
      downloadUrl: value.downloadUrl as string,
      pdfDownloadUrl: pdf,
      appliedChangeCount: count(value.appliedChangeCount),
      rejectedChangeCount: count(value.rejectedChangeCount),
      savedAt: typeof value.savedAt === 'string' ? value.savedAt : '',
    }
  } catch {
    return null
  }
}

export async function loadKnownResult(userId: number, jobId: number, mode: ProductMode): Promise<KnownResult | null> {
  return parseKnownResult(await tailorStore.get(knownResultKey(userId, jobId, mode)), jobId, mode)
}

export async function saveKnownResult(userId: number, record: KnownResult): Promise<void> {
  await tailorStore.set(knownResultKey(userId, record.jobId, record.mode), JSON.stringify(record))
}

export async function clearKnownResult(userId: number, jobId: number, mode: ProductMode): Promise<void> {
  await tailorStore.remove(knownResultKey(userId, jobId, mode))
}
