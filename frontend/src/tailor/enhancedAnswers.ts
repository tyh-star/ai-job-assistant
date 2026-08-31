import type { AnswerStatus, V3EnhancedQuestion } from '../api/types'

const YES_ONLY = new Set([
  'yes',
  'y',
  'yeah',
  'yep',
  'true',
  'ok',
  'used',
  '是',
  '有',
  '做过',
  '有做过',
  '用过',
])

export type EnhancedAnswerIntent = 'used' | 'not_used' | 'skipped' | null

export interface EnhancedAnswerDraft {
  intent: EnhancedAnswerIntent
  text: string
  selectedEntryKey?: string
  submitted?: boolean
}

export function visibleQuestions(questions: V3EnhancedQuestion[] | undefined): V3EnhancedQuestion[] {
  return (questions ?? []).slice(0, 5)
}

export function groupQuestionsByEntry(questions: V3EnhancedQuestion[]): {
  canonicalEntryKey: string
  entryTitle: string
  questions: V3EnhancedQuestion[]
}[] {
  const groups: {
    canonicalEntryKey: string
    entryTitle: string
    questions: V3EnhancedQuestion[]
  }[] = []
  const index = new Map<string, number>()
  for (const question of visibleQuestions(questions)) {
    const existing = index.get(question.canonicalEntryKey)
    if (existing == null) {
      index.set(question.canonicalEntryKey, groups.length)
      groups.push({
        canonicalEntryKey: question.canonicalEntryKey,
        entryTitle: question.entryTitle,
        questions: [question],
      })
    } else {
      groups[existing].questions.push(question)
    }
  }
  return groups
}

export function isBareYes(text: string | undefined): boolean {
  const value = (text ?? '').trim()
  if (!value) {
    return false
  }
  const compact = value.replace(/[.!。！\s]+$/g, '').toLowerCase()
  return YES_ONLY.has(compact) || YES_ONLY.has(value)
}

export function specificMaintainAnswer(text: string | undefined): boolean {
  const value = (text ?? '').trim()
  if (value.length < 12 || isBareYes(value)) {
    return false
  }
  const withoutMaintain = value
    .replace(/\b(?:maintain|maintained|maintaining|maintenance)\b/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return withoutMaintain.length >= 12
}

export function confirmedUsedNeedsText(question: V3EnhancedQuestion, draft: EnhancedAnswerDraft | undefined): boolean {
  if (draft?.intent !== 'used') {
    return false
  }
  const text = draft.text ?? ''
  if (!text.trim() || isBareYes(text)) {
    return true
  }
  if (question.topic === 'maintain' && !specificMaintainAnswer(text)) {
    return true
  }
  return false
}

export function canSubmitAnswers(
  questions: V3EnhancedQuestion[],
  drafts: Record<string, EnhancedAnswerDraft>,
): { ok: true } | { ok: false; questionId: string } {
  for (const question of visibleQuestions(questions)) {
    if (confirmedUsedNeedsText(question, drafts[question.id])) {
      return { ok: false, questionId: question.id }
    }
  }
  return { ok: true }
}

export function toAnswerStatus(intent: EnhancedAnswerIntent): AnswerStatus {
  if (intent === 'used') {
    return 'CONFIRMED_USED'
  }
  if (intent === 'not_used') {
    return 'CONFIRMED_NOT_USED'
  }
  return 'SKIPPED'
}

export function allowedEntryKeys(question: V3EnhancedQuestion): string[] {
  if (question.allowedEntryKeys && question.allowedEntryKeys.length > 0) {
    return question.allowedEntryKeys
  }
  return question.canonicalEntryKey ? [question.canonicalEntryKey] : []
}

export function selectedEntryKey(question: V3EnhancedQuestion, draft: EnhancedAnswerDraft | undefined): string {
  const allowed = allowedEntryKeys(question)
  const selected = draft?.selectedEntryKey?.trim()
  if (selected && allowed.includes(selected)) {
    return selected
  }
  return question.canonicalEntryKey
}

export function answersForSubmit(
  questions: V3EnhancedQuestion[],
  drafts: Record<string, EnhancedAnswerDraft>,
): { questionId: string; answerStatus: AnswerStatus; answerText?: string; entryKey?: string }[] {
  return visibleQuestions(questions).map((question) => {
    const draft = drafts[question.id]
    const status = toAnswerStatus(draft?.intent ?? 'skipped')
    const entryKey = selectedEntryKey(question, draft)
    return {
      questionId: question.id,
      answerStatus: status,
      answerText: status === 'CONFIRMED_USED' ? draft?.text?.trim() : undefined,
      entryKey: entryKey || undefined,
    }
  })
}
