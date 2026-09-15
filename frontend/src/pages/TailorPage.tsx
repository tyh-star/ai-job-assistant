import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  analyzeTailorFit,
  answerEnhancedQuestion,
  confirmTailorProposal,
  continueTailorProposal,
  createTailorProposal,
  getTailorProposal,
  listJobs,
  refreshJobRequirementProfile,
  scanEnhancedOpportunity,
  tailorResume,
} from '../api/jobs'
import {
  canonicalizeResume,
  deleteResumeEvidence,
  downloadByUrl,
  getResumeStructure,
  listResumeEvidence,
  listResumes,
  saveResumeEvidence,
} from '../api/resumes'
import type {
  JobResponse,
  ResumeEvidenceItem,
  ResumeResponse,
  TailorAnalysisResponse,
  TailorChange,
  TailoredResumeResponse,
  V3EvidenceUsage,
  V3OpportunityMap,
} from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import { displayFileName } from '../resumes/fileName'
import {
  IncompletePdfStructureError,
  ResumeFileUnavailableError,
  runTailorFlow,
  type TailorProgress,
} from '../tailor/runTailorFlow'
import {
  buildEditedChanges,
  defaultSelectedNodeIds,
  hasUnsavedEdit,
  suggestionMap,
} from '../tailor/reviewEdits'
import {
  answersForSubmit,
  canSubmitAnswers,
  type EnhancedAnswerDraft,
  visibleQuestions,
} from '../tailor/enhancedAnswers'
import {
  buildEditedEntries,
  buildEditedSkillReviews,
  defaultSelectedEntryKeys,
  defaultSelectedSkillReviewIds,
  hasNoHighValueRewrite,
  isEnhancedProposal,
  reviewSuggestionMap,
  skillSuggestionMap,
} from '../tailor/enhancedConfirm'
import {
  hasLocalUnsavedWork,
  loadSession,
  saveSession,
} from '../tailor/enhancedSession'
import type { EnhancedPhase, ProductUiMode } from '../tailor/productMode'
import { ProductModeCards } from './ProductModeCards'
import { EnhancedStepList } from './EnhancedStepList'
import { EnhancedOpportunityMap } from './EnhancedOpportunityMap'
import { EnhancedQuestionsPanel } from './EnhancedQuestionsPanel'
import { EnhancedReviewPanel } from './EnhancedReviewPanel'
import { EnhancedEvidenceUsagePanel } from './EnhancedEvidenceUsagePanel'
import { EnhancedSkillsReviewPanel } from './EnhancedSkillsReviewPanel'

export function TailorPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const [resumes, setResumes] = useState<ResumeResponse[]>([])
  const [jobs, setJobs] = useState<JobResponse[]>([])
  const [resumeId, setResumeId] = useState(searchParams.get('resumeId') ?? '')
  const [jobId, setJobId] = useState(searchParams.get('jobId') ?? '')
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState<TailorProgress | null>(null)
  const [error, setError] = useState('')
  const [structureResumeId, setStructureResumeId] = useState<number | null>(null)
  const [result, setResult] = useState<TailoredResumeResponse | null>(null)
  const [analysis, setAnalysis] = useState<TailorAnalysisResponse | null>(null)
  const [analyzing, setAnalyzing] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [suggestionText, setSuggestionText] = useState<Record<string, string>>({})
  const [aiOriginal, setAiOriginal] = useState<Record<string, string>>({})
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const confirmingRef = useRef(false)
  const continuingRef = useRef(false)
  const scanCancelRef = useRef(false)
  const scanningRef = useRef(false)
  const generatingRef = useRef(false)
  const restoredKeyRef = useRef('')
  const [continuing, setContinuing] = useState(false)
  const [continueNotice, setContinueNotice] = useState('')
  const [scanProgress, setScanProgress] = useState<{ checked: number; total: number; found: number } | null>(null)
  const [productMode, setProductMode] = useState<ProductUiMode>('QUICK')
  const [enhancedPhase, setEnhancedPhase] = useState<EnhancedPhase>('IDLE')
  const [opportunity, setOpportunity] = useState<V3OpportunityMap | null>(null)
  const [answers, setAnswers] = useState<Record<string, EnhancedAnswerDraft>>({})
  const [questionIndex, setQuestionIndex] = useState(0)
  const [failedQuestionId, setFailedQuestionId] = useState<string | null>(null)
  const [tailorResumeId, setTailorResumeId] = useState<number | null>(null)
  const [selectedEntryKeys, setSelectedEntryKeys] = useState<string[]>([])
  const [selectedSkillReviewIds, setSelectedSkillReviewIds] = useState<string[]>([])
  const [editingSkillReviewId, setEditingSkillReviewId] = useState<string | null>(null)
  const [evidenceItems, setEvidenceItems] = useState<ResumeEvidenceItem[]>([])
  const [editingEntryKey, setEditingEntryKey] = useState<string | null>(null)
  const [leaveWarning, setLeaveWarning] = useState(false)

  async function load() {
    setLoading(true)
    setError('')
    try {
      const [resumeRes, jobRes] = await Promise.all([listResumes(), listJobs()])
      setResumes(resumeRes.data.filter((resume) => resume.origin !== 'TEMPLATE'))
      setJobs(jobRes.data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  useEffect(() => {
    setAnalysis(null)
    setError('')
    restoredKeyRef.current = ''
    setProductMode('QUICK')
    setEnhancedPhase('IDLE')
    setOpportunity(null)
    setAnswers({})
    setQuestionIndex(0)
    setFailedQuestionId(null)
    setTailorResumeId(null)
    setSelectedEntryKeys([])
    setSelectedSkillReviewIds([])
    setEditingEntryKey(null)
    setEditingSkillReviewId(null)
    setEvidenceItems([])
    setResult(null)
    setSelected([])
    setSuggestionText({})
    setAiOriginal({})
    setLeaveWarning(false)
  }, [resumeId, jobId])

  const unsavedWork = hasLocalUnsavedWork({
    answers,
    suggestionText,
    aiOriginal,
  }) || Boolean(editingEntryKey || editingNodeId)

  useEffect(() => {
    if (!resumeId || !jobId) {
      return
    }
    if (restoredKeyRef.current !== `${jobId}:${resumeId}`) {
      return
    }
    saveSession(jobId, resumeId, {
      productMode,
      phase: enhancedPhase,
      tailorResumeId,
      opportunity,
      answers,
      questionIndex,
      result,
      selectedEntryKeys,
      selectedSkillReviewIds,
      suggestionText,
      aiOriginal,
    })
  }, [
    resumeId,
    jobId,
    productMode,
    enhancedPhase,
    tailorResumeId,
    opportunity,
    answers,
    questionIndex,
    result,
    selectedEntryKeys,
    selectedSkillReviewIds,
    suggestionText,
    aiOriginal,
  ])

  useEffect(() => {
    function onBeforeUnload(event: BeforeUnloadEvent) {
      if (!unsavedWork) {
        return
      }
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [unsavedWork])

  useEffect(() => {
    if (loading || !resumeId || !jobId) {
      return
    }
    const key = `${jobId}:${resumeId}`
    if (restoredKeyRef.current === key) {
      return
    }
    restoredKeyRef.current = key
    const snapshot = loadSession(jobId, resumeId)
    if (!snapshot) {
      return
    }
    setProductMode(snapshot.productMode)
    setEnhancedPhase(snapshot.phase)
    setTailorResumeId(snapshot.tailorResumeId)
    setOpportunity(snapshot.opportunity)
    setAnswers(snapshot.answers ?? {})
    setQuestionIndex(snapshot.questionIndex ?? 0)
    setSelectedEntryKeys(snapshot.selectedEntryKeys ?? [])
    setSelectedSkillReviewIds(snapshot.selectedSkillReviewIds ?? [])
    setSuggestionText(snapshot.suggestionText ?? {})
    setAiOriginal(snapshot.aiOriginal ?? {})
    if (snapshot.result) {
      setResult(sanitizeResult(snapshot.result))
    }
    if (snapshot.result?.status === 'CONFIRMED' && snapshot.result.downloadUrl) {
      setEnhancedPhase(snapshot.productMode === 'ENHANCED' ? 'COMPLETED' : snapshot.phase)
      return
    }
    const proposalId = snapshot.result?.proposalId
    if (!proposalId) {
      return
    }
    void (async () => {
      try {
        const { data } = await getTailorProposal(Number(jobId), proposalId)
        const next = sanitizeResult(data)
        setResult(next)
        if (isEnhancedProposal(next)) {
          setProductMode('ENHANCED')
          setEnhancedPhase('REVIEW')
          const originals = {
            ...reviewSuggestionMap(next.entryReviews),
            ...skillSuggestionMap(next.skillReviews),
          }
          setAiOriginal(originals)
          setSuggestionText((current) => ({ ...originals, ...current }))
          setSelectedEntryKeys((current) =>
            current.length > 0 ? current : defaultSelectedEntryKeys(next.entryReviews),
          )
          setSelectedSkillReviewIds((current) =>
            current.length > 0 ? current : defaultSelectedSkillReviewIds(next.skillReviews),
          )
        } else {
          applyProposalPage(next, snapshot.suggestionText ? false : true)
          if (snapshot.suggestionText) {
            setSuggestionText(snapshot.suggestionText)
          }
          if (snapshot.aiOriginal) {
            setAiOriginal(snapshot.aiOriginal)
          }
        }
      } catch {
        setResult(snapshot.result)
        if (snapshot.productMode === 'ENHANCED' && snapshot.phase === 'REVIEW') {
          setError(t('tailor.proposalExpired'))
        }
      }
    })()
  }, [loading, resumeId, jobId, t])

  useEffect(() => {
    if (!resumeId || productMode !== 'ENHANCED' || !result) {
      return
    }
    void Promise.resolve(listResumeEvidence(Number(resumeId)))
      .then((response) => setEvidenceItems(response?.data ?? []))
      .catch(() => undefined)
  }, [resumeId, productMode, result?.proposalId])

  const visibleResumes = resumes.filter((resume) => resume.origin !== 'TEMPLATE')
  const selectedResume = visibleResumes.find((item) => String(item.id) === resumeId)
  const fileUnavailable = Boolean(selectedResume && !selectedResume.fileAvailable)
  const selectedPdf =
    selectedResume?.fileType.toLowerCase() === 'pdf' && selectedResume.fileAvailable
      ? selectedResume
      : null
  const needsConfirm = Boolean(result?.proposalId) && !result?.downloadUrl
  const showEnhancedReview =
    productMode === 'ENHANCED'
    && (enhancedPhase === 'REVIEW' || enhancedPhase === 'CONFIRMING' || enhancedPhase === 'COMPLETED')
    && isEnhancedProposal(result)
  const hidePreviousRunResult =
    productMode === 'ENHANCED'
    && (
      enhancedPhase === 'SCANNING'
      || enhancedPhase === 'QUESTIONS'
      || enhancedPhase === 'GENERATING_PROPOSAL'
      || enhancedPhase === 'REVIEW'
      || enhancedPhase === 'CONFIRMING'
    )
  const modeLocked =
    running
    || analyzing
    || enhancedPhase === 'SCANNING'
    || enhancedPhase === 'GENERATING_PROPOSAL'
    || enhancedPhase === 'CONFIRMING'

  function clearDisplayedRunResult() {
    setResult(null)
    setSelected([])
    setSuggestionText({})
    setAiOriginal({})
    setEditingNodeId(null)
    setEditingEntryKey(null)
    setDraft('')
    setContinueNotice('')
    setSelectedEntryKeys([])
    setSelectedSkillReviewIds([])
    setEditingSkillReviewId(null)
    setScanProgress(null)
  }

  function applyEnhancedProposal(next: TailoredResumeResponse) {
    const originals = {
      ...reviewSuggestionMap(next.entryReviews),
      ...skillSuggestionMap(next.skillReviews),
    }
    setAiOriginal(originals)
    setSuggestionText(originals)
    setSelectedEntryKeys(defaultSelectedEntryKeys(next.entryReviews))
    setSelectedSkillReviewIds(defaultSelectedSkillReviewIds(next.skillReviews))
    setEditingEntryKey(null)
    setEditingSkillReviewId(null)
    setDraft('')
  }

  async function resolveTailorResume(resume: ResumeResponse): Promise<number> {
    if (resume.fileType.toLowerCase() !== 'pdf') {
      return resume.id
    }
    setProgress('recognizing')
    await getResumeStructure(resume.id)
    setProgress('preparing')
    try {
      const { data } = await canonicalizeResume(resume.id)
      return data.id
    } catch (error) {
      const axiosError = error as { response?: { status?: number } }
      if (axiosError.response?.status === 400) {
        throw new IncompletePdfStructureError(resume.id)
      }
      throw error
    }
  }

  async function onAnalyze() {
    if (!resumeId || !jobId) {
      setError(t('tailor.needResumeJob'))
      return
    }
    setAnalyzing(true)
    setError('')
    try {
      const { data } = await analyzeTailorFit(Number(jobId), Number(resumeId))
      setAnalysis(data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setAnalyzing(false)
    }
  }

  async function onRefreshProfile() {
    if (!resumeId || !jobId) {
      setError(t('tailor.needResumeJob'))
      return
    }
    if (!window.confirm(t('tailor.refreshProfileConfirm'))) {
      return
    }
    setAnalyzing(true)
    setError('')
    try {
      const { data } = await refreshJobRequirementProfile(Number(jobId), Number(resumeId))
      setAnalysis(data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setAnalyzing(false)
    }
  }

  async function onRun() {
    if (productMode === 'ENHANCED') {
      await onEnhancedScan()
      return
    }
    if (!resumeId || !jobId) {
      setError(t('tailor.needResumeJob'))
      return
    }
    const resume = visibleResumes.find((item) => String(item.id) === resumeId)
    if (!resume) {
      setError(t('tailor.needResumeJob'))
      return
    }
    if (!resume.fileAvailable) {
      setError(t('tailor.fileUnavailable'))
      setStructureResumeId(null)
      return
    }
    setRunning(true)
    setError('')
    setStructureResumeId(null)
    setEnhancedPhase('IDLE')
    setOpportunity(null)
    setAnswers({})
    setQuestionIndex(0)
    setFailedQuestionId(null)
    clearDisplayedRunResult()
    scanCancelRef.current = false
    try {
      const data = await runTailorFlow({
        resume,
        jobId: Number(jobId),
        getResumeStructure,
        canonicalizeResume,
        tailorResume,
        onProgress: setProgress,
      })
      setResult(sanitizeResult(data))
      setEditingNodeId(null)
      setDraft('')
      setSelected([])
      setSuggestionText({})
      setAiOriginal({})
    } catch (err) {
      if (err instanceof IncompletePdfStructureError) {
        setStructureResumeId(err.resumeId)
        setError(t('tailor.incompleteRecognition'))
      } else if (err instanceof ResumeFileUnavailableError) {
        setError(t('tailor.fileUnavailable'))
      } else {
        setError(t(apiErrorKey(err)))
      }
    } finally {
      setProgress(null)
      setRunning(false)
      setContinuing(false)
      setScanProgress(null)
    }
  }

  function applyProposalPage(data: TailoredResumeResponse, replace: boolean) {
    const next = sanitizeResult(data)
    setResult(next)
    const originals = suggestionMap(next.changes)
    if (replace) {
      setAiOriginal(originals)
      setSuggestionText(originals)
      setSelected(defaultSelectedNodeIds(next.changes))
    } else {
      setSuggestionText((current) => ({ ...current, ...originals }))
      setAiOriginal((current) => {
        const merged = { ...current }
        for (const [nodeId, text] of Object.entries(originals)) {
          if (!(nodeId in merged)) {
            merged[nodeId] = text
          }
        }
        return merged
      })
      setSelected((current) => {
        const extra = defaultSelectedNodeIds(next.changes).filter((id) => !current.includes(id))
        return extra.length === 0 ? current : [...current, ...extra]
      })
    }
    const total = next.eligibleNodeIds?.length ?? 0
    const checked = next.exploredNodeIds?.length ?? 0
    setScanProgress(total > 0 ? { checked, total, found: next.changes?.length ?? 0 } : null)
  }

  async function onConfirm() {
    if (productMode === 'ENHANCED' && isEnhancedProposal(result)) {
      await onEnhancedConfirm()
      return
    }
    if (!result?.proposalId || !jobId) {
      return
    }
    if (selected.length === 0 || confirmingRef.current) {
      return
    }
    if (hasUnsavedEdit(editingNodeId, draft, suggestionText)) {
      setError(t('tailor.unsavedEdits'))
      return
    }
    confirmingRef.current = true
    setRunning(true)
    setError('')
    try {
      const { data } = await confirmTailorProposal(
        Number(jobId),
        result.proposalId,
        selected,
        buildEditedChanges(selected, suggestionText, aiOriginal),
      )
      setResult(sanitizeResult(data))
      setEditingNodeId(null)
      setDraft('')
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      confirmingRef.current = false
      setRunning(false)
    }
  }

  async function onContinue() {
    if (!result?.proposalId || !jobId || continuingRef.current || continuing) {
      return
    }
    continuingRef.current = true
    setContinuing(true)
    setError('')
    setContinueNotice('')
    const previousIds = new Set((result.changes ?? []).map((change) => change.nodeId))
    try {
      const { data } = await continueTailorProposal(Number(jobId), result.proposalId)
      const next = sanitizeResult(data)
      const added = next.changes.filter((change) => !previousIds.has(change.nodeId))
      setSuggestionText((current) => {
        const merged = { ...current }
        for (const change of added) {
          merged[change.nodeId] = change.newText
        }
        return merged
      })
      setAiOriginal((current) => {
        const merged = { ...current }
        for (const change of added) {
          merged[change.nodeId] = change.newText
        }
        return merged
      })
      setSelected((current) => [...current, ...added.map((change) => change.nodeId)])
      setResult(next)
      if (next.coverageStatus === 'PARTIAL' || next.coverageStatus === 'FAILED') {
        setContinueNotice(t('tailor.scanIncomplete'))
      } else if ((next.addedSuggestionCount ?? added.length) === 0) {
        setContinueNotice(
          next.hasMoreSuggestions === false && (next.coverageStatus === 'COMPLETE' || next.coverageStatus == null)
            ? t('tailor.highValueComplete')
            : next.hasMoreSuggestions === false
              ? t('tailor.scanIncomplete')
              : '',
        )
      } else if (next.hasMoreSuggestions === false && (next.coverageStatus === 'COMPLETE' || next.coverageStatus == null)) {
        setContinueNotice(t('tailor.highValueComplete'))
      }
      if (added.length > 0) {
        requestAnimationFrame(() => {
          const node = document.querySelector(`[data-suggestion-node="${added[0].nodeId}"]`)
          if (node && typeof node.scrollIntoView === 'function') {
            node.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
          }
        })
      }
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      continuingRef.current = false
      setContinuing(false)
    }
  }

  async function onEnhancedScan() {
    if (!resumeId || !jobId || scanningRef.current) {
      if (!resumeId || !jobId) {
        setError(t('tailor.needResumeJob'))
      }
      return
    }
    const resume = visibleResumes.find((item) => String(item.id) === resumeId)
    if (!resume) {
      setError(t('tailor.needResumeJob'))
      return
    }
    if (!resume.fileAvailable) {
      setError(t('tailor.fileUnavailable'))
      setStructureResumeId(null)
      return
    }
    scanningRef.current = true
    setRunning(true)
    setError('')
    setStructureResumeId(null)
    setAnswers({})
    setQuestionIndex(0)
    setFailedQuestionId(null)
    clearDisplayedRunResult()
    setEnhancedPhase('SCANNING')
    setLeaveWarning(false)
    try {
      const resolved = await resolveTailorResume(resume)
      setTailorResumeId(resolved)
      setProgress(null)
      const { data } = await scanEnhancedOpportunity(Number(jobId), resolved)
      setOpportunity(data)
      setQuestionIndex(0)
      setFailedQuestionId(null)
      setEnhancedPhase('QUESTIONS')
    } catch (err) {
      setEnhancedPhase('SCANNING')
      if (err instanceof IncompletePdfStructureError) {
        setStructureResumeId(err.resumeId)
        setError(t('tailor.incompleteRecognition'))
      } else if (err instanceof ResumeFileUnavailableError) {
        setError(t('tailor.fileUnavailable'))
      } else {
        setError(t(apiErrorKey(err)))
      }
    } finally {
      scanningRef.current = false
      setProgress(null)
      setRunning(false)
    }
  }

  async function onGenerateEnhanced() {
    if (!jobId || generatingRef.current || !opportunity) {
      return
    }
    generatingRef.current = true
    const questions = visibleQuestions(opportunity.questions)
    const validation = canSubmitAnswers(questions, answers)
    if (!validation.ok) {
      generatingRef.current = false
      const index = questions.findIndex((question) => question.id === validation.questionId)
      if (index >= 0) {
        setQuestionIndex(index)
      }
      setError(t('tailor.needRealDetails'))
      return
    }
    const resumeIdForApi = tailorResumeId ?? Number(resumeId)
    setRunning(true)
    setError('')
    setFailedQuestionId(null)
    setEnhancedPhase('GENERATING_PROPOSAL')
    try {
      const payload = answersForSubmit(questions, answers)
      for (const item of payload) {
        try {
          await answerEnhancedQuestion(Number(jobId), resumeIdForApi, item)
          setAnswers((current) => ({
            ...current,
            [item.questionId]: {
              ...(current[item.questionId] ?? { intent: 'skipped', text: '' }),
              submitted: true,
            },
          }))
        } catch (err) {
          setFailedQuestionId(item.questionId)
          const index = questions.findIndex((question) => question.id === item.questionId)
          if (index >= 0) {
            setQuestionIndex(index)
          }
          setEnhancedPhase('QUESTIONS')
          setError(t(apiErrorKey(err)))
          return
        }
      }
      try {
        const { data: refreshed } = await scanEnhancedOpportunity(Number(jobId), resumeIdForApi)
        setOpportunity(refreshed)
      } catch (err) {
        setEnhancedPhase('QUESTIONS')
        setError(t(apiErrorKey(err)))
        return
      }
      const { data } = await createTailorProposal(Number(jobId), resumeIdForApi, 'ENHANCED')
      const next = sanitizeResult(data)
      setResult(next)
      applyEnhancedProposal(next)
      setEnhancedPhase('REVIEW')
    } catch (err) {
      setEnhancedPhase('GENERATING_PROPOSAL')
      setError(t(apiErrorKey(err)))
    } finally {
      generatingRef.current = false
      setRunning(false)
    }
  }

  async function onCorrectEvidence(usage: V3EvidenceUsage, text: string) {
    if (!resumeId) {
      return
    }
    const item = evidenceItems.find((row) => row.id === usage.evidenceItemId)
    if (!item) {
      return
    }
    try {
      await saveResumeEvidence(Number(resumeId), {
        topic: item.topicKey || item.topic,
        category: item.category,
        answerStatus: 'CONFIRMED_USED',
        entryKey: item.entryKey,
        relatedNodeIds: item.relatedNodeIds ?? [],
        context: item.context ?? '',
        action: text,
        result: item.result ?? '',
        metric: item.metric ?? '',
        confirmed: true,
      })
      const { data } = await listResumeEvidence(Number(resumeId))
      setEvidenceItems(data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  async function onDeleteEvidence(usage: V3EvidenceUsage) {
    if (!resumeId) {
      return
    }
    try {
      await deleteResumeEvidence(Number(resumeId), usage.evidenceItemId)
      setEvidenceItems((current) => current.filter((item) => item.id !== usage.evidenceItemId))
      setResult((current) => {
        if (!current) {
          return current
        }
        return {
          ...current,
          evidenceUsage: (current.evidenceUsage ?? []).filter((item) => item.evidenceItemId !== usage.evidenceItemId),
        }
      })
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  async function onEnhancedConfirm() {
    if (!result?.proposalId || !jobId || confirmingRef.current) {
      return
    }
    if (selectedEntryKeys.length === 0 && selectedSkillReviewIds.length === 0) {
      return
    }
    if (editingEntryKey || editingSkillReviewId) {
      setError(t('tailor.unsavedEdits'))
      return
    }
    confirmingRef.current = true
    setRunning(true)
    setError('')
    setEnhancedPhase('CONFIRMING')
    try {
      const { data } = await confirmTailorProposal(
        Number(jobId),
        result.proposalId,
        undefined,
        undefined,
        {
          selectedEntryKeys,
          editedEntries: buildEditedEntries(
            result.entryReviews,
            selectedEntryKeys,
            suggestionText,
            aiOriginal,
          ),
          selectedSkillReviewIds,
          editedSkillReviews: buildEditedSkillReviews(
            result.skillReviews,
            selectedSkillReviewIds,
            suggestionText,
            aiOriginal,
          ),
        },
      )
      setResult(sanitizeResult(data))
      setEditingEntryKey(null)
      setDraft('')
      setEnhancedPhase('COMPLETED')
    } catch (err) {
      setEnhancedPhase('REVIEW')
      setError(t(apiErrorKey(err)))
    } finally {
      confirmingRef.current = false
      setRunning(false)
    }
  }

  function applyEnhancedEntryText(entryKey: string, text: string) {
    const review = result?.entryReviews?.find((item) => item.canonicalEntryKey === entryKey)
    if (!review) {
      return
    }
    const lines = text.split('\n')
    const next = { ...suggestionText }
    const nodes = review.proposedNodes ?? []
    nodes.forEach((node, index) => {
      if (index === nodes.length - 1) {
        next[node.nodeId] = lines.slice(index).join('\n')
      } else {
        next[node.nodeId] = lines[index] ?? ''
      }
    })
    setSuggestionText(next)
  }

  function applyEnhancedSkillText(skillReviewId: string, text: string) {
    const review = result?.skillReviews?.find((item) => item.skillReviewId === skillReviewId)
    const next = { ...suggestionText, [skillReviewId]: text }
    const nodes = review?.proposedNodes ?? []
    const lines = text.split('\n')
    nodes.forEach((node, index) => {
      if (index === nodes.length - 1) {
        next[node.nodeId] = lines.slice(index).join('\n')
      } else {
        next[node.nodeId] = lines[index] ?? ''
      }
    })
    setSuggestionText(next)
  }

  const progressLabel = progressLabelKey(progress)

  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-900">{t('tailor.title')}</h1>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {running && scanProgress ? (
        <div className="mt-4 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-700">
          <p>{t('tailor.checkingResume')}</p>
          <p className="mt-1">{t('tailor.checkedEntries', { checked: scanProgress.checked, total: scanProgress.total })}</p>
          <p className="mt-1">{t('tailor.foundSuggestions', { count: scanProgress.found })}</p>
          <button type="button" className="mt-3 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm"
            onClick={() => { scanCancelRef.current = true }}>
            {t('tailor.cancelScan')}
          </button>
        </div>
      ) : null}
      {error ? (
        <div className="mt-4">
          <Alert
            message={error}
            onRetry={
              productMode === 'ENHANCED' && enhancedPhase === 'GENERATING_PROPOSAL'
                ? () => void onGenerateEnhanced()
                : productMode === 'ENHANCED' && enhancedPhase === 'SCANNING'
                  ? () => void onEnhancedScan()
                  : undefined
            }
          />
          {structureResumeId ? (
            <Link
              className="mt-3 inline-flex rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm"
              to={`/resumes/${structureResumeId}/structure?next=tailor`}
            >
              {t('resumes.confirmContent')}
            </Link>
          ) : null}
        </div>
      ) : null}
      {progressLabel ? (
        <div className="mt-4"><Spinner label={t(progressLabel)} /></div>
      ) : null}

      {unsavedWork && leaveWarning ? (
        <p role="status" className="mt-3 text-sm text-amber-800">{t('tailor.enhanced.leaveWarning')}</p>
      ) : null}
      {enhancedPhase === 'GENERATING_PROPOSAL' && running ? (
        <div className="mt-4 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-700">
          <p>{t('tailor.enhanced.generating')}</p>
          <p className="mt-2">{t('tailor.enhanced.noFilesYet')}</p>
        </div>
      ) : null}

      <div className="mt-6 grid gap-4 rounded-xl border border-slate-200 bg-white p-5 md:grid-cols-2">
        <label className="text-sm">
          <span>{t('tailor.selectResume')}</span>
          <select className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            value={resumeId} onChange={(event) => setResumeId(event.target.value)}>
            <option value="">{t('tailor.selectResume')}</option>
            {visibleResumes.map((resume) => (
              <option key={resume.id} value={resume.id}>
                {displayFileName(resume.fileName)}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span>{t('tailor.selectJob')}</span>
          <select className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            value={jobId} onChange={(event) => setJobId(event.target.value)}>
            <option value="">{t('tailor.selectJob')}</option>
            {jobs.map((job) => (
              <option key={job.id} value={job.id}>
                {job.company} — {job.title}
              </option>
            ))}
          </select>
        </label>
        <div className="md:col-span-2">
          <ProductModeCards value={productMode} onChange={setProductMode} disabled={modeLocked} />
        </div>
        {productMode === 'ENHANCED' ? (
          <div className="md:col-span-2">
            <EnhancedStepList phase={enhancedPhase} />
          </div>
        ) : null}
        {selectedPdf ? (
          <p className="md:col-span-2 text-sm">
            <Link className="underline" to={`/resumes/${selectedPdf.id}/structure?next=tailor`}>
              {t('resumes.confirmContent')}
            </Link>
          </p>
        ) : null}
        {fileUnavailable ? (
          <p className="md:col-span-2 text-sm text-red-700">{t('tailor.fileUnavailable')}</p>
        ) : null}
        <div className="flex flex-wrap gap-3">
          <button type="button" disabled={running || analyzing || fileUnavailable}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm disabled:opacity-60"
            onClick={() => void onAnalyze()}>
            {analyzing ? t('tailor.analyzing') : t('tailor.analyze')}
          </button>
          {productMode === 'QUICK' ? (
            <button type="button" disabled={running || analyzing || fileUnavailable}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
              onClick={() => void onRun()}>
              {running && progressLabel ? t(progressLabel) : running ? t('tailor.tailoringJob') : t('tailor.generateSuggestions')}
            </button>
          ) : (
            <>
              <button type="button"
                disabled={running || analyzing || fileUnavailable || generatingRef.current}
                className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
                onClick={() => void onEnhancedScan()}>
                {enhancedPhase === 'SCANNING' && running
                  ? t('tailor.enhanced.scanning')
                  : t('tailor.enhanced.startScan')}
              </button>
            </>
          )}
        </div>
        <div className="md:col-span-2">
          <button type="button" disabled={running || analyzing || fileUnavailable}
            className="text-xs text-slate-500 underline disabled:opacity-60"
            onClick={() => void onRefreshProfile()}>
            {t('tailor.refreshProfile')}
          </button>
        </div>
      </div>

      {!loading && visibleResumes.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">{t('tailor.emptyResumes')}</p>
      ) : null}
      {!loading && jobs.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500">{t('tailor.emptyJobs')}</p>
      ) : null}

      {analysis ? (
        <AnalysisPanel analysis={analysis} />
      ) : null}

      {productMode === 'ENHANCED' && opportunity && enhancedPhase !== 'IDLE' && enhancedPhase !== 'SCANNING' ? (
        <EnhancedOpportunityMap map={opportunity} />
      ) : null}
      {productMode === 'ENHANCED' && opportunity && (enhancedPhase === 'QUESTIONS' || enhancedPhase === 'GENERATING_PROPOSAL') ? (
        <EnhancedQuestionsPanel
          map={opportunity}
          answers={answers}
          questionIndex={questionIndex}
          submitting={running && enhancedPhase === 'GENERATING_PROPOSAL'}
          failedQuestionId={failedQuestionId}
          onAnswerChange={(questionId, draft) => {
            setAnswers((current) => ({ ...current, [questionId]: draft }))
            setLeaveWarning(true)
          }}
          onIndexChange={setQuestionIndex}
          onGenerate={() => void onGenerateEnhanced()}
        />
      ) : null}
      {showEnhancedReview && result ? (
        <EnhancedResult
          result={result}
          selectedEntryKeys={selectedEntryKeys}
          selectedSkillReviewIds={selectedSkillReviewIds}
          suggestionText={suggestionText}
          aiOriginal={aiOriginal}
          editingEntryKey={editingEntryKey}
          editingSkillReviewId={editingSkillReviewId}
          draft={draft}
          confirming={enhancedPhase === 'CONFIRMING' || running}
          opportunity={opportunity}
          answers={answers}
          evidenceItems={evidenceItems}
          noHighValue={hasNoHighValueRewrite(result)}
          onToggle={(entryKey, checked) => {
            setSelectedEntryKeys((current) =>
              checked ? [...current, entryKey] : current.filter((key) => key !== entryKey),
            )
          }}
          onToggleSkill={(skillReviewId, checked) => {
            setSelectedSkillReviewIds((current) =>
              checked ? [...current, skillReviewId] : current.filter((key) => key !== skillReviewId),
            )
          }}
          onStartEdit={(entryKey, text) => {
            setEditingSkillReviewId(null)
            setEditingEntryKey(entryKey)
            setDraft(text)
          }}
          onStartSkillEdit={(skillReviewId, text) => {
            setEditingEntryKey(null)
            setEditingSkillReviewId(skillReviewId)
            setDraft(text)
          }}
          onChangeDraft={setDraft}
          onSaveEdit={(entryKey) => {
            applyEnhancedEntryText(entryKey, draft)
            setEditingEntryKey(null)
            setDraft('')
            setLeaveWarning(true)
          }}
          onSaveSkillEdit={(skillReviewId) => {
            applyEnhancedSkillText(skillReviewId, draft)
            setEditingSkillReviewId(null)
            setDraft('')
            setLeaveWarning(true)
          }}
          onCancelEdit={() => {
            setEditingEntryKey(null)
            setEditingSkillReviewId(null)
            setDraft('')
          }}
          onRestore={(entryKey) => {
            const review = result.entryReviews?.find((item) => item.canonicalEntryKey === entryKey)
            if (!review) {
              return
            }
            const next = { ...suggestionText }
            for (const node of review.proposedNodes ?? []) {
              next[node.nodeId] = aiOriginal[node.nodeId] ?? node.text
            }
            setSuggestionText(next)
            if (editingEntryKey === entryKey) {
              setDraft(review.proposedNodes.map((node) => aiOriginal[node.nodeId] ?? node.text).join('\n'))
            }
          }}
          onRestoreSkill={(skillReviewId) => {
            const review = result.skillReviews?.find((item) => item.skillReviewId === skillReviewId)
            if (!review) {
              return
            }
            const next = { ...suggestionText }
            next[skillReviewId] = aiOriginal[skillReviewId] ?? review.proposedSkills
            for (const node of review.proposedNodes ?? []) {
              next[node.nodeId] = aiOriginal[node.nodeId] ?? node.text
            }
            setSuggestionText(next)
            if (editingSkillReviewId === skillReviewId) {
              setDraft(aiOriginal[skillReviewId] ?? review.proposedSkills)
            }
          }}
          onCorrectEvidence={(usage, text) => void onCorrectEvidence(usage, text)}
          onDeleteEvidence={(usage) => onDeleteEvidence(usage)}
          onConfirm={() => void onEnhancedConfirm()}
        />
      ) : null}

      {result && !showEnhancedReview && !hidePreviousRunResult ? <ResultPanel result={result} selected={selected} setSelected={setSelected}
        suggestionText={suggestionText} setSuggestionText={setSuggestionText}
        aiOriginal={aiOriginal} editingNodeId={editingNodeId} setEditingNodeId={setEditingNodeId}
        draft={draft} setDraft={setDraft}
        needsConfirm={needsConfirm} running={running} onConfirm={() => void onConfirm()}
        continuing={continuing} continueNotice={continueNotice}
        onContinue={() => void onContinue()} /> : null}
    </div>
  )
}

function progressLabelKey(progress: TailorProgress | null): string | null {
  if (progress === 'recognizing') {
    return 'tailor.recognizingPdf'
  }
  if (progress === 'preparing') {
    return 'tailor.preparingEditable'
  }
  if (progress === 'tailoring') {
    return 'tailor.tailoringJob'
  }
  return null
}

function AnalysisPanel({ analysis }: { analysis: TailorAnalysisResponse }) {
  const { t } = useTranslation()
  const supported = analysis.requirements.filter((item) => item.status === 'SUPPORTED')
  const partial = analysis.requirements.filter((item) => item.status === 'PARTIALLY_SUPPORTED')
  const missing = analysis.requirements.filter((item) => item.status === 'MISSING_EVIDENCE')
  const explanation = analysis.scoreExplanation

  return (
    <section className="mt-8 rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium">{t('tailor.analysisTitle')}</h2>
      {analysis.requirementsFixed ? (
        <div className="mt-2 text-sm text-slate-600">
          <p>{t('tailor.requirementsFixed')}</p>
          {analysis.profileUpdatedAt ? (
            <p className="mt-1 text-xs">{t('tailor.profileUpdatedAt', { time: analysis.profileUpdatedAt })}</p>
          ) : null}
          <p className="mt-1 text-xs">{t('tailor.reanalyzeHint')}</p>
        </div>
      ) : null}
      <p className="mt-3 text-2xl font-semibold">{t('tailor.overallScore')}: {analysis.overallScore}</p>
      {analysis.scoreBand ? (
        <p className="mt-1 text-sm text-slate-600">
          {t(`tailor.scoreBand.${analysis.scoreBand}`, { defaultValue: analysis.scoreBand })}
        </p>
      ) : null}
      <dl className="mt-4 grid gap-2 text-sm md:grid-cols-2">
        <DimensionScore label={t('tailor.dimHardSkills')} value={analysis.dimensions.hardSkills} />
        <DimensionScore label={t('tailor.dimResponsibilities')} value={analysis.dimensions.responsibilities} />
        <DimensionScore label={t('tailor.dimQualifications')} value={analysis.dimensions.qualifications} />
        <DimensionScore label={t('tailor.dimEvidenceStrength')} value={analysis.dimensions.evidenceStrength} />
        <DimensionScore label={t('tailor.dimAtsSearchability')} value={analysis.dimensions.atsSearchability} />
      </dl>
      <p className="mt-4 text-sm">
        {t('tailor.mustHaveCoverage', {
          supported: analysis.summary.supportedMustHaveCount,
          total: analysis.summary.totalMustHaveCount,
        })}
      </p>
      {explanation && (explanation.strengths.length > 0 || explanation.gaps.length > 0) ? (
        <div className="mt-4 space-y-3 text-sm">
          <ExplanationList title={t('tailor.strengths')} items={explanation.strengths} />
          <ExplanationList title={t('tailor.gaps')} items={explanation.gaps} />
          <ExplanationList title={t('tailor.nextActions')} items={explanation.nextActions} />
        </div>
      ) : null}
      <RequirementGroup title={t('tailor.supportedRequirements')} items={supported} />
      <RequirementGroup title={t('tailor.partialRequirements')} items={partial} />
      <RequirementGroup title={t('tailor.missingRequirements')} items={missing} />
    </section>
  )
}

function DimensionScore({ label, value }: { label: string; value: number | null | undefined }) {
  const { t } = useTranslation()
  return (
    <div>
      {label}: {value == null ? t('tailor.dimensionNotSpecified') : value}
    </div>
  )
}

function ExplanationList({ title, items }: { title: string; items: string[] }) {
  if (!items || items.length === 0) {
    return null
  }
  return (
    <div>
      <h3 className="font-medium">{title}</h3>
      <ul className="mt-1 list-disc space-y-1 pl-5">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function RequirementGroup({
  title,
  items,
}: {
  title: string
  items: TailorAnalysisResponse['requirements']
}) {
  const { t } = useTranslation()
  return (
    <div className="mt-5">
      <h3 className="text-sm font-medium">{title} ({items.length})</h3>
      {items.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500">{t('tailor.noRequirementsInGroup')}</p>
      ) : (
        <ul className="mt-2 space-y-3">
          {items.map((item) => (
            <li key={item.id} className="rounded-lg border border-slate-200 p-3 text-sm">
              <p className="font-medium">{item.requirement}</p>
              <p className="mt-1 text-xs text-slate-500">
                {t(`tailor.category.${item.category}`, { defaultValue: item.category })}
                {' · '}
                {t(`tailor.status.${item.status}`, { defaultValue: item.status })}
                {item.evidenceKind ? (
                  <>
                    {' · '}
                    {t(`tailor.evidenceKind.${item.evidenceKind}`, { defaultValue: item.evidenceKind })}
                  </>
                ) : null}
              </p>
              {item.evidenceAvailability === 'USER_CONFIRMED' ? (
                <p className="mt-1 text-xs text-slate-700">{t('tailor.waitingToAdd')}</p>
              ) : null}
              {item.evidenceAvailability === 'USER_REJECTED' ? (
                <p className="mt-1 text-xs text-slate-700">{t('tailor.confirmedNotUsed')}</p>
              ) : null}
              {item.coverageReason === 'currently pursuing' ? (
                <p className="mt-1 text-xs text-slate-700">{t('tailor.currentlyPursuing')}</p>
              ) : null}
              {item.evidenceQuotes.length > 0 ? (
                <ul className="mt-2 space-y-1">
                  {item.evidenceQuotes.map((quote, index) => (
                    <li key={`${item.id}-q-${index}`} className="rounded-md bg-slate-50 p-2 text-xs whitespace-pre-wrap">
                      {quote}
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function EnhancedResult({
  result,
  selectedEntryKeys,
  selectedSkillReviewIds,
  suggestionText,
  aiOriginal,
  editingEntryKey,
  editingSkillReviewId,
  draft,
  confirming,
  opportunity,
  answers,
  evidenceItems,
  noHighValue,
  onToggle,
  onToggleSkill,
  onStartEdit,
  onStartSkillEdit,
  onChangeDraft,
  onSaveEdit,
  onSaveSkillEdit,
  onCancelEdit,
  onRestore,
  onRestoreSkill,
  onCorrectEvidence,
  onDeleteEvidence,
  onConfirm,
}: {
  result: TailoredResumeResponse
  selectedEntryKeys: string[]
  selectedSkillReviewIds: string[]
  suggestionText: Record<string, string>
  aiOriginal: Record<string, string>
  editingEntryKey: string | null
  editingSkillReviewId: string | null
  draft: string
  confirming: boolean
  opportunity: V3OpportunityMap | null
  answers: Record<string, EnhancedAnswerDraft>
  evidenceItems: ResumeEvidenceItem[]
  noHighValue: boolean
  onToggle: (entryKey: string, checked: boolean) => void
  onToggleSkill: (skillReviewId: string, checked: boolean) => void
  onStartEdit: (entryKey: string, text: string) => void
  onStartSkillEdit: (skillReviewId: string, text: string) => void
  onChangeDraft: (value: string) => void
  onSaveEdit: (entryKey: string) => void
  onSaveSkillEdit: (skillReviewId: string) => void
  onCancelEdit: () => void
  onRestore: (entryKey: string) => void
  onRestoreSkill: (skillReviewId: string) => void
  onCorrectEvidence: (usage: V3EvidenceUsage, text: string) => void
  onDeleteEvidence: (usage: V3EvidenceUsage) => Promise<void>
  onConfirm: () => void
}) {
  const { t } = useTranslation()
  const [downloadError, setDownloadError] = useState('')
  const reviews = result.entryReviews ?? []
  const skillReviews = result.skillReviews ?? []
  const usages = result.evidenceUsage ?? []
  const confirmDisabled = confirming
    || (selectedEntryKeys.length === 0 && selectedSkillReviewIds.length === 0)
    || Boolean(editingEntryKey || editingSkillReviewId)
  const completed = Boolean(result.downloadUrl)
  const showReviews = !noHighValue || skillReviews.length > 0

  async function onDownload(url: string, name: string) {
    setDownloadError('')
    try {
      await downloadByUrl(url, name)
    } catch (err) {
      const message = err instanceof Error ? err.message : ''
      setDownloadError(message || t('resumes.downloadFailed'))
    }
  }

  return (
    <div className="mt-8 space-y-4">
      {noHighValue ? (
        <p className="rounded-xl border border-slate-200 bg-white p-5 text-sm text-slate-700">
          {t('tailor.enhanced.noHighValue')}
        </p>
      ) : null}
      {usages.length > 0 ? (
        <EnhancedEvidenceUsagePanel
          usages={usages}
          items={evidenceItems}
          opportunity={opportunity}
          busy={confirming}
          onCorrect={async (usage, text) => onCorrectEvidence(usage, text)}
          onDelete={onDeleteEvidence}
        />
      ) : null}
      {showReviews ? (
        <>
          <p className="text-sm text-slate-700">
            {t('tailor.enhanced.selectedEntries', {
              selected: selectedEntryKeys.length,
              total: reviews.length,
            })}
            {' · '}
            {t('tailor.filesAfterConfirm')}
          </p>
          <EnhancedReviewPanel
            reviews={reviews}
            selectedEntryKeys={selectedEntryKeys}
            suggestionText={suggestionText}
            aiOriginal={aiOriginal}
            editingKey={editingEntryKey}
            draft={draft}
            confirming={confirming}
            opportunity={opportunity}
            answers={answers}
            onToggle={onToggle}
            onStartEdit={onStartEdit}
            onChangeDraft={onChangeDraft}
            onSaveEdit={onSaveEdit}
            onCancelEdit={onCancelEdit}
            onRestore={onRestore}
          />
          <EnhancedSkillsReviewPanel
            reviews={skillReviews}
            selectedIds={selectedSkillReviewIds}
            suggestionText={suggestionText}
            aiOriginal={aiOriginal}
            editingId={editingSkillReviewId}
            draft={draft}
            confirming={confirming}
            onToggle={onToggleSkill}
            onStartEdit={onStartSkillEdit}
            onChangeDraft={onChangeDraft}
            onSaveEdit={onSaveSkillEdit}
            onCancelEdit={onCancelEdit}
            onRestore={onRestoreSkill}
          />
          {!completed && (reviews.length > 0 || skillReviews.length > 0) ? (
            <button type="button" disabled={confirmDisabled}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
              onClick={onConfirm}>
              {confirming ? t('tailor.confirming') : t('tailor.enhanced.confirmGenerate')}
            </button>
          ) : null}
        </>
      ) : null}
      {completed ? (
        <p className="text-sm text-slate-800">{t('tailor.enhanced.completed')}</p>
      ) : null}
      {downloadError ? <Alert message={downloadError} /> : null}
      <div className="flex flex-wrap gap-3">
        {result.downloadUrl ? (
          <button type="button" className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white"
            onClick={() => void onDownload(result.downloadUrl!, 'tailored.docx')}>
            {t('tailor.downloadDocx')}
          </button>
        ) : null}
        {result.pdfDownloadUrl && result.pdfExportStatus === 'SUCCESS' ? (
          <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm"
            onClick={() => void onDownload(result.pdfDownloadUrl!, 'tailored.pdf')}>
            {t('tailor.downloadPdf')}
          </button>
        ) : null}
      </div>
    </div>
  )
}

function ResultPanel({
  result,
  selected,
  setSelected,
  suggestionText,
  setSuggestionText,
  aiOriginal,
  editingNodeId,
  setEditingNodeId,
  draft,
  setDraft,
  needsConfirm,
  running,
  onConfirm,
  continuing,
  continueNotice,
  onContinue,
}: {
  result: TailoredResumeResponse
  selected: string[]
  setSelected: (value: string[]) => void
  suggestionText: Record<string, string>
  setSuggestionText: (value: Record<string, string>) => void
  aiOriginal: Record<string, string>
  editingNodeId: string | null
  setEditingNodeId: (value: string | null) => void
  draft: string
  setDraft: (value: string) => void
  needsConfirm: boolean
  running: boolean
  onConfirm: () => void
  continuing: boolean
  continueNotice: string
  onContinue: () => void
}) {
  const { t, i18n } = useTranslation()
  const [downloadError, setDownloadError] = useState('')
  const changes = result.changes ?? []
  const rejected = result.rejectedChanges ?? []
  const unsaved = hasUnsavedEdit(editingNodeId, draft, suggestionText)
  const confirmDisabled = running || selected.length === 0 || unsaved

  function whyBetter(change: TailorChange) {
    const chinese = i18n.language.toLowerCase().startsWith('zh')
    if (chinese) {
      return change.improvementSummaryZh || change.reasonZh || change.reason
    }
    return change.improvementSummaryEn || change.reason || change.reasonZh
  }

  function upliftLabel(upliftType?: string) {
    if (upliftType === 'SUBSTANTIVE_REWRITE') {
      return t('tailor.upliftSubstantive')
    }
    if (upliftType === 'CLARITY_IMPROVEMENT') {
      return t('tailor.upliftClarity')
    }
    if (upliftType === 'GRAMMAR_FIX') {
      return t('tailor.upliftGrammar')
    }
    return upliftType || null
  }

  function toggleSelected(nodeId: string, checked: boolean) {
    if (checked) {
      setSelected([...selected, nodeId])
    } else {
      setSelected(selected.filter((id) => id !== nodeId))
    }
  }

  function startEdit(change: TailorChange) {
    setEditingNodeId(change.nodeId)
    setDraft(suggestionText[change.nodeId] ?? change.newText)
  }

  function saveEdit(nodeId: string) {
    setSuggestionText({ ...suggestionText, [nodeId]: draft })
    setEditingNodeId(null)
    setDraft('')
  }

  function cancelEdit() {
    setEditingNodeId(null)
    setDraft('')
  }

  function restoreAi(nodeId: string) {
    setSuggestionText({ ...suggestionText, [nodeId]: aiOriginal[nodeId] ?? '' })
    if (editingNodeId === nodeId) {
      setDraft(aiOriginal[nodeId] ?? '')
    }
  }

  const grammarOnly =
    changes.length > 0 && changes.every((change) => change.upliftType === 'GRAMMAR_FIX')

  async function onDownload(url: string, name: string) {
    setDownloadError('')
    try {
      await downloadByUrl(url, name)
    } catch (err) {
      const message = err instanceof Error ? err.message : ''
      setDownloadError(message || t('resumes.downloadFailed'))
    }
  }

  return (
    <div className="mt-8 space-y-6">
      <div className="flex flex-wrap gap-3 text-sm">
        <Badge>{t('tailor.appliedCount', { count: result.appliedChangeCount ?? 0 })}</Badge>
        <Badge>{t('tailor.rejectedCount', { count: result.rejectedChangeCount ?? 0 })}</Badge>
        {result.unchanged ? <Badge>{t('tailor.unchanged')}</Badge> : null}
        {result.pdfExportStatus ? (
          <Badge>
            {t('tailor.pdfStatus')}: {t(`status.pdfExport.${result.pdfExportStatus}`, {
              defaultValue: result.pdfExportStatus,
            })}
          </Badge>
        ) : null}
        {result.status ? (
          <Badge>
            {t('tailor.proposalStatus')}: {t(`status.tailor.${result.status}`, {
              defaultValue: t(`status.proposal.${result.status}`, {
                defaultValue: result.status,
              }),
            })}
          </Badge>
        ) : null}
      </div>

      {result.proposalId ? (
      <section className="rounded-xl border border-slate-200 bg-white p-5">
        <h2 className="text-lg font-medium">{t('tailor.changes')}</h2>
        {needsConfirm && changes.length > 0 ? (
          <p className="mt-2 text-sm text-slate-600">
            {t('tailor.selectedCount', { selected: selected.length, total: changes.length })}
            {' · '}
            {t('tailor.filesAfterConfirm')}
          </p>
        ) : null}
        {grammarOnly ? (
          <p className="mt-3 text-sm text-slate-600">{t('tailor.grammarOnlyNotice')}</p>
        ) : null}
        {needsConfirm && unsaved ? (
          <p className="mt-3 text-sm text-amber-800">{t('tailor.unsavedEdits')}</p>
        ) : null}
        {changes.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">{t('tailor.noChanges')}</p>
        ) : (
          <ul className="mt-4 space-y-4">
            {changes.map((change) => {
              const accepted = selected.includes(change.nodeId)
              const current = suggestionText[change.nodeId] ?? change.newText
              const edited = current !== (aiOriginal[change.nodeId] ?? change.newText)
              const editing = needsConfirm && editingNodeId === change.nodeId
              return (
                <li key={change.nodeId} data-suggestion-node={change.nodeId} className="rounded-lg border border-slate-200 p-4">
                  {needsConfirm ? (
                    <div className="mb-2 flex flex-wrap items-center gap-2 text-sm">
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={accepted}
                          onChange={(event) => toggleSelected(change.nodeId, event.target.checked)}
                        />
                        {accepted ? t('tailor.acceptChange') : t('tailor.declineChange')}
                      </label>
                    </div>
                  ) : null}
                  {upliftLabel(change.upliftType) ? (
                    <p className="mb-2 text-xs font-medium text-slate-600">
                      {t('tailor.suggestionType')}: {upliftLabel(change.upliftType)}
                    </p>
                  ) : null}
                  {whyBetter(change) ? (
                    <p className="mt-1 text-sm text-slate-700">
                      <span className="font-medium">{t('tailor.whyBetter')}: </span>
                      {whyBetter(change)}
                    </p>
                  ) : null}
                  <div className="mt-3 grid gap-3 md:grid-cols-2">
                    <div>
                      <p className="mb-1 text-xs text-slate-500">{t('tailor.original')}</p>
                      <pre className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{change.originalText}</pre>
                    </div>
                    <div>
                      <p className="mb-1 text-xs text-slate-500">{t('tailor.aiSuggestion')}</p>
                      {editing ? (
                        <label className="block">
                          <span className="sr-only">{t('tailor.editSuggestion')}</span>
                          <textarea
                            className="min-h-24 w-full rounded-md border border-slate-300 p-3 text-sm"
                            value={draft}
                            onChange={(event) => setDraft(event.target.value)}
                          />
                        </label>
                      ) : (
                        <pre className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{current}</pre>
                      )}
                    </div>
                  </div>
                  {needsConfirm ? (
                    <div className="mt-3 flex flex-wrap gap-2">
                      {editing ? (
                        <>
                          <button type="button" className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white"
                            onClick={() => saveEdit(change.nodeId)}>
                            {t('tailor.saveEdit')}
                          </button>
                          <button type="button" className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs"
                            onClick={cancelEdit}>
                            {t('tailor.cancelEdit')}
                          </button>
                        </>
                      ) : (
                        <button type="button" className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs"
                          onClick={() => startEdit(change)}>
                          {t('tailor.editSuggestion')}
                        </button>
                      )}
                      {edited ? (
                        <button type="button" className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs"
                          onClick={() => restoreAi(change.nodeId)}>
                          {t('tailor.restoreAi')}
                        </button>
                      ) : null}
                    </div>
                  ) : null}
                  <details className="mt-3">
                    <summary className="cursor-pointer text-xs text-slate-500">{t('tailor.technicalDetails')}</summary>
                    {change.evidenceQuote ? (
                      <p className="mt-2 text-xs text-slate-500">{t('tailor.evidenceQuote')}: {change.evidenceQuote}</p>
                    ) : null}
                    {change.evidenceType === 'VERIFIED' || change.evidenceType === 'USER_EDITED' ? (
                      <p className="text-xs text-slate-500">
                        {change.evidenceType === 'USER_EDITED'
                          ? t('tailor.editedByYou')
                          : t('tailor.enhanced.fromResume')}
                      </p>
                    ) : null}
                  </details>
                </li>
              )
            })}
          </ul>
        )}
        {needsConfirm && continueNotice ? (
          <p className="mt-3 text-sm text-slate-600">{continueNotice}</p>
        ) : null}
        <div className="mt-4 flex flex-wrap gap-3">
          {needsConfirm && result.status === 'PENDING' && result.hasMoreSuggestions ? (
            <button type="button" disabled={continuing}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 disabled:opacity-60"
              onClick={onContinue}>
              {continuing
                ? t('tailor.findingMoreImprovements')
                : result.coverageStatus === 'PARTIAL' || result.coverageStatus === 'FAILED'
                  ? t('tailor.retryScan')
                  : t('tailor.findMoreImprovements')}
            </button>
          ) : null}
          {needsConfirm && changes.length > 0 ? (
            <button type="button" disabled={confirmDisabled}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
              onClick={onConfirm}>
              {running ? t('tailor.confirming') : t('tailor.confirm')}
            </button>
          ) : null}
        </div>
      </section>
      ) : null}

      {result.proposalId && rejected.length > 0 ? (
        <section className="rounded-xl border border-slate-200 bg-white p-5">
          <p className="text-sm text-slate-600">{t('tailor.keptOriginalSummary')}</p>
          <details className="mt-3">
            <summary className="cursor-pointer text-xs text-slate-500">{t('tailor.technicalDetails')}</summary>
            <ul className="mt-2 space-y-1">
              {rejected.map((item) => (
                <li key={`${item.nodeId}-${item.reasonCode}`} className="text-xs text-slate-500">
                  {t(`reasonCode.${item.reasonCode}`, { defaultValue: t('tailor.keptOriginalSummary') })}
                </li>
              ))}
            </ul>
          </details>
        </section>
      ) : null}

      {downloadError ? <div className="mt-2"><Alert message={downloadError} /></div> : null}

      <div className="flex flex-wrap gap-3">
        {result.downloadUrl ? (
          <button type="button" className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white"
            onClick={() => void onDownload(result.downloadUrl!, 'tailored.docx')}>
            {t('tailor.downloadDocx')}
          </button>
        ) : null}
        {result.pdfDownloadUrl && result.pdfExportStatus === 'SUCCESS' ? (
          <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm"
            onClick={() => void onDownload(result.pdfDownloadUrl!, 'tailored.pdf')}>
            {t('tailor.downloadPdf')}
          </button>
        ) : null}
      </div>
    </div>
  )
}

function Badge({ children }: { children: ReactNode }) {
  return (
    <span className="rounded-full bg-slate-100 px-3 py-1 text-slate-700">{children}</span>
  )
}

function sanitizeResult(data: TailoredResumeResponse): TailoredResumeResponse {
  const cleaned = { ...data }
  delete (cleaned as { generatedFilePath?: string }).generatedFilePath
  return cleaned
}
