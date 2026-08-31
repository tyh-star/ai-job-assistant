import type { TailoredResumeResponse, V3OpportunityMap } from '../api/types'
import type { EnhancedAnswerDraft } from './enhancedAnswers'
import type { EnhancedPhase, ProductUiMode } from './productMode'

export interface TailorSessionSnapshot {
  productMode: ProductUiMode
  phase: EnhancedPhase
  tailorResumeId: number | null
  opportunity: V3OpportunityMap | null
  answers: Record<string, EnhancedAnswerDraft>
  questionIndex: number
  result: TailoredResumeResponse | null
  selectedEntryKeys: string[]
  selectedSkillReviewIds?: string[]
  suggestionText: Record<string, string>
  aiOriginal: Record<string, string>
}

const PREFIX = 'aijob.tailor.session.'

export function sessionKey(jobId: string, resumeId: string): string {
  return `${PREFIX}${jobId}.${resumeId}`
}

export function loadSession(jobId: string, resumeId: string): TailorSessionSnapshot | null {
  if (!jobId || !resumeId) {
    return null
  }
  try {
    const raw = sessionStorage.getItem(sessionKey(jobId, resumeId))
    if (!raw) {
      return null
    }
    return JSON.parse(raw) as TailorSessionSnapshot
  } catch {
    return null
  }
}

export function saveSession(jobId: string, resumeId: string, snapshot: TailorSessionSnapshot): void {
  if (!jobId || !resumeId) {
    return
  }
  try {
    sessionStorage.setItem(sessionKey(jobId, resumeId), JSON.stringify(snapshot))
  } catch {
    /* ignore quota */
  }
}

export function hasLocalUnsavedWork(snapshot: Partial<TailorSessionSnapshot> | null): boolean {
  if (!snapshot) {
    return false
  }
  const answers = Object.values(snapshot.answers ?? {})
  if (answers.some((answer) => answer.intent && !answer.submitted)) {
    return true
  }
  const original = snapshot.aiOriginal ?? {}
  const current = snapshot.suggestionText ?? {}
  return Object.keys(current).some((nodeId) => current[nodeId] !== original[nodeId])
}
