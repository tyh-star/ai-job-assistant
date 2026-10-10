import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { draftsApi } from '../api/endpoints'
import { builderErrorKey } from '../api/errors'
import type { ResumeResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { resumeKeys, useDraft } from '../resumes/queries'
import { afterDraftWritten, afterResumeGenerated } from './cache'
import {
  buildUpdateRequest,
  draftReadiness,
  formFromResponse,
  formSignature,
  isAmbiguousFailure,
  isSaveConflict,
  newIdempotencyKey,
  validateForm,
  type DraftForm,
  type FieldProblems,
} from './model'
import {
  clearPendingGeneration,
  loadPendingGeneration,
  savePendingGeneration,
  type PendingGeneration,
} from './pendingGeneration'
import { useSessionGuard, type SessionTicket } from './sessionGuard'
import type { BuilderDraftResponse } from './types'

export type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'invalid' }
  | { kind: 'failed'; errorKey: string }
  | { kind: 'conflict' }

export type GenerateState =
  | { kind: 'idle' }
  | { kind: 'generating' }
  /** `signature` is the saved form the file was built from. */
  | { kind: 'done'; resume: ResumeResponse; signature: string }
  | { kind: 'failed'; errorKey: string }
  /** The request may have created a file. Its key is kept (and persisted) until resolved. */
  | { kind: 'unknown'; errorKey: string | null; restored: boolean }

/**
 * none: no unresolved request. confirmable: resending its key asks the server about that
 * exact request. stale: the draft changed since, so resending could build a file from
 * newer content and present it as the old one -- only "generate a new version" is offered.
 */
export type PendingStatus = 'none' | 'confirmable' | 'stale'

const NO_PROBLEMS: FieldProblems = {}

/**
 * Local form state for one draft. Nothing is saved until the user asks: "saved" is
 * only ever shown after the server accepted this exact form.
 */
export function useDraftEditor(draftId: number, untitled: string) {
  const userId = useAuth().user?.userId ?? null
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const query = useDraft(draftId)

  const identity = `${userId}:${draftId}`
  const [loadedFor, setLoadedFor] = useState<string | null>(null)
  const [form, setForm] = useState<DraftForm | null>(null)
  const [baseline, setBaseline] = useState('')
  const [savedVersion, setSavedVersion] = useState(0)
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' })
  const [generateState, setGenerateState] = useState<GenerateState>({ kind: 'idle' })
  const [pending, setPending] = useState<PendingGeneration | null>(null)
  const [serverMovedOn, setServerMovedOn] = useState(false)
  const [showProblems, setShowProblems] = useState(false)

  const versionRef = useRef(0)
  const savingRef = useRef(false)
  const generatingRef = useRef(false)
  const pendingRef = useRef<PendingGeneration | null>(null)

  const data = query.data as BuilderDraftResponse | undefined
  useEffect(() => {
    if (!data || loadedFor === identity) {
      return
    }
    const next = formFromResponse(data)
    versionRef.current = data.version
    pendingRef.current = null
    setPending(null)
    setServerMovedOn(false)
    setForm(next)
    setBaseline(formSignature(next))
    setSavedVersion(data.version)
    setSaveState({ kind: 'idle' })
    setGenerateState({ kind: 'idle' })
    setShowProblems(false)
    setLoadedFor(identity)

    // An earlier visit (or app session) may have left a request whose result was never seen.
    if (userId !== null) {
      const ticket = guard.capture()
      loadPendingGeneration(userId, draftId)
        .then((record) => {
          if (!record || !ticket.isCurrent() || pendingRef.current) {
            return
          }
          pendingRef.current = record
          setPending(record)
          setGenerateState((state) => (state.kind === 'idle' ? { kind: 'unknown', errorKey: null, restored: true } : state))
        })
        .catch(() => {})
    }
  }, [data, draftId, guard, identity, loadedFor, userId])

  // A form that belongs to another account or draft is never shown.
  const current = loadedFor === identity ? form : null
  const dirty = current !== null && formSignature(current) !== baseline
  const allProblems = useMemo(() => (current ? validateForm(current) : NO_PROBLEMS), [current])
  const problems = showProblems ? allProblems : NO_PROBLEMS
  const pendingStatus: PendingStatus = !pending
    ? 'none'
    : dirty || serverMovedOn || pending.draftVersion !== savedVersion
      ? 'stale'
      : 'confirmable'

  const update = useCallback((change: (form: DraftForm) => DraftForm) => {
    setForm((previous) => (previous ? change(previous) : previous))
  }, [])

  /** Resolves to the signature of the saved form, or null when nothing was saved. */
  const persist = useCallback(async (): Promise<string | null> => {
    if (!current || savingRef.current) {
      return null
    }
    if (Object.keys(validateForm(current)).length > 0) {
      setShowProblems(true)
      setSaveState({ kind: 'invalid' })
      return null
    }
    const ticket = guard.capture()
    const snapshot = formSignature(current)
    savingRef.current = true
    setSaveState({ kind: 'saving' })
    try {
      const saved = await draftsApi.update(draftId, buildUpdateRequest(current, versionRef.current, untitled))
      if (!ticket.isCurrent()) {
        return null
      }
      const savedForm = formFromResponse(saved)
      const savedSignature = formSignature(savedForm)
      versionRef.current = saved.version
      setSavedVersion(saved.version)
      setBaseline(savedSignature)
      // Edits typed while the request was out stay on screen and stay unsaved.
      setForm((previous) => (previous && formSignature(previous) === snapshot ? savedForm : previous))
      setGenerateState((state) => (state.kind === 'failed' ? { kind: 'idle' } : state))
      setShowProblems(false)
      setSaveState({ kind: 'saved' })
      void afterDraftWritten(queryClient, ticket.userId, saved)
      return savedSignature
    } catch (error) {
      if (ticket.isCurrent()) {
        setSaveState(isSaveConflict(error) ? { kind: 'conflict' } : { kind: 'failed', errorKey: builderErrorKey(error) })
      }
      return null
    } finally {
      savingRef.current = false
    }
  }, [current, draftId, guard, queryClient, untitled])

  const save = useCallback(async () => (await persist()) !== null, [persist])

  const forgetPending = useCallback(async (ticket: SessionTicket) => {
    pendingRef.current = null
    setPending(null)
    setServerMovedOn(false)
    if (ticket.userId !== null) {
      await clearPendingGeneration(ticket.userId, draftId).catch(() => {})
    }
  }, [draftId])

  /** Sends one generate request and settles what its outcome means for the kept key. */
  const send = useCallback(
    async (ticket: SessionTicket, record: PendingGeneration, signature: string) => {
      try {
        const resume = await draftsApi.generate(draftId, { idempotencyKey: record.idempotencyKey })
        if (!ticket.isCurrent()) {
          return
        }
        await forgetPending(ticket)
        setGenerateState({ kind: 'done', resume, signature })
        void afterResumeGenerated(queryClient, ticket.userId, resume)
      } catch (error) {
        if (!ticket.isCurrent()) {
          return
        }
        if (isAmbiguousFailure(error)) {
          setGenerateState({ kind: 'unknown', errorKey: builderErrorKey(error), restored: false })
          void afterResumeGenerated(queryClient, ticket.userId, null)
        } else {
          await forgetPending(ticket)
          setGenerateState({ kind: 'failed', errorKey: builderErrorKey(error) })
        }
      }
    },
    [draftId, forgetPending, queryClient],
  )

  /** A new generate action from the current content, with a new key. */
  const generateNew = useCallback(async () => {
    if (!current || generatingRef.current || savingRef.current || saveState.kind === 'conflict') {
      return
    }
    if (!draftReadiness(current).ready) {
      return
    }
    const ticket = guard.capture()
    generatingRef.current = true
    setGenerateState({ kind: 'generating' })
    try {
      // The server builds the file from the saved draft, so unsaved edits go first.
      const signature = dirty ? await persist() : baseline
      if (!ticket.isCurrent()) {
        return
      }
      if (signature === null) {
        setGenerateState(pendingRef.current ? { kind: 'unknown', errorKey: null, restored: false } : { kind: 'idle' })
        return
      }
      await forgetPending(ticket)
      const record: PendingGeneration = { idempotencyKey: newIdempotencyKey(), draftVersion: versionRef.current }
      pendingRef.current = record
      setPending(record)
      if (ticket.userId !== null) {
        // Written before sending, so the key survives a crash or a closed app mid-request.
        await savePendingGeneration(ticket.userId, draftId, record).catch(() => {})
      }
      if (!ticket.isCurrent()) {
        return
      }
      await send(ticket, record, signature)
    } finally {
      generatingRef.current = false
    }
  }, [baseline, current, dirty, draftId, forgetPending, guard, persist, saveState.kind, send])

  /** Resends the unresolved request's key, but only while the draft is still the version it used. */
  const confirmPrevious = useCallback(async () => {
    const record = pendingRef.current
    if (!record || pendingStatus !== 'confirmable' || generatingRef.current || savingRef.current) {
      return
    }
    const ticket = guard.capture()
    generatingRef.current = true
    setGenerateState({ kind: 'generating' })
    try {
      let latest: BuilderDraftResponse
      try {
        // The website or another device may have saved since; the key would then build newer content.
        latest = (await draftsApi.get(draftId)) as unknown as BuilderDraftResponse
      } catch (error) {
        if (ticket.isCurrent()) {
          setGenerateState({ kind: 'unknown', errorKey: builderErrorKey(error), restored: false })
        }
        return
      }
      if (!ticket.isCurrent()) {
        return
      }
      if (latest.version !== record.draftVersion) {
        setServerMovedOn(true)
        setGenerateState({ kind: 'unknown', errorKey: null, restored: false })
        return
      }
      queryClient.setQueryData(resumeKeys.draft(ticket.userId, draftId), latest)
      await send(ticket, record, baseline)
    } finally {
      generatingRef.current = false
    }
  }, [baseline, draftId, guard, pendingStatus, queryClient, send])

  /** Throws away local edits and loads the version saved on the server. */
  const reload = useCallback(async () => {
    const ticket = guard.capture()
    const result = await query.refetch()
    if (ticket.isCurrent() && result.data) {
      setLoadedFor(null)
    }
  }, [guard, query])

  return {
    query,
    form: current,
    update,
    dirty,
    problems,
    problemCount: showProblems ? Object.keys(allProblems).length : 0,
    readiness: current ? draftReadiness(current) : null,
    saveState,
    generateState,
    pendingStatus,
    save,
    generateNew,
    confirmPrevious,
    reload,
    /** True when the shown result was built from exactly what is on screen now. */
    resultIsCurrent: generateState.kind === 'done' && current !== null && formSignature(current) === generateState.signature,
    busy: saveState.kind === 'saving' || generateState.kind === 'generating',
  }
}
