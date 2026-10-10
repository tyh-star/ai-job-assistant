import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { tailorApi } from '../api/endpoints'
import { ApiError } from '../api/client'
import type { JobResponse, ResumeResponse } from '../api/types'
import { useSessionGuard, type SessionTicket } from '../builder/sessionGuard'
import { resumeKeys } from '../resumes/queries'
import { loadStructureSavedAt, structureChangedSince } from '../resumes/structure'
import {
  confirmNodeIds,
  isAmbiguousTailorFailure,
  isConfirmInProgress,
  isProposalExpired,
  isProposalUnavailable,
  prepareErrorKey,
  tailorErrorKey,
} from './model'
import { clearPendingProposal, loadPendingProposal, savePendingProposal, type PendingProposal } from './pendingProposal'
import { knownResultFrom, saveKnownResult } from './records'
import type { TailorMode, TailoredResumeResponse } from './types'

export type RunStep = 'recognizing' | 'preparing' | 'tailoring'

export type PreviewNotice = 'restored' | 'notConfirmed' | null

export type TailorPhase =
  | { kind: 'idle' }
  | { kind: 'restoring'; jobId: number }
  | { kind: 'restoreFailed'; record: PendingProposal; errorKey: string }
  | { kind: 'running'; mode: TailorMode; step: RunStep }
  /** A file exists: a CONSERVATIVE run, or a confirmed AGGRESSIVE proposal. */
  | { kind: 'done'; result: TailoredResumeResponse }
  /** AGGRESSIVE proposal waiting for the user. No file exists. */
  /** `outdated`: the PDF's recognized structure was saved after this proposal was made; never confirmable. */
  | { kind: 'preview'; proposal: TailoredResumeResponse; record: PendingProposal; notice: PreviewNotice; outdated?: boolean }
  | { kind: 'confirming'; proposal: TailoredResumeResponse; record: PendingProposal }
  | { kind: 'checking'; proposal: TailoredResumeResponse | null; record: PendingProposal }
  | { kind: 'failed'; stage: 'prepare' | 'run'; errorKey: string }
  /** The proposal is still pending; confirming again is allowed. */
  | { kind: 'confirmFailed'; proposal: TailoredResumeResponse; record: PendingProposal; errorKey: string; outdated?: boolean }
  | { kind: 'unknown'; stage: 'run'; mode: TailorMode; errorKey: string }
  | { kind: 'unknown'; stage: 'confirm'; proposal: TailoredResumeResponse | null; record: PendingProposal; errorKey: string | null }
  | { kind: 'gone'; reason: 'expired' | 'unavailable' | 'maybeConfirmed' }

export type RunInput = { job: Pick<JobResponse, 'id'>; resume: Pick<ResumeResponse, 'id' | 'fileType'>; mode: TailorMode }

const BUSY_KINDS = new Set<TailorPhase['kind']>(['running', 'confirming', 'checking', 'restoring'])

/**
 * One tailoring session. Every server call is started at most once per user action,
 * results are dropped when the account changed meanwhile, and an AGGRESSIVE proposal id
 * is kept so the preview can be reopened or its confirmation checked later.
 */
export function useTailorRun() {
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const [phase, setPhaseState] = useState<TailorPhase>({ kind: 'idle' })
  const phaseRef = useRef<TailorPhase>(phase)
  const busyRef = useRef(false)

  const setPhase = useCallback((next: TailorPhase) => {
    phaseRef.current = next
    setPhaseState(next)
  }, [])

  const start = useCallback((): SessionTicket | null => {
    if (busyRef.current) {
      return null
    }
    busyRef.current = true
    return guard.capture()
  }, [guard])

  const forget = useCallback(async (ticket: SessionTicket, record: PendingProposal) => {
    if (ticket.userId !== null) {
      await clearPendingProposal(ticket.userId, record.jobId).catch(() => {})
    }
  }, [])

  /** A PDF's structure saved on this device after the proposal: its internal DOCX is rebuilt on the next preparation. */
  const isOutdated = useCallback(async (ticket: SessionTicket, record: PendingProposal) => {
    if (ticket.userId === null) {
      return false
    }
    const savedAt = await loadStructureSavedAt(ticket.userId, record.resumeId).catch(() => null)
    return structureChangedSince(savedAt, record.savedAt)
  }, [])

  const goneReason = (record: PendingProposal, confirmMayHaveRun: boolean): 'expired' | 'unavailable' | 'maybeConfirmed' =>
    isProposalExpired(record.expiresAt) ? 'expired' : confirmMayHaveRun ? 'maybeConfirmed' : 'unavailable'

  const run = useCallback(
    async ({ job, resume, mode }: RunInput) => {
      const current = phaseRef.current.kind
      if (current !== 'idle' && current !== 'failed' && current !== 'gone' && current !== 'unknown') {
        return
      }
      const ticket = start()
      if (!ticket) {
        return
      }
      try {
        let tailorResumeId = resume.id
        if ((resume.fileType ?? '').toLowerCase() === 'pdf') {
          try {
            setPhase({ kind: 'running', mode, step: 'recognizing' })
            await tailorApi.structure(resume.id)
            if (!ticket.isCurrent()) {
              return
            }
            setPhase({ kind: 'running', mode, step: 'preparing' })
            const canonical = await tailorApi.canonicalize(resume.id)
            if (!ticket.isCurrent()) {
              return
            }
            tailorResumeId = canonical.id
            void queryClient.invalidateQueries({ queryKey: resumeKeys.files(ticket.userId), exact: true })
          } catch (error) {
            if (ticket.isCurrent()) {
              setPhase({ kind: 'failed', stage: 'prepare', errorKey: prepareErrorKey(error) })
            }
            return
          }
        }

        setPhase({ kind: 'running', mode, step: 'tailoring' })
        let result: TailoredResumeResponse
        try {
          result = await tailorApi.run(job.id, tailorResumeId, mode)
        } catch (error) {
          if (ticket.isCurrent()) {
            setPhase(
              isAmbiguousTailorFailure(error)
                ? { kind: 'unknown', stage: 'run', mode, errorKey: tailorErrorKey(error) }
                : { kind: 'failed', stage: 'run', errorKey: tailorErrorKey(error) },
            )
          }
          return
        }
        if (!ticket.isCurrent()) {
          return
        }
        if (result.downloadUrl) {
          const known = knownResultFrom(mode, resume.id, result)
          if (known && ticket.userId !== null) {
            await saveKnownResult(ticket.userId, known).catch(() => {})
          }
          if (ticket.isCurrent()) {
            setPhase({ kind: 'done', result })
          }
          return
        }
        if (mode === 'AGGRESSIVE' && result.proposalId && result.status === 'PENDING') {
          const record: PendingProposal = {
            proposalId: result.proposalId,
            jobId: job.id,
            resumeId: resume.id,
            expiresAt: result.expiresAt,
            savedAt: new Date().toISOString(),
          }
          if (ticket.userId !== null) {
            await savePendingProposal(ticket.userId, record).catch(() => {})
          }
          if (ticket.isCurrent()) {
            setPhase({ kind: 'preview', proposal: result, record, notice: null })
          }
          return
        }
        setPhase({ kind: 'failed', stage: 'run', errorKey: 'tailor.errors.unexpected' })
      } finally {
        busyRef.current = false
      }
    },
    [queryClient, setPhase, start],
  )

  const confirm = useCallback(async () => {
    const current = phaseRef.current
    if (current.kind !== 'preview' && current.kind !== 'confirmFailed') {
      return
    }
    const { proposal, record } = current
    const nodeIds = confirmNodeIds(proposal.changes)
    if (nodeIds.length === 0 || current.outdated) {
      return
    }
    const ticket = start()
    if (!ticket) {
      return
    }
    try {
      if (isProposalExpired(record.expiresAt)) {
        await forget(ticket, record)
        setPhase({ kind: 'gone', reason: 'expired' })
        return
      }
      if (await isOutdated(ticket, record)) {
        if (ticket.isCurrent()) {
          setPhase({ kind: 'preview', proposal, record, notice: null, outdated: true })
        }
        return
      }
      setPhase({ kind: 'confirming', proposal, record })
      try {
        const result = await tailorApi.confirm(record.jobId, record.proposalId, { selectedNodeIds: nodeIds })
        if (!ticket.isCurrent()) {
          return
        }
        await forget(ticket, record)
        const known = knownResultFrom('AGGRESSIVE', record.resumeId, result)
        if (known && ticket.userId !== null) {
          await saveKnownResult(ticket.userId, known).catch(() => {})
        }
        if (ticket.isCurrent()) {
          setPhase({ kind: 'done', result })
        }
      } catch (error) {
        if (!ticket.isCurrent()) {
          return
        }
        if (isConfirmInProgress(error)) {
          setPhase({ kind: 'unknown', stage: 'confirm', proposal, record, errorKey: 'tailor.errors.confirmInProgress' })
        } else if (isProposalUnavailable(error)) {
          await forget(ticket, record)
          setPhase({ kind: 'gone', reason: goneReason(record, false) })
        } else if (isAmbiguousTailorFailure(error)) {
          setPhase({ kind: 'unknown', stage: 'confirm', proposal, record, errorKey: tailorErrorKey(error) })
        } else {
          setPhase({ kind: 'confirmFailed', proposal, record, errorKey: tailorErrorKey(error) })
        }
      }
    } finally {
      busyRef.current = false
    }
  }, [forget, isOutdated, setPhase, start])

  /** Asks GET .../tailor-proposals/{id} what happened to a confirmation whose response was lost. */
  const check = useCallback(async () => {
    const current = phaseRef.current
    if (current.kind !== 'unknown' || current.stage !== 'confirm') {
      return
    }
    const { record } = current
    const ticket = start()
    if (!ticket) {
      return
    }
    try {
      setPhase({ kind: 'checking', proposal: current.proposal, record })
      try {
        const latest = await tailorApi.proposal(record.jobId, record.proposalId)
        if (!ticket.isCurrent()) {
          return
        }
        if (latest.status === 'PENDING') {
          const outdated = await isOutdated(ticket, record)
          if (ticket.isCurrent()) {
            setPhase({ kind: 'preview', proposal: latest, record, notice: 'notConfirmed', outdated })
          }
        } else {
          setPhase({ kind: 'unknown', stage: 'confirm', proposal: latest, record, errorKey: 'tailor.errors.confirmInProgress' })
        }
      } catch (error) {
        if (!ticket.isCurrent()) {
          return
        }
        if (error instanceof ApiError && error.status === 404) {
          // Confirmed and expired proposals both 404; there is no route that returns the file.
          await forget(ticket, record)
          setPhase({ kind: 'gone', reason: goneReason(record, true) })
        } else {
          setPhase({ kind: 'unknown', stage: 'confirm', proposal: current.proposal, record, errorKey: tailorErrorKey(error) })
        }
      }
    } finally {
      busyRef.current = false
    }
  }, [forget, isOutdated, setPhase, start])

  /** Reopens a preview left from an earlier visit, for this account and job. */
  const restore = useCallback(
    async (jobId: number) => {
      const current = phaseRef.current
      if (current.kind !== 'idle' && current.kind !== 'restoreFailed') {
        return
      }
      const ticket = start()
      if (!ticket) {
        return
      }
      try {
        if (ticket.userId === null) {
          return
        }
        const record = current.kind === 'restoreFailed' ? current.record : await loadPendingProposal(ticket.userId, jobId).catch(() => null)
        if (!ticket.isCurrent() || !record || record.jobId !== jobId) {
          return
        }
        setPhase({ kind: 'restoring', jobId })
        try {
          const latest = await tailorApi.proposal(record.jobId, record.proposalId)
          if (!ticket.isCurrent()) {
            return
          }
          if ((latest.productMode ?? '').toUpperCase() === 'ENHANCED') {
            // Never show an ENHANCED proposal as an AGGRESSIVE preview.
            await forget(ticket, record)
            setPhase({ kind: 'gone', reason: 'unavailable' })
          } else if (latest.status === 'PENDING') {
            const outdated = await isOutdated(ticket, record)
            if (ticket.isCurrent()) {
              setPhase({ kind: 'preview', proposal: latest, record, notice: 'restored', outdated })
            }
          } else {
            setPhase({ kind: 'unknown', stage: 'confirm', proposal: latest, record, errorKey: 'tailor.errors.confirmInProgress' })
          }
        } catch (error) {
          if (!ticket.isCurrent()) {
            return
          }
          if (error instanceof ApiError && error.status === 404) {
            await forget(ticket, record)
            setPhase({ kind: 'gone', reason: goneReason(record, true) })
          } else {
            setPhase({ kind: 'restoreFailed', record, errorKey: tailorErrorKey(error) })
          }
        }
      } finally {
        busyRef.current = false
      }
    },
    [forget, isOutdated, setPhase, start],
  )

  /** Drops the preview without calling the confirm endpoint. */
  const discard = useCallback(async () => {
    const current = phaseRef.current
    if (busyRef.current) {
      return
    }
    if (current.kind === 'preview' || current.kind === 'confirmFailed' || current.kind === 'restoreFailed') {
      await forget(guard.capture(), current.record)
    }
    setPhase({ kind: 'idle' })
  }, [forget, guard, setPhase])

  const reset = useCallback(() => {
    if (!busyRef.current) {
      setPhase({ kind: 'idle' })
    }
  }, [setPhase])

  return { phase, busy: BUSY_KINDS.has(phase.kind), run, confirm, check, restore, discard, reset }
}
