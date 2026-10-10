import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { enhancedApi, evidenceApi, tailorApi } from '../../api/endpoints'
import type { JobResponse, ResumeResponse } from '../../api/types'
import { useSessionGuard, type SessionTicket } from '../../builder/sessionGuard'
import { resumeKeys } from '../../resumes/queries'
import { loadStructureSavedAt, structureChangedSince } from '../../resumes/structure'
import { isAmbiguousTailorFailure, isConfirmInProgress, isProposalExpired, isProposalUnavailable, prepareErrorKey } from '../model'
import {
  clearEnhancedPending,
  knownResultFrom,
  loadEnhancedPending,
  saveEnhancedPending,
  saveKnownResult,
  type EnhancedPendingRecord,
} from '../records'
import type { ResumeEvidenceItem, TailoredResumeResponse, V3EvidenceUsage, V3OpportunityMap } from '../types'
import {
  answersToSubmit,
  applyEntryText,
  applySkillText,
  buildEnhancedConfirmBody,
  canSubmitAnswers,
  defaultSelectedSkillReviewIds,
  eligibleEntryKeys,
  enhancedErrorKey,
  hasUnsubmittedAnswers,
  isEnhancedResponse,
  proposedJoined,
  restoreEntryText,
  restoreSkillText,
  suggestionMap,
  validateEnhancedEdits,
  visibleQuestions,
  type AnswerDraft,
} from './rules'

export type EnhancedStage =
  | 'idle'
  | 'restoring'
  | 'restoreFailed'
  | 'preparing'
  | 'scanning'
  | 'scanFailed'
  | 'questions'
  | 'submitting'
  | 'generating'
  | 'generateFailed'
  | 'generateUnknown'
  | 'review'
  | 'confirming'
  | 'confirmFailed'
  | 'confirmUnknown'
  | 'checking'
  | 'done'
  | 'gone'

export type FlowError = { key: string; reason?: string; params?: Record<string, number | string> }

export type EditingState = { kind: 'entry' | 'skill'; key: string; draft: string }

export type EnhancedState = {
  stage: EnhancedStage
  jobId: number | null
  resumeId: number | null
  tailorResumeId: number | null
  opportunity: V3OpportunityMap | null
  answers: Record<string, AnswerDraft>
  questionIndex: number
  failedQuestionId: string | null
  error: FlowError | null
  proposal: TailoredResumeResponse | null
  record: EnhancedPendingRecord | null
  selectedEntryKeys: string[]
  selectedSkillIds: string[]
  texts: Record<string, string>
  aiOriginal: Record<string, string>
  editing: EditingState | null
  stale: boolean
  /** What made the proposal stale: changed evidence, or a PDF structure saved after it was made. */
  staleReason: 'evidence' | 'structure' | null
  /** When the prepared DOCX was made (a PDF's internal copy is rebuilt from the saved structure). */
  preparedAt: string | null
  notice: 'restored' | 'notConfirmed' | null
  result: TailoredResumeResponse | null
  pastExpiry: boolean
  evidence: ResumeEvidenceItem[] | null
  evidenceBusy: boolean
  evidenceError: FlowError | null
}

const INITIAL: EnhancedState = {
  stage: 'idle',
  jobId: null,
  resumeId: null,
  tailorResumeId: null,
  opportunity: null,
  answers: {},
  questionIndex: 0,
  failedQuestionId: null,
  error: null,
  proposal: null,
  record: null,
  selectedEntryKeys: [],
  selectedSkillIds: [],
  texts: {},
  aiOriginal: {},
  editing: null,
  stale: false,
  staleReason: null,
  preparedAt: null,
  notice: null,
  result: null,
  pastExpiry: false,
  evidence: null,
  evidenceBusy: false,
  evidenceError: null,
}

const BUSY = new Set<EnhancedStage>(['restoring', 'preparing', 'scanning', 'submitting', 'generating', 'confirming', 'checking'])
const REVIEWABLE = new Set<EnhancedStage>(['review', 'confirmFailed'])

export type EnhancedInput = { job: Pick<JobResponse, 'id'>; resume: Pick<ResumeResponse, 'id' | 'fileType'> }

function reviewFields(proposal: TailoredResumeResponse) {
  const originals = suggestionMap(proposal)
  return {
    proposal,
    selectedEntryKeys: eligibleEntryKeys(proposal.entryReviews),
    selectedSkillIds: defaultSelectedSkillReviewIds(proposal.skillReviews),
    texts: originals,
    aiOriginal: originals,
    editing: null,
  }
}

/**
 * The ENHANCED ("深度增强") flow: prepare DOCX -> scan -> answer -> scan again -> proposal
 * -> review -> confirm. Each server call runs once per user action; every result is
 * dropped when the account changed meanwhile.
 */
export function useEnhancedTailor() {
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const ref = useRef<EnhancedState>(INITIAL)
  const [state, setState] = useState<EnhancedState>(INITIAL)
  const busyRef = useRef(false)

  const patch = useCallback((next: Partial<EnhancedState>) => {
    ref.current = { ...ref.current, ...next }
    setState(ref.current)
  }, [])

  const lock = useCallback((): SessionTicket | null => {
    if (busyRef.current) {
      return null
    }
    busyRef.current = true
    return guard.capture()
  }, [guard])

  const unlock = useCallback(() => {
    busyRef.current = false
  }, [])

  const forgetRecord = useCallback(async (ticket: SessionTicket, jobId: number | null) => {
    if (ticket.userId !== null && jobId !== null) {
      await clearEnhancedPending(ticket.userId, jobId).catch(() => {})
    }
  }, [])

  /** True when this device saved the PDF's structure after `since`. DOCX resumes have no structure. */
  const structureChanged = useCallback(async (ticket: SessionTicket, resumeId: number, since: string | null | undefined) => {
    if (ticket.userId === null) {
      return false
    }
    const savedAt = await loadStructureSavedAt(ticket.userId, resumeId).catch(() => null)
    return structureChangedSince(savedAt, since)
  }, [])

  /** structure -> canonicalize; the server builds the internal DOCX from the saved structure. */
  const preparePdf = useCallback(
    async (ticket: SessionTicket, resumeId: number): Promise<{ id: number; preparedAt: string } | null> => {
      try {
        await tailorApi.structure(resumeId)
        if (!ticket.isCurrent()) {
          return null
        }
        const canonical = await tailorApi.canonicalize(resumeId)
        if (!ticket.isCurrent()) {
          return null
        }
        void queryClient.invalidateQueries({ queryKey: resumeKeys.files(ticket.userId), exact: true })
        return { id: canonical.id, preparedAt: new Date().toISOString() }
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ stage: 'scanFailed', tailorResumeId: null, error: { key: 'tailor.enhanced.errors.scanFailed', reason: prepareErrorKey(error) } })
        }
        return null
      }
    },
    [patch, queryClient],
  )

  const markStale = useCallback(
    async (ticket: SessionTicket, reason: 'evidence' | 'structure') => {
      const record = ref.current.record
      if (!record) {
        patch({ stale: true, staleReason: reason })
        return
      }
      const next = { ...record, stale: true, staleReason: reason }
      patch({ stale: true, staleReason: reason, record: next })
      if (ticket.userId !== null) {
        await saveEnhancedPending(ticket.userId, next).catch(() => {})
      }
    },
    [patch],
  )

  const loadEvidence = useCallback(
    async (ticket: SessionTicket) => {
      const resumeId = ref.current.resumeId
      if (resumeId === null) {
        return
      }
      try {
        const items = await evidenceApi.list(resumeId)
        if (ticket.isCurrent()) {
          patch({ evidence: items, evidenceError: null })
        }
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ evidenceError: { key: 'tailor.enhanced.evidence.loadFailed', reason: enhancedErrorKey(error) } })
        }
      }
    },
    [patch],
  )

  const scanInto = useCallback(
    async (ticket: SessionTicket, jobId: number, tailorResumeId: number) => {
      patch({ stage: 'scanning', error: null })
      try {
        const map = await enhancedApi.scan(jobId, tailorResumeId)
        if (ticket.isCurrent()) {
          patch({ stage: 'questions', opportunity: map, answers: {}, questionIndex: 0, failedQuestionId: null, error: null })
        }
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ stage: 'scanFailed', error: { key: 'tailor.enhanced.errors.scanFailed', reason: enhancedErrorKey(error) } })
        }
      }
    },
    [patch],
  )

  const generateInto = useCallback(
    async (ticket: SessionTicket) => {
      const { jobId, resumeId, tailorResumeId, preparedAt } = ref.current
      if (jobId === null || resumeId === null || tailorResumeId === null) {
        return
      }
      patch({ stage: 'generating', error: null })
      let proposal: TailoredResumeResponse
      try {
        proposal = await enhancedApi.createProposal(jobId, tailorResumeId)
      } catch (error) {
        if (ticket.isCurrent()) {
          const reason = enhancedErrorKey(error)
          patch(
            isAmbiguousTailorFailure(error)
              ? { stage: 'generateUnknown', error: { key: 'tailor.enhanced.errors.generateUnknown', reason } }
              : { stage: 'generateFailed', error: { key: 'tailor.enhanced.errors.generateFailed', reason } },
          )
        }
        return
      }
      if (!ticket.isCurrent()) {
        return
      }
      if (!isEnhancedResponse(proposal) || !proposal.proposalId || proposal.status !== 'PENDING') {
        patch({ stage: 'generateFailed', error: { key: 'tailor.enhanced.errors.generateFailed', reason: 'tailor.errors.unexpected' } })
        return
      }
      const record: EnhancedPendingRecord = {
        productMode: 'ENHANCED',
        proposalId: proposal.proposalId,
        jobId,
        resumeId,
        tailorResumeId,
        expiresAt: proposal.expiresAt,
        stale: false,
        savedAt: new Date().toISOString(),
        preparedAt,
      }
      if (ticket.userId !== null) {
        await saveEnhancedPending(ticket.userId, record).catch(() => {})
      }
      if (!ticket.isCurrent()) {
        return
      }
      patch({ stage: 'review', ...reviewFields(proposal), record, stale: false, staleReason: null, notice: null, error: null })
      void loadEvidence(ticket)
    },
    [loadEvidence, patch],
  )

  /** Prepares the DOCX once per job and resume, then scans. */
  const start = useCallback(
    async ({ job, resume }: EnhancedInput) => {
      const current = ref.current
      if (current.stage !== 'idle' && current.stage !== 'scanFailed' && current.stage !== 'gone') {
        return
      }
      const ticket = lock()
      if (!ticket) {
        return
      }
      try {
        const sameSelection = current.jobId === job.id && current.resumeId === resume.id
        const isPdf = (resume.fileType ?? '').toLowerCase() === 'pdf'
        let tailorResumeId = sameSelection ? current.tailorResumeId : null
        let preparedAt = sameSelection ? current.preparedAt : null
        // A structure saved since the internal copy was made means that copy is out of date.
        if (tailorResumeId !== null && isPdf && (await structureChanged(ticket, resume.id, preparedAt))) {
          tailorResumeId = null
          preparedAt = null
        }
        if (!ticket.isCurrent()) {
          return
        }
        patch({ ...INITIAL, stage: 'preparing', jobId: job.id, resumeId: resume.id, tailorResumeId, preparedAt })
        if (tailorResumeId === null) {
          if (isPdf) {
            const prepared = await preparePdf(ticket, resume.id)
            if (!prepared) {
              return
            }
            tailorResumeId = prepared.id
            preparedAt = prepared.preparedAt
          } else {
            tailorResumeId = resume.id
            preparedAt = new Date().toISOString()
          }
          patch({ tailorResumeId, preparedAt })
        }
        await scanInto(ticket, job.id, tailorResumeId)
      } finally {
        unlock()
      }
    },
    [lock, patch, preparePdf, scanInto, structureChanged, unlock],
  )

  const setAnswer = useCallback(
    (questionId: string, draft: AnswerDraft) => {
      if (ref.current.stage !== 'questions') {
        return
      }
      patch({ answers: { ...ref.current.answers, [questionId]: draft } })
    },
    [patch],
  )

  const setQuestionIndex = useCallback((index: number) => patch({ questionIndex: index }), [patch])

  /** Sends pending answers in order, stops at the first failure, then scans and creates the proposal. */
  const submit = useCallback(async () => {
    const current = ref.current
    const { jobId, tailorResumeId, opportunity } = current
    if (current.stage !== 'questions' || jobId === null || tailorResumeId === null || !opportunity) {
      return
    }
    const questions = visibleQuestions(opportunity.questions)
    const check = canSubmitAnswers(questions, current.answers)
    if (!check.ok) {
      patch({
        questionIndex: Math.max(0, questions.findIndex((question) => question.id === check.questionId)),
        failedQuestionId: check.questionId,
        error: { key: 'tailor.enhanced.errors.needRealDetails' },
      })
      return
    }
    const ticket = lock()
    if (!ticket) {
      return
    }
    try {
      patch({ stage: 'submitting', error: null, failedQuestionId: null })
      for (const item of answersToSubmit(questions, ref.current.answers)) {
        try {
          await enhancedApi.answer(jobId, tailorResumeId, item.payload)
        } catch (error) {
          if (ticket.isCurrent()) {
            patch({
              stage: 'questions',
              failedQuestionId: item.question.id,
              questionIndex: questions.indexOf(item.question),
              error: { key: 'tailor.enhanced.questions.failed', reason: enhancedErrorKey(error) },
            })
          }
          return
        }
        if (!ticket.isCurrent()) {
          return
        }
        const draft = ref.current.answers[item.question.id] ?? { intent: null, text: '' }
        patch({ answers: { ...ref.current.answers, [item.question.id]: { ...draft, submittedSignature: item.signature } } })
      }
      try {
        const refreshed = await enhancedApi.scan(jobId, tailorResumeId)
        if (!ticket.isCurrent()) {
          return
        }
        patch({ opportunity: refreshed })
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ stage: 'questions', error: { key: 'tailor.enhanced.errors.rescanFailed', reason: enhancedErrorKey(error) } })
        }
        return
      }
      await generateInto(ticket)
    } finally {
      unlock()
    }
  }, [generateInto, lock, patch, unlock])

  /** An explicit retry of proposal creation; answers are not sent again. */
  const retryGenerate = useCallback(async () => {
    const stage = ref.current.stage
    if (stage !== 'generateFailed' && stage !== 'generateUnknown') {
      return
    }
    const ticket = lock()
    if (!ticket) {
      return
    }
    try {
      await generateInto(ticket)
    } finally {
      unlock()
    }
  }, [generateInto, lock, unlock])

  const editable = () => REVIEWABLE.has(ref.current.stage)

  const toggleEntry = useCallback(
    (key: string) => {
      if (!editable()) {
        return
      }
      const selected = ref.current.selectedEntryKeys
      patch({ selectedEntryKeys: selected.includes(key) ? selected.filter((item) => item !== key) : [...selected, key] })
    },
    [patch],
  )

  const toggleSkill = useCallback(
    (id: string) => {
      if (!editable()) {
        return
      }
      const selected = ref.current.selectedSkillIds
      patch({ selectedSkillIds: selected.includes(id) ? selected.filter((item) => item !== id) : [...selected, id] })
    },
    [patch],
  )

  const startEdit = useCallback(
    (kind: 'entry' | 'skill', key: string) => {
      const { proposal, texts, editing } = ref.current
      if (!editable() || editing || !proposal) {
        return
      }
      if (kind === 'entry') {
        const review = proposal.entryReviews?.find((item) => item.canonicalEntryKey === key)
        if (review) {
          patch({ editing: { kind, key, draft: proposedJoined(review, texts) } })
        }
      } else {
        const review = proposal.skillReviews?.find((item) => item.skillReviewId === key)
        if (review) {
          patch({ editing: { kind, key, draft: texts[key] ?? review.proposedSkills } })
        }
      }
    },
    [patch],
  )

  const changeEdit = useCallback(
    (draft: string) => {
      const editing = ref.current.editing
      if (editing) {
        patch({ editing: { ...editing, draft } })
      }
    },
    [patch],
  )

  const saveEdit = useCallback(() => {
    const { editing, proposal, texts } = ref.current
    if (!editing || !proposal) {
      return
    }
    if (editing.kind === 'entry') {
      const review = proposal.entryReviews?.find((item) => item.canonicalEntryKey === editing.key)
      patch({ texts: review ? applyEntryText(texts, review, editing.draft) : texts, editing: null, error: null })
    } else {
      const review = proposal.skillReviews?.find((item) => item.skillReviewId === editing.key)
      patch({ texts: review ? applySkillText(texts, review, editing.draft) : texts, editing: null, error: null })
    }
  }, [patch])

  const cancelEdit = useCallback(() => patch({ editing: null }), [patch])

  const restoreAi = useCallback(
    (kind: 'entry' | 'skill', key: string) => {
      const { proposal, texts, aiOriginal } = ref.current
      if (!editable() || !proposal) {
        return
      }
      if (kind === 'entry') {
        const review = proposal.entryReviews?.find((item) => item.canonicalEntryKey === key)
        if (review) {
          patch({ texts: restoreEntryText(texts, review, aiOriginal) })
        }
      } else {
        const review = proposal.skillReviews?.find((item) => item.skillReviewId === key)
        if (review) {
          patch({ texts: restoreSkillText(texts, review, aiOriginal) })
        }
      }
    },
    [patch],
  )

  const confirm = useCallback(async () => {
    const current = ref.current
    const { proposal, record, jobId } = current
    if (!editable() || !proposal?.proposalId || !record || jobId === null || current.stale || current.editing) {
      return
    }
    if (current.selectedEntryKeys.length === 0 && current.selectedSkillIds.length === 0) {
      return
    }
    if (isProposalExpired(record.expiresAt)) {
      patch({ error: { key: 'tailor.enhanced.review.expired' } })
      return
    }
    const body = buildEnhancedConfirmBody(proposal, current.selectedEntryKeys, current.selectedSkillIds, current.texts, current.aiOriginal)
    const invalid = validateEnhancedEdits(body)
    if (invalid) {
      patch({ error: invalid })
      return
    }
    const ticket = lock()
    if (!ticket) {
      return
    }
    try {
      if (
        record.tailorResumeId !== record.resumeId &&
        (await structureChanged(ticket, record.resumeId, record.preparedAt ?? record.savedAt ?? null))
      ) {
        if (ticket.isCurrent()) {
          await markStale(ticket, 'structure')
        }
        return
      }
      if (!ticket.isCurrent()) {
        return
      }
      patch({ stage: 'confirming', error: null, notice: null })
      let result: TailoredResumeResponse
      try {
        result = await enhancedApi.confirm(jobId, record.proposalId, body)
      } catch (error) {
        if (!ticket.isCurrent()) {
          return
        }
        if (isConfirmInProgress(error)) {
          patch({ stage: 'confirmUnknown', error: { key: 'tailor.unknown.stillConfirming' } })
        } else if (isProposalUnavailable(error)) {
          await forgetRecord(ticket, jobId)
          patch({ stage: 'gone', pastExpiry: isProposalExpired(record.expiresAt), error: null })
        } else if (isAmbiguousTailorFailure(error)) {
          patch({ stage: 'confirmUnknown', error: { key: 'tailor.unknown.confirm', reason: enhancedErrorKey(error) } })
        } else {
          patch({ stage: 'confirmFailed', error: { key: 'tailor.enhanced.errors.confirmFailed', reason: enhancedErrorKey(error) } })
        }
        return
      }
      if (!ticket.isCurrent()) {
        return
      }
      await forgetRecord(ticket, jobId)
      const known = knownResultFrom('ENHANCED', record.resumeId, result)
      if (known && ticket.userId !== null) {
        await saveKnownResult(ticket.userId, known).catch(() => {})
      }
      if (ticket.isCurrent()) {
        patch({ stage: 'done', result, record: null, error: null })
      }
    } finally {
      unlock()
    }
  }, [forgetRecord, lock, markStale, patch, structureChanged, unlock])

  /** GET .../tailor-proposals/{id} after a confirmation whose response was lost. */
  const check = useCallback(async () => {
    const { stage, record, jobId } = ref.current
    if (stage !== 'confirmUnknown' || !record || jobId === null) {
      return
    }
    const ticket = lock()
    if (!ticket) {
      return
    }
    try {
      patch({ stage: 'checking', error: null })
      try {
        const latest = await tailorApi.proposal(jobId, record.proposalId)
        if (!ticket.isCurrent()) {
          return
        }
        if (!isEnhancedResponse(latest)) {
          await forgetRecord(ticket, jobId)
          patch({ stage: 'gone', pastExpiry: false })
        } else if (latest.status === 'PENDING') {
          // Selections and edits stay as the user left them.
          patch({ stage: 'review', notice: 'notConfirmed' })
        } else {
          patch({ stage: 'confirmUnknown', error: { key: 'tailor.unknown.stillConfirming' } })
        }
      } catch (error) {
        if (!ticket.isCurrent()) {
          return
        }
        if (error instanceof ApiError && error.status === 404) {
          await forgetRecord(ticket, jobId)
          patch({ stage: 'gone', pastExpiry: isProposalExpired(record.expiresAt) })
        } else {
          patch({ stage: 'confirmUnknown', error: { key: 'tailor.unknown.confirm', reason: enhancedErrorKey(error) } })
        }
      }
    } finally {
      unlock()
    }
  }, [forgetRecord, lock, patch, unlock])

  /** Reopens this account's ENHANCED proposal for the job. Resolves true when one was found. */
  const restore = useCallback(
    async (jobId: number): Promise<boolean> => {
      const current = ref.current
      if (current.stage !== 'idle' && current.stage !== 'restoreFailed') {
        return false
      }
      const ticket = lock()
      if (!ticket) {
        return false
      }
      try {
        if (ticket.userId === null) {
          return false
        }
        const record =
          current.stage === 'restoreFailed' && current.record ? current.record : await loadEnhancedPending(ticket.userId, jobId).catch(() => null)
        if (!ticket.isCurrent() || !record || record.jobId !== jobId) {
          return false
        }
        // Restoring never prepares the DOCX again; it only checks whether the proposal is out of date.
        const outdated =
          record.tailorResumeId !== record.resumeId &&
          (await structureChanged(ticket, record.resumeId, record.preparedAt ?? record.savedAt ?? null))
        if (!ticket.isCurrent()) {
          return false
        }
        patch({
          ...INITIAL,
          stage: 'restoring',
          jobId,
          resumeId: record.resumeId,
          tailorResumeId: record.tailorResumeId,
          preparedAt: record.preparedAt ?? null,
          record,
          stale: record.stale || outdated,
          staleReason: outdated ? 'structure' : record.stale ? (record.staleReason ?? 'evidence') : null,
        })
        let latest: TailoredResumeResponse
        try {
          latest = await tailorApi.proposal(jobId, record.proposalId)
        } catch (error) {
          if (!ticket.isCurrent()) {
            return true
          }
          if (error instanceof ApiError && error.status === 404) {
            await forgetRecord(ticket, jobId)
            patch({ stage: 'gone', pastExpiry: isProposalExpired(record.expiresAt), record: null })
          } else {
            patch({ stage: 'restoreFailed', error: { key: 'tailor.restoreFailed', reason: enhancedErrorKey(error) } })
          }
          return true
        }
        if (!ticket.isCurrent()) {
          return true
        }
        if (!isEnhancedResponse(latest)) {
          await forgetRecord(ticket, jobId)
          patch({ stage: 'gone', pastExpiry: false, record: null })
          return true
        }
        if (latest.status === 'PENDING') {
          patch({ stage: 'review', ...reviewFields(latest), notice: 'restored' })
          void loadEvidence(ticket)
          // Requirement context for the cards; read-only, and the DOCX is not prepared again.
          if (!outdated) {
            void enhancedApi
              .scan(jobId, record.tailorResumeId)
              .then((map) => {
                if (ticket.isCurrent()) {
                  patch({ opportunity: map })
                }
              })
              .catch(() => {})
          }
        } else {
          patch({ stage: 'confirmUnknown', ...reviewFields(latest), error: { key: 'tailor.unknown.stillConfirming' } })
        }
        return true
      } finally {
        unlock()
      }
    },
    [forgetRecord, loadEvidence, lock, patch, structureChanged, unlock],
  )

  const correctEvidence = useCallback(
    async (usage: V3EvidenceUsage, text: string): Promise<boolean> => {
      const { resumeId, evidence, evidenceBusy, stage } = ref.current
      const item = evidence?.find((row) => row.id === usage.evidenceItemId)
      if (!REVIEWABLE.has(stage) || evidenceBusy || resumeId === null || !item || !text.trim()) {
        return false
      }
      const ticket = guard.capture()
      patch({ evidenceBusy: true, evidenceError: null })
      try {
        await evidenceApi.save(resumeId, {
          topic: item.topicKey || item.topic,
          category: item.category,
          answerStatus: 'CONFIRMED_USED',
          entryKey: item.entryKey,
          relatedNodeIds: item.relatedNodeIds ?? [],
          context: item.context ?? '',
          action: text.trim(),
          result: item.result ?? '',
          metric: item.metric ?? '',
          confirmed: true,
        })
        if (!ticket.isCurrent()) {
          return false
        }
        await markStale(ticket, 'evidence')
        await loadEvidence(ticket)
        return ticket.isCurrent()
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ evidenceError: { key: 'tailor.enhanced.evidence.failed', reason: enhancedErrorKey(error) } })
        }
        return false
      } finally {
        if (ticket.isCurrent()) {
          patch({ evidenceBusy: false })
        }
      }
    },
    [guard, loadEvidence, markStale, patch],
  )

  const deleteEvidence = useCallback(
    async (usage: V3EvidenceUsage): Promise<boolean> => {
      const { resumeId, evidenceBusy, stage } = ref.current
      if (!REVIEWABLE.has(stage) || evidenceBusy || resumeId === null) {
        return false
      }
      const ticket = guard.capture()
      patch({ evidenceBusy: true, evidenceError: null })
      try {
        await evidenceApi.remove(resumeId, usage.evidenceItemId)
        if (!ticket.isCurrent()) {
          return false
        }
        const { proposal, evidence } = ref.current
        patch({
          evidence: (evidence ?? []).filter((item) => item.id !== usage.evidenceItemId),
          proposal: proposal
            ? { ...proposal, evidenceUsage: (proposal.evidenceUsage ?? []).filter((item) => item.evidenceItemId !== usage.evidenceItemId) }
            : proposal,
        })
        await markStale(ticket, 'evidence')
        return true
      } catch (error) {
        if (ticket.isCurrent()) {
          patch({ evidenceError: { key: 'tailor.enhanced.evidence.failed', reason: enhancedErrorKey(error) } })
        }
        return false
      } finally {
        if (ticket.isCurrent()) {
          patch({ evidenceBusy: false })
        }
      }
    },
    [guard, markStale, patch],
  )

  /** Leaves the proposal unconfirmed on the server; saved evidence is kept. */
  const discard = useCallback(async () => {
    if (busyRef.current) {
      return
    }
    const { jobId, resumeId, tailorResumeId, preparedAt, record } = ref.current
    if (record) {
      await forgetRecord(guard.capture(), jobId)
    }
    patch({ ...INITIAL, jobId, resumeId, tailorResumeId, preparedAt })
  }, [forgetRecord, guard, patch])

  /**
   * Scans again, for stale or unavailable proposals. The prepared DOCX is reused, unless a
   * PDF structure was saved since it was made: then the internal copy is prepared again.
   */
  const regenerate = useCallback(async () => {
    const { jobId, tailorResumeId, resumeId, stage, record, preparedAt } = ref.current
    if (!REVIEWABLE.has(stage) && stage !== 'gone' && stage !== 'generateFailed') {
      return
    }
    const ticket = lock()
    if (!ticket) {
      return
    }
    try {
      if (record) {
        await forgetRecord(ticket, jobId)
      }
      if (jobId === null || tailorResumeId === null || resumeId === null) {
        patch({ ...INITIAL, jobId, resumeId })
        return
      }
      let docId = tailorResumeId
      let prepared = preparedAt
      if (tailorResumeId !== resumeId && (await structureChanged(ticket, resumeId, preparedAt ?? record?.savedAt ?? null))) {
        if (!ticket.isCurrent()) {
          return
        }
        patch({ ...INITIAL, stage: 'preparing', jobId, resumeId })
        const next = await preparePdf(ticket, resumeId)
        if (!next) {
          return
        }
        docId = next.id
        prepared = next.preparedAt
      }
      if (!ticket.isCurrent()) {
        return
      }
      patch({ ...INITIAL, jobId, resumeId, tailorResumeId: docId, preparedAt: prepared })
      await scanInto(ticket, jobId, docId)
    } finally {
      unlock()
    }
  }, [forgetRecord, lock, patch, preparePdf, scanInto, structureChanged, unlock])

  const reset = useCallback(() => {
    if (!busyRef.current) {
      const { jobId, resumeId, tailorResumeId, preparedAt } = ref.current
      patch({ ...INITIAL, jobId, resumeId, tailorResumeId, preparedAt })
    }
  }, [patch])

  const hasUnsaved =
    (state.stage === 'questions' && Boolean(state.opportunity) && hasUnsubmittedAnswers(state.opportunity?.questions ?? [], state.answers)) ||
    (REVIEWABLE.has(state.stage) &&
      (state.editing !== null || Object.keys(state.texts).some((key) => state.texts[key] !== state.aiOriginal[key])))

  return {
    state,
    busy: BUSY.has(state.stage) || state.evidenceBusy,
    hasUnsaved,
    start,
    setAnswer,
    setQuestionIndex,
    submit,
    retryGenerate,
    toggleEntry,
    toggleSkill,
    startEdit,
    changeEdit,
    saveEdit,
    cancelEdit,
    restoreAi,
    confirm,
    check,
    restore,
    correctEvidence,
    deleteEvidence,
    discard,
    regenerate,
    reset,
  }
}

export type EnhancedController = ReturnType<typeof useEnhancedTailor>
