/**
 * ENHANCED rules ported from the website (frontend/src/tailor/enhancedAnswers.ts,
 * enhancedConfirm.ts, evidenceUsage.ts, opportunityCopy.ts) and the backend limits.
 */
import { ApiError } from '../../api/client'
import { tailorErrorKey } from '../model'
import type {
  AnswerEnhancedQuestionRequest,
  AnswerStatus,
  ConfirmEnhancedRequest,
  ResumeEvidenceItem,
  TailoredResumeResponse,
  V3EnhancedQuestion,
  V3EntryReview,
  V3EvidenceUsage,
  V3OpportunityEntry,
  V3OpportunityMap,
  V3SkillReview,
} from '../types'

export const MAX_VISIBLE_QUESTIONS = 5
/** ConservativeTailorPlanner.ABSOLUTE_MAX_CHARACTERS, checked on every confirmed edit. */
export const MAX_EDIT_CHARACTERS = 180

export function isEnhancedResponse(result: Pick<TailoredResumeResponse, 'productMode'> | null | undefined): boolean {
  return (result?.productMode ?? '').toUpperCase() === 'ENHANCED'
}

// ---------------------------------------------------------------------------
// questions and answers

export type AnswerIntent = 'used' | 'not_used' | 'skipped' | null

export type AnswerDraft = {
  intent: AnswerIntent
  text: string
  entryKey?: string
  /** The exact request last accepted by the server for this question. */
  submittedSignature?: string
}

const YES_ONLY = new Set(['yes', 'y', 'yeah', 'yep', 'true', 'ok', 'used', '是', '有', '做过', '有做过', '用过'])

export function visibleQuestions(questions: V3EnhancedQuestion[] | null | undefined): V3EnhancedQuestion[] {
  return (questions ?? []).slice(0, MAX_VISIBLE_QUESTIONS)
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

/** "I did this" needs a real description, as on the website. */
export function confirmedUsedNeedsText(question: V3EnhancedQuestion, draft: AnswerDraft | undefined): boolean {
  if (draft?.intent !== 'used') {
    return false
  }
  const text = draft.text ?? ''
  if (!text.trim() || isBareYes(text)) {
    return true
  }
  return question.topic === 'maintain' && !specificMaintainAnswer(text)
}

export function canSubmitAnswers(
  questions: V3EnhancedQuestion[],
  drafts: Record<string, AnswerDraft>,
): { ok: true } | { ok: false; questionId: string } {
  for (const question of visibleQuestions(questions)) {
    if (confirmedUsedNeedsText(question, drafts[question.id])) {
      return { ok: false, questionId: question.id }
    }
  }
  return { ok: true }
}

export function toAnswerStatus(intent: AnswerIntent): AnswerStatus {
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

export function selectedEntryKey(question: V3EnhancedQuestion, draft: AnswerDraft | undefined): string {
  const allowed = allowedEntryKeys(question)
  const selected = draft?.entryKey?.trim()
  return selected && allowed.includes(selected) ? selected : question.canonicalEntryKey
}

/** An unanswered question is submitted as SKIPPED, which the server does not store. */
export function answerPayload(question: V3EnhancedQuestion, draft: AnswerDraft | undefined): AnswerEnhancedQuestionRequest {
  const answerStatus = toAnswerStatus(draft?.intent ?? 'skipped')
  const payload: AnswerEnhancedQuestionRequest = { questionId: question.id, answerStatus }
  if (answerStatus === 'CONFIRMED_USED') {
    payload.answerText = (draft?.text ?? '').trim()
  }
  const entryKey = selectedEntryKey(question, draft)
  if (entryKey) {
    payload.entryKey = entryKey
  }
  return payload
}

export function answerSignature(question: V3EnhancedQuestion, draft: AnswerDraft | undefined): string {
  return JSON.stringify(answerPayload(question, draft))
}

export function answerSaved(question: V3EnhancedQuestion, draft: AnswerDraft | undefined): boolean {
  return Boolean(draft?.submittedSignature) && draft?.submittedSignature === answerSignature(question, draft)
}

/** Only answers that never succeeded, or changed since they did, are sent again. */
export function answersToSubmit(
  questions: V3EnhancedQuestion[],
  drafts: Record<string, AnswerDraft>,
): { question: V3EnhancedQuestion; payload: AnswerEnhancedQuestionRequest; signature: string }[] {
  return visibleQuestions(questions)
    .map((question) => ({
      question,
      payload: answerPayload(question, drafts[question.id]),
      signature: answerSignature(question, drafts[question.id]),
    }))
    .filter((item) => drafts[item.question.id]?.submittedSignature !== item.signature)
}

export function hasUnsubmittedAnswers(questions: V3EnhancedQuestion[], drafts: Record<string, AnswerDraft>): boolean {
  return visibleQuestions(questions).some((question) => {
    const draft = drafts[question.id]
    return Boolean(draft?.intent) && !answerSaved(question, draft)
  })
}

// ---------------------------------------------------------------------------
// review, selection and edits

export function eligibleEntryKeys(reviews: V3EntryReview[] | null | undefined): string[] {
  return (reviews ?? [])
    .filter((review) => (review.proposedNodes ?? []).some((node) => node.nodeId))
    .map((review) => review.canonicalEntryKey)
}

export function defaultSelectedSkillReviewIds(reviews: V3SkillReview[] | null | undefined): string[] {
  return (reviews ?? [])
    .filter((review) => review.admitted !== false && (review.proposedSkills || (review.proposedNodes ?? []).length > 0))
    .map((review) => review.skillReviewId)
}

/** nodeId -> AI text for entries, plus nodeId and skillReviewId -> AI text for skills. */
export function suggestionMap(result: Pick<TailoredResumeResponse, 'entryReviews' | 'skillReviews'>): Record<string, string> {
  const map: Record<string, string> = {}
  for (const review of result.entryReviews ?? []) {
    for (const node of review.proposedNodes ?? []) {
      if (node.nodeId) {
        map[node.nodeId] = node.text ?? ''
      }
    }
  }
  for (const review of result.skillReviews ?? []) {
    for (const node of review.proposedNodes ?? []) {
      if (node.nodeId) {
        map[node.nodeId] = node.text ?? ''
      }
    }
    if (review.skillReviewId) {
      map[review.skillReviewId] = review.proposedSkills ?? ''
    }
  }
  return map
}

export function originalJoined(review: V3EntryReview): string {
  return (review.originalNodes ?? []).map((node) => node.text ?? '').join('\n')
}

export function proposedJoined(review: V3EntryReview, texts: Record<string, string>): string {
  return (review.proposedNodes ?? []).map((node) => texts[node.nodeId] ?? node.text ?? '').join('\n')
}

function splitIntoNodes(texts: Record<string, string>, nodeIds: string[], text: string): Record<string, string> {
  const lines = text.split('\n')
  const next = { ...texts }
  nodeIds.forEach((nodeId, index) => {
    next[nodeId] = index === nodeIds.length - 1 ? lines.slice(index).join('\n') : (lines[index] ?? '')
  })
  return next
}

/** Line i goes to proposed node i; the last node keeps any remaining lines (website mapping). */
export function applyEntryText(texts: Record<string, string>, review: V3EntryReview, text: string): Record<string, string> {
  return splitIntoNodes(texts, (review.proposedNodes ?? []).map((node) => node.nodeId), text)
}

export function applySkillText(texts: Record<string, string>, review: V3SkillReview, text: string): Record<string, string> {
  const next = splitIntoNodes(texts, (review.proposedNodes ?? []).map((node) => node.nodeId), text)
  next[review.skillReviewId] = text
  return next
}

export function restoreEntryText(texts: Record<string, string>, review: V3EntryReview, ai: Record<string, string>) {
  const next = { ...texts }
  for (const node of review.proposedNodes ?? []) {
    next[node.nodeId] = ai[node.nodeId] ?? node.text ?? ''
  }
  return next
}

export function restoreSkillText(texts: Record<string, string>, review: V3SkillReview, ai: Record<string, string>) {
  const next = { ...texts, [review.skillReviewId]: ai[review.skillReviewId] ?? review.proposedSkills }
  for (const node of review.proposedNodes ?? []) {
    next[node.nodeId] = ai[node.nodeId] ?? node.text ?? ''
  }
  return next
}

export function buildEnhancedConfirmBody(
  result: Pick<TailoredResumeResponse, 'entryReviews' | 'skillReviews'>,
  selectedEntryKeys: string[],
  selectedSkillReviewIds: string[],
  texts: Record<string, string>,
  ai: Record<string, string>,
): ConfirmEnhancedRequest {
  const body: ConfirmEnhancedRequest = {}
  const entries = new Set(selectedEntryKeys)
  const skills = new Set(selectedSkillReviewIds)
  const current = (id: string, fallback: string) => texts[id] ?? fallback
  const original = (id: string, fallback: string) => ai[id] ?? fallback

  const editedEntries: NonNullable<ConfirmEnhancedRequest['editedEntries']> = []
  for (const review of result.entryReviews ?? []) {
    if (!entries.has(review.canonicalEntryKey)) {
      continue
    }
    const nodes = review.proposedNodes ?? []
    if (nodes.some((node) => current(node.nodeId, node.text) !== original(node.nodeId, node.text))) {
      editedEntries.push({
        canonicalEntryKey: review.canonicalEntryKey,
        nodes: nodes.map((node) => ({ nodeId: node.nodeId, newText: current(node.nodeId, node.text ?? '') })),
      })
    }
  }

  const editedSkillReviews: NonNullable<ConfirmEnhancedRequest['editedSkillReviews']> = []
  for (const review of result.skillReviews ?? []) {
    if (!skills.has(review.skillReviewId)) {
      continue
    }
    const nodes = review.proposedNodes ?? []
    if (nodes.length > 0) {
      if (nodes.some((node) => current(node.nodeId, node.text) !== original(node.nodeId, node.text))) {
        editedSkillReviews.push({
          skillReviewId: review.skillReviewId,
          proposedSkills: nodes.map((node) => current(node.nodeId, node.text ?? '')).join('\n'),
          nodes: nodes.map((node) => ({ nodeId: node.nodeId, newText: current(node.nodeId, node.text ?? '') })),
        })
      }
    } else if (current(review.skillReviewId, review.proposedSkills) !== original(review.skillReviewId, review.proposedSkills)) {
      editedSkillReviews.push({ skillReviewId: review.skillReviewId, proposedSkills: current(review.skillReviewId, review.proposedSkills) })
    }
  }

  if (selectedEntryKeys.length > 0) {
    body.selectedEntryKeys = selectedEntryKeys
  }
  if (editedEntries.length > 0) {
    body.editedEntries = editedEntries
  }
  if (selectedSkillReviewIds.length > 0) {
    body.selectedSkillReviewIds = selectedSkillReviewIds
  }
  if (editedSkillReviews.length > 0) {
    body.editedSkillReviews = editedSkillReviews
  }
  return body
}

/** The server rejects blank or over-long edited node text with a 400. */
export function validateEnhancedEdits(body: ConfirmEnhancedRequest): { key: string; params?: Record<string, number> } | null {
  const nodes = [
    ...(body.editedEntries ?? []).flatMap((entry) => entry.nodes),
    ...(body.editedSkillReviews ?? []).flatMap((review) => review.nodes ?? []),
  ]
  for (const node of nodes) {
    const text = node.newText.trim()
    if (!text) {
      return { key: 'tailor.enhanced.errors.editBlank' }
    }
    if (text.length > MAX_EDIT_CHARACTERS) {
      return { key: 'tailor.enhanced.errors.editTooLong', params: { max: MAX_EDIT_CHARACTERS } }
    }
  }
  return null
}

export function suggestionTypeLabelKey(suggestionType: string | undefined): string {
  if (suggestionType === 'JD_TARGETED' || suggestionType === 'JD_ALIGNED') {
    return 'tailor.enhanced.entry.typeJd'
  }
  if (suggestionType === 'CLARITY' || suggestionType === 'CLARITY_FIRST' || suggestionType === 'EXPRESSION') {
    return 'tailor.enhanced.entry.typeClarity'
  }
  return 'tailor.enhanced.entry.typeEvidence'
}

// ---------------------------------------------------------------------------
// opportunity copy and evidence usage

export function cleanDisplayItems(items: string[] | null | undefined): string[] {
  const seen = new Set<string>()
  const cleaned: string[] = []
  for (const item of items ?? []) {
    const value = String(item ?? '')
      .replace(/^[\s•·\-–—]+/g, '')
      .replace(/[\s•·]+$/g, '')
      .replace(/•\s*•/g, ' ')
      .trim()
    if (!value || value === '-' || value === '•' || seen.has(value)) {
      continue
    }
    seen.add(value)
    cleaned.push(value)
  }
  return cleaned
}

export function actionableEntries(map: V3OpportunityMap | null | undefined): V3OpportunityEntry[] {
  return (map?.entries ?? []).filter((entry) => entry.priority === 'HIGH' || entry.priority === 'MEDIUM')
}

export function mostRelevantTitles(map: V3OpportunityMap | null | undefined): string[] {
  return (map?.entries ?? []).filter((entry) => entry.priority === 'HIGH').map((entry) => entry.entryTitle)
}

export function hasCannotInvent(map: V3OpportunityMap | null | undefined): boolean {
  return (map?.entries ?? []).some((entry) => (entry.cannotInvent ?? []).length > 0)
}

export function relatedRequirementForQuestion(
  entry: V3OpportunityEntry | undefined,
  question: V3EnhancedQuestion | undefined,
): string | undefined {
  const fromQuestion = question?.requirementText?.trim()
  if (fromQuestion) {
    return fromQuestion
  }
  if (!entry) {
    return undefined
  }
  const needle = (question?.topic ?? '').toLowerCase()
  const pools = [
    ...cleanDisplayItems(entry.partiallyCoveredRequirements),
    ...cleanDisplayItems(entry.missingRequirements),
    ...cleanDisplayItems(entry.missingFactsThatCanBeConfirmed),
    ...cleanDisplayItems(entry.coveredRequirements),
  ]
  if (!needle) {
    return pools[0]
  }
  return pools.find((item) => item.toLowerCase().includes(needle)) ?? pools[0]
}

export function matchedRequirements(entry: V3OpportunityEntry | undefined): string[] {
  return entry ? cleanDisplayItems([...entry.coveredRequirements, ...entry.partiallyCoveredRequirements]) : []
}

export function usageCounts(usages: V3EvidenceUsage[] | null | undefined) {
  const saved = (usages ?? []).filter((item) => item.usageStatus !== 'USER_REJECTED')
  const used = saved.filter((item) => (item.usageStatus ?? '').startsWith('USED_'))
  const unused = saved.filter((item) => item.usageStatus === 'SAVED_NOT_USED')
  return { saved: saved.length, used: used.length, unused: unused.length }
}

const USAGE_REASONS = new Set([
  'NO_ADMITTED_REWRITE',
  'FACT_ALREADY_PRESENT',
  'NOT_A_SKILL',
  'NO_SKILLS_SECTION',
  'NOT_RELEVANT_TO_JD',
  'VALIDATOR_REJECTED',
  'CONFIRMED_FACT_NOT_USED',
])

export function usageReasonKey(reasonCode: string | undefined): string {
  return `tailor.enhanced.evidence.reasons.${reasonCode && USAGE_REASONS.has(reasonCode) ? reasonCode : 'NO_ADMITTED_REWRITE'}`
}

export function evidenceDisplayText(usage: V3EvidenceUsage, items: ResumeEvidenceItem[] | null | undefined): string {
  const item = (items ?? []).find((row) => row.id === usage.evidenceItemId)
  if (!item) {
    return ''
  }
  return [item.action, item.result, item.metric, item.context]
    .filter((part): part is string => Boolean(part && part.trim()))
    .join(' ')
    .trim()
}

export function entryTitleForKey(key: string | undefined, map: V3OpportunityMap | null | undefined): string {
  if (!key) {
    return ''
  }
  return map?.entries.find((item) => item.canonicalEntryKey === key)?.entryTitle ?? key
}

/** Details the user added that the server used for this entry, plus this session's answers. */
export function suppliedInfoForEntry(
  review: V3EntryReview,
  usages: V3EvidenceUsage[] | null | undefined,
  items: ResumeEvidenceItem[] | null | undefined,
  map: V3OpportunityMap | null | undefined,
  answers: Record<string, AnswerDraft>,
): string[] {
  const fromServer = (usages ?? [])
    .filter((usage) => (usage.usedInEntryKeys ?? []).includes(review.canonicalEntryKey))
    .map((usage) => evidenceDisplayText(usage, items))
  const fromSession = (map?.questions ?? [])
    .filter((question) => question.canonicalEntryKey === review.canonicalEntryKey)
    .map((question) => answers[question.id])
    .filter((draft) => draft?.intent === 'used')
    .map((draft) => draft?.text.trim() ?? '')
  return cleanDisplayItems([...fromServer, ...fromSession])
}

// ---------------------------------------------------------------------------
// errors

export function enhancedErrorKey(error: unknown): string {
  if (error instanceof ApiError && error.status !== null) {
    const message = (error.body?.message ?? '').toLowerCase()
    if (error.status === 400) {
      if (message.includes('not enough') || message.includes('describe the actual') || message.includes('answertext is required')) {
        return 'tailor.enhanced.errors.needRealDetails'
      }
      if (message.includes('questionid is not valid') || message.includes('entrykey is not valid') || message.includes('questionid and answerstatus')) {
        return 'tailor.enhanced.errors.questionStale'
      }
      if (message.includes('enhanced mode is not available')) {
        return 'tailor.enhanced.errors.unavailable'
      }
      if (message.includes('selectednodeids must not be empty')) {
        return 'tailor.enhanced.errors.nothingSelected'
      }
      if (
        message.includes('node cannot be edited') ||
        message.includes('must be selected') ||
        message.includes('newtext') ||
        message.includes('not part of this proposal') ||
        message.includes('does not match the target node')
      ) {
        return 'tailor.enhanced.errors.editRejected'
      }
      if (message.startsWith('evidence_') || message.includes('topic is required') || message.includes('too long')) {
        return 'tailor.enhanced.errors.evidenceInvalid'
      }
    }
    if (error.status === 409 && message.includes('already saved evidence')) {
      return 'tailor.enhanced.errors.evidenceConflict'
    }
  }
  return tailorErrorKey(error)
}
