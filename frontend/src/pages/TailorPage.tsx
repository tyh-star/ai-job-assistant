/**
 * 简历改写 / Resume Rewrite at /tailor.
 *
 * Two separate workflows with their own backend endpoints:
 *   Free  POST /resume-rewrites/free                     resume only, one request, file at once
 *   Pro   POST /resume-rewrites/pro/sessions             1-2 analyze the job, check evidence
 *         POST .../{id}/answers                          3   up to five questions
 *         POST .../{id}/preview                          4   suggestions to review (no file)
 *         POST .../{id}/confirm                          5-6 accepted suggestions -> file
 * Free never asks for a job and never calls job matching. Switching mode keeps the chosen
 * resume and clears everything that belonged to the other workflow.
 */

import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { AxiosError } from 'axios'
import { apiErrorKey } from '../api/client'
import { listJobs } from '../api/jobs'
import { canonicalizeResume, downloadByUrl, getResumeStructure, listResumes } from '../api/resumes'
import {
  answerProRewrite,
  confirmProRewrite,
  createFreeRewrite,
  previewProRewrite,
  startProRewrite,
} from '../api/resumeRewrite'
import type {
  FreeRewriteResponse,
  JobResponse,
  ProRewriteItem,
  ProRewriteSession,
  ResumeResponse,
} from '../api/types'
import { Alert } from '../components/Alert'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { LoadingSkeleton } from '../components/ui/LoadingSkeleton'
import { SectionHeader } from '../components/ui/SectionHeader'
import { cx } from '../components/ui/cx'
import { displayFileName } from '../resumes/fileName'
import { PRO_STEPS, proStepsDone } from '../rewrite/proSteps'

type RewriteMode = 'FREE' | 'PRO'

type Busy = null | 'preparing' | 'free' | 'analyze' | 'answers' | 'preview' | 'confirm' | 'download'

type AnswerDraft = { hasExperience: boolean | null; detail: string }

class IncompletePdfError extends Error {}

export function TailorPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  // Free by default; a saved job's own link opens Pro with that job (?mode=pro&jobId=).
  const [mode, setMode] = useState<RewriteMode>(searchParams.get('mode') === 'pro' ? 'PRO' : 'FREE')
  const [resumes, setResumes] = useState<ResumeResponse[]>([])
  const [jobs, setJobs] = useState<JobResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [resumeId, setResumeId] = useState(searchParams.get('resumeId') ?? '')
  const [jobId, setJobId] = useState(searchParams.get('jobId') ?? '')
  const [busy, setBusy] = useState<Busy>(null)
  const [error, setError] = useState('')
  const [pdfIncomplete, setPdfIncomplete] = useState(false)
  const [freeResult, setFreeResult] = useState<FreeRewriteResponse | null>(null)
  const [session, setSession] = useState<ProRewriteSession | null>(null)
  const [answers, setAnswers] = useState<Record<string, AnswerDraft>>({})
  const [accepted, setAccepted] = useState<string[]>([])

  async function load() {
    setLoading(true)
    setLoadError('')
    const [resumeResult, jobResult] = await Promise.allSettled([listResumes(), listJobs()])
    if (resumeResult.status === 'fulfilled') {
      setResumes(resumeResult.value.data)
    }
    if (jobResult.status === 'fulfilled') {
      setJobs(jobResult.value.data)
    }
    if (resumeResult.status === 'rejected' || jobResult.status === 'rejected') {
      setLoadError(t('rewrite.loadFailed'))
    }
    setLoading(false)
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectedResume = resumes.find((resume) => String(resume.id) === resumeId) ?? null

  /** Anything produced for the previous choice no longer applies. */
  function clearRun() {
    setFreeResult(null)
    setSession(null)
    setAnswers({})
    setAccepted([])
    setError('')
    setPdfIncomplete(false)
  }

  function onModeChange(next: RewriteMode) {
    if (next === mode) {
      return
    }
    setMode(next)
    clearRun()
  }

  function onResumeChange(value: string) {
    setResumeId(value)
    clearRun()
  }

  function onJobChange(value: string) {
    setJobId(value)
    clearRun()
  }

  function showError(err: unknown) {
    if (err instanceof IncompletePdfError) {
      setError(t('rewrite.pdfIncomplete'))
      setPdfIncomplete(true)
      return
    }
    setError(t(apiErrorKey(err)))
  }

  /** A PDF is rewritten through its confirmed DOCX copy; make sure it exists. */
  async function prepareResume(resume: ResumeResponse) {
    if (resume.fileType.toLowerCase() !== 'pdf') {
      return
    }
    setBusy('preparing')
    await getResumeStructure(resume.id)
    try {
      await canonicalizeResume(resume.id)
    } catch (err) {
      if ((err as AxiosError).response?.status === 400) {
        throw new IncompletePdfError()
      }
      throw err
    }
  }

  async function run(step: Exclude<Busy, null>, action: () => Promise<void>) {
    setError('')
    setPdfIncomplete(false)
    setBusy(step)
    try {
      await action()
    } catch (err) {
      showError(err)
    } finally {
      setBusy(null)
    }
  }

  function onFree() {
    if (!selectedResume) {
      return
    }
    void run('free', async () => {
      await prepareResume(selectedResume)
      setBusy('free')
      const { data } = await createFreeRewrite(selectedResume.id)
      setFreeResult(data)
    })
  }

  function onAnalyze() {
    if (!selectedResume || !jobId) {
      setError(t('rewrite.pro.jobRequired'))
      return
    }
    void run('analyze', async () => {
      await prepareResume(selectedResume)
      setBusy('analyze')
      const { data } = await startProRewrite(selectedResume.id, Number(jobId))
      setSession(data)
      setAnswers(Object.fromEntries(data.questions.map((q) => [q.id, { hasExperience: null, detail: '' }])))
    })
  }

  const answersValid = useMemo(
    () => Object.values(answers).every((a) => a.hasExperience !== true || a.detail.trim().length >= 5),
    [answers],
  )

  function onSubmitAnswers() {
    if (!session || !answersValid) {
      return
    }
    void run('answers', async () => {
      const payload = session.questions.map((q) => {
        const draft = answers[q.id] ?? { hasExperience: null, detail: '' }
        const has = draft.hasExperience === true
        return { questionId: q.id, hasExperience: has, detail: has ? draft.detail.trim() : undefined }
      })
      const { data } = await answerProRewrite(session.sessionId, payload)
      setSession(data)
    })
  }

  function onPreview() {
    if (!session) {
      return
    }
    void run('preview', async () => {
      const { data } = await previewProRewrite(session.sessionId)
      setSession(data)
      setAccepted(data.suggestions.map((s) => s.id))
    })
  }

  function onConfirm() {
    if (!session || accepted.length === 0) {
      return
    }
    void run('confirm', async () => {
      const { data } = await confirmProRewrite(session.sessionId, accepted)
      setSession(data)
    })
  }

  function onDownload(url: string, name: string) {
    void run('download', async () => {
      await downloadByUrl(url, name)
    })
  }

  const disabled = busy !== null
  const usableResumes = resumes.filter((resume) => resume.fileAvailable)
  const done = proStepsDone(session)

  let proPrimary: ReactNode = null
  if (mode === 'PRO') {
    if (!session) {
      proPrimary = (
        <Button onClick={onAnalyze} disabled={disabled || !selectedResume || !jobId}>
          {busy === 'analyze' || busy === 'preparing' ? t('rewrite.pro.actions.analyzing') : t('rewrite.pro.actions.analyze')}
        </Button>
      )
    } else if (session.status === 'ANALYZED' && session.questions.length > 0) {
      proPrimary = (
        <Button onClick={onSubmitAnswers} disabled={disabled || !answersValid}>
          {busy === 'answers' ? t('rewrite.pro.actions.submittingAnswers') : t('rewrite.pro.actions.submitAnswers')}
        </Button>
      )
    } else if (session.status === 'ANALYZED' || session.status === 'ANSWERED') {
      proPrimary = (
        <Button onClick={onPreview} disabled={disabled}>
          {busy === 'preview' ? t('rewrite.pro.actions.previewing') : t('rewrite.pro.actions.preview')}
        </Button>
      )
    } else if (session.status === 'PREVIEWED') {
      proPrimary = (
        <Button onClick={onConfirm} disabled={disabled || accepted.length === 0}>
          {busy === 'confirm' ? t('rewrite.pro.actions.confirming') : t('rewrite.pro.actions.confirm')}
        </Button>
      )
    } else if (session.status === 'COMPLETED' && session.downloadUrl) {
      proPrimary = (
        <Button onClick={() => onDownload(session.downloadUrl!, session.downloadFileName ?? 'resume.docx')} disabled={disabled}>
          {t('rewrite.pro.actions.download')}
        </Button>
      )
    }
  }

  return (
    <div className="min-w-0">
      <SectionHeader title={t('tailor.title')} />

      <div role="radiogroup" aria-label={t('rewrite.modesLabel')} className="mt-4 grid min-w-0 gap-3 sm:grid-cols-2">
        <ModeCard
          checked={mode === 'FREE'}
          disabled={disabled}
          onSelect={() => onModeChange('FREE')}
          title={t('rewrite.free.title')}
          description={t('rewrite.free.description')}
        />
        <ModeCard
          checked={mode === 'PRO'}
          disabled={disabled}
          onSelect={() => onModeChange('PRO')}
          title={t('rewrite.pro.title')}
          badge={<Badge tone="warning">{t('rewrite.pro.paidTag')}</Badge>}
          description={t('rewrite.pro.description')}
        />
      </div>

      {loading ? <div className="mt-4"><LoadingSkeleton rows={2} label={t('common.loading')} /></div> : null}
      {loadError ? <div className="mt-4"><Alert message={loadError} onRetry={() => void load()} /></div> : null}

      {!loading ? (
        <Card className="mt-4 min-w-0">
          <div className={cx('grid min-w-0 gap-4', mode === 'PRO' && 'md:grid-cols-2')}>
            <label className="block min-w-0 text-sm text-ink">
              <span className="font-medium">{t('rewrite.selectResume')}</span>
              <select
                className="ui-input mt-1"
                value={resumeId}
                disabled={disabled}
                onChange={(event) => onResumeChange(event.target.value)}
              >
                <option value="">{t('rewrite.selectResumePlaceholder')}</option>
                {usableResumes.map((resume) => (
                  <option key={resume.id} value={resume.id}>{displayFileName(resume.fileName)}</option>
                ))}
              </select>
              {usableResumes.length === 0 ? (
                <span className="mt-1 block text-xs text-muted">
                  {t('rewrite.noResumes')} <Link className="text-brand underline" to="/resumes">{t('rewrite.goResumes')}</Link>
                </span>
              ) : null}
            </label>
            {mode === 'PRO' ? (
              <label className="block min-w-0 text-sm text-ink">
                <span className="font-medium">{t('rewrite.selectJob')}</span>
                <select
                  className="ui-input mt-1"
                  value={jobId}
                  disabled={disabled}
                  onChange={(event) => onJobChange(event.target.value)}
                >
                  <option value="">{t('rewrite.selectJobPlaceholder')}</option>
                  {jobs.map((job) => (
                    <option key={job.id} value={job.id}>{[job.title, job.company].filter(Boolean).join(' · ')}</option>
                  ))}
                </select>
                {jobs.length === 0 ? (
                  <span className="mt-1 block text-xs text-muted">
                    {t('rewrite.noJobs')} <Link className="text-brand underline" to="/jobs">{t('rewrite.goJobs')}</Link>
                  </span>
                ) : null}
              </label>
            ) : null}
          </div>

          {mode === 'PRO' ? <ProSteps done={done} /> : null}

          {busy === 'preparing' ? <p role="status" className="mt-4 text-sm text-muted">{t('rewrite.preparingPdf')}</p> : null}
          {error ? (
            <div className="mt-4">
              <Alert message={error} />
              {pdfIncomplete ? (
                <Link className="mt-2 inline-block text-sm text-brand underline" to={`/resumes/${resumeId}/structure`}>
                  {t('rewrite.pdfFix')}
                </Link>
              ) : null}
            </div>
          ) : null}

          <div className="mt-4 flex min-w-0 flex-wrap items-center gap-3">
            {mode === 'FREE' && !freeResult ? (
              <Button onClick={onFree} disabled={disabled || !selectedResume}>
                {busy === 'free' || busy === 'preparing' ? t('rewrite.free.running') : t('rewrite.free.action')}
              </Button>
            ) : null}
            {mode === 'FREE' && freeResult ? (
              <Button onClick={() => onDownload(freeResult.downloadUrl, freeResult.downloadFileName)} disabled={disabled}>
                {t('rewrite.free.download')}
              </Button>
            ) : null}
            {proPrimary}
            {mode === 'PRO' && session?.status === 'COMPLETED' ? (
              <Button variant="secondary" onClick={clearRun} disabled={disabled}>{t('rewrite.pro.actions.restart')}</Button>
            ) : null}
          </div>
        </Card>
      ) : null}

      {mode === 'FREE' && freeResult ? <FreeResult result={freeResult} /> : null}

      {mode === 'PRO' && session ? (
        <>
          <RequirementsPanel session={session} />
          {session.status === 'ANALYZED' ? (
            session.questions.length > 0 ? (
              <Card className="mt-4 min-w-0">
                <h2 className="section-title">{t('rewrite.pro.questionsTitle')}</h2>
                <p className="mt-1 text-sm text-muted">{t('rewrite.pro.questionsHint')}</p>
                <ol className="mt-3 grid min-w-0 gap-4">
                  {session.questions.map((question) => {
                    const draft = answers[question.id] ?? { hasExperience: null, detail: '' }
                    const setDraft = (next: AnswerDraft) => setAnswers((all) => ({ ...all, [question.id]: next }))
                    return (
                      <li key={question.id} className="min-w-0 rounded-md border border-line p-3">
                        <p className="break-words text-sm font-medium text-ink">{question.question}</p>
                        <div className="mt-2 flex flex-wrap gap-4 text-sm">
                          <label className="inline-flex items-center gap-2">
                            <input
                              type="radio"
                              name={`answer-${question.id}`}
                              checked={draft.hasExperience === true}
                              onChange={() => setDraft({ ...draft, hasExperience: true })}
                            />
                            {t('rewrite.pro.hasExperience')}
                          </label>
                          <label className="inline-flex items-center gap-2">
                            <input
                              type="radio"
                              name={`answer-${question.id}`}
                              checked={draft.hasExperience === false}
                              onChange={() => setDraft({ ...draft, hasExperience: false })}
                            />
                            {t('rewrite.pro.noExperience')}
                          </label>
                        </div>
                        {draft.hasExperience ? (
                          <>
                            <label className="mt-2 block text-sm">
                              <span className="text-muted">{t('rewrite.pro.detailLabel')}</span>
                              <textarea
                                className="ui-input mt-1 min-h-20"
                                maxLength={1000}
                                value={draft.detail}
                                onChange={(event) => setDraft({ ...draft, detail: event.target.value })}
                              />
                            </label>
                            {draft.detail.trim().length < 5 ? (
                              <span className="mt-1 block text-xs text-warning">{t('rewrite.pro.detailRequired')}</span>
                            ) : null}
                          </>
                        ) : null}
                      </li>
                    )
                  })}
                </ol>
              </Card>
            ) : (
              <p className="mt-4 text-sm text-muted">{t('rewrite.pro.noQuestions')}</p>
            )
          ) : null}
          {session.status === 'PREVIEWED' ? (
            <SuggestionsPanel session={session} accepted={accepted} onChange={setAccepted} />
          ) : null}
          {session.status === 'COMPLETED' ? (
            <Card className="mt-4 min-w-0">
              <p role="status" className="text-sm font-medium text-success">{t('rewrite.pro.done')}</p>
              <h2 className="section-title mt-3">{t('rewrite.pro.finalTitle', { count: session.finalChanges.length })}</h2>
              <ul className="mt-2 grid min-w-0 gap-3">
                {session.finalChanges.map((change) => (
                  <li key={change.nodeId} className="min-w-0 break-words text-sm text-ink">{change.finalText}</li>
                ))}
              </ul>
            </Card>
          ) : null}
        </>
      ) : null}
    </div>
  )
}

function ModeCard({
  checked,
  disabled,
  onSelect,
  title,
  description,
  badge,
}: {
  checked: boolean
  disabled: boolean
  onSelect: () => void
  title: string
  description: string
  badge?: ReactNode
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      disabled={disabled}
      onClick={onSelect}
      className={cx(
        'min-w-0 rounded-lg border bg-card p-4 text-left shadow-card transition-colors disabled:opacity-60',
        checked ? 'border-accent ring-2 ring-accent/30' : 'border-line hover:border-accent/50',
      )}
    >
      <span className="flex min-w-0 flex-wrap items-center gap-2">
        <span className="text-base font-semibold text-ink">{title}</span>
        {badge}
      </span>
      <span className="mt-1 block text-sm text-muted">{description}</span>
    </button>
  )
}

function ProSteps({ done }: { done: number }) {
  const { t } = useTranslation()
  return (
    <ol aria-label={t('rewrite.pro.stepsLabel')} className="mt-4 grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
      {PRO_STEPS.map((step, index) => {
        const state = index < done ? 'done' : index === done ? 'current' : 'upcoming'
        return (
          <li
            key={step}
            aria-current={state === 'current' ? 'step' : undefined}
            className={cx(
              'min-w-0 rounded-md border px-2 py-1.5 text-xs',
              state === 'done' && 'border-success/30 bg-success-soft text-success',
              state === 'current' && 'border-accent bg-accent-soft font-medium text-brand',
              state === 'upcoming' && 'border-line text-muted',
            )}
          >
            <span className="block">{index + 1}. {t(`rewrite.pro.steps.${step}`)}</span>
            <span className="sr-only">
              {state === 'done' ? t('rewrite.pro.stepDone') : state === 'current' ? t('rewrite.pro.stepCurrent') : t('rewrite.pro.stepUpcoming')}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

function FreeResult({ result }: { result: FreeRewriteResponse }) {
  const { t } = useTranslation()
  return (
    <Card className="mt-4 min-w-0">
      <p role="status" className="text-sm font-medium text-success">
        {result.unchanged ? t('rewrite.free.unchanged') : t('rewrite.free.done')}
      </p>
      {result.changes.length > 0 ? (
        <>
          <h2 className="section-title mt-3">{t('rewrite.free.changesTitle', { count: result.changes.length })}</h2>
          <ul className="mt-2 grid min-w-0 gap-3">
            {result.changes.map((change) => (
              <ChangeRow key={change.nodeId} before={change.originalText} after={change.revisedText} reason={change.reason} />
            ))}
          </ul>
        </>
      ) : null}
      {result.rejectedCount > 0 ? (
        <p className="mt-3 text-xs text-muted">{t('rewrite.free.rejected', { count: result.rejectedCount })}</p>
      ) : null}
    </Card>
  )
}

function ChangeRow({ before, after, reason, extra }: { before: string; after: string; reason: string; extra?: ReactNode }) {
  const { t } = useTranslation()
  return (
    <li className="grid min-w-0 gap-2 rounded-md border border-line p-3 text-sm md:grid-cols-2">
      <div className="min-w-0">
        <span className="text-xs text-muted">{t('rewrite.pro.before')}</span>
        <p className="break-words text-slate-600 line-through decoration-slate-300">{before}</p>
      </div>
      <div className="min-w-0">
        <span className="text-xs text-muted">{t('rewrite.pro.after')}</span>
        <p className="break-words text-ink">{after}</p>
        {reason ? <p className="mt-1 text-xs text-muted">{reason}</p> : null}
        {extra}
      </div>
    </li>
  )
}

const STATUS_TONE: Record<ProRewriteItem['status'], 'success' | 'warning' | 'neutral' | 'accent'> = {
  VERIFIED: 'success',
  USER_CONFIRMED: 'accent',
  INFERRED: 'warning',
  UNSUPPORTED: 'neutral',
}

function RequirementsPanel({ session }: { session: ProRewriteSession }) {
  const { t } = useTranslation()
  return (
    <Card className="mt-4 min-w-0">
      <h2 className="section-title">{t('rewrite.pro.requirementsTitle')}</h2>
      {session.requirements.experienceLevel ? (
        <p className="mt-1 text-sm text-muted">{t('rewrite.pro.experienceLevel', { level: session.requirements.experienceLevel })}</p>
      ) : null}
      <ul className="mt-3 grid min-w-0 gap-2">
        {session.items.map((item) => (
          <li key={item.id} className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
            <span className="text-xs text-muted">{t(`rewrite.pro.categories.${item.category}`)}</span>
            <span className="min-w-0 break-words text-ink">{item.text}</span>
            <Badge tone={STATUS_TONE[item.status]}>{t(`rewrite.pro.status.${item.status}`)}</Badge>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function SuggestionsPanel({
  session,
  accepted,
  onChange,
}: {
  session: ProRewriteSession
  accepted: string[]
  onChange: (ids: string[]) => void
}) {
  const { t } = useTranslation()
  return (
    <Card className="mt-4 min-w-0">
      <h2 className="section-title">{t('rewrite.pro.suggestionsTitle', { count: session.suggestions.length })}</h2>
      <p className="mt-1 text-sm text-muted">{t('rewrite.pro.suggestionsHint')}</p>
      {session.suggestions.length === 0 ? <p className="mt-3 text-sm text-muted">{t('rewrite.pro.noSuggestions')}</p> : null}
      <ul className="mt-3 grid min-w-0 gap-3">
        {session.suggestions.map((suggestion) => {
          const checked = accepted.includes(suggestion.id)
          return (
            <ChangeRow
              key={suggestion.id}
              before={suggestion.originalText}
              after={suggestion.revisedText}
              reason={suggestion.reason}
              extra={
                <div className="mt-2 flex flex-wrap items-center gap-3">
                  <label className="inline-flex items-center gap-2 text-sm font-medium text-ink">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={(event) =>
                        onChange(event.target.checked ? [...accepted, suggestion.id] : accepted.filter((id) => id !== suggestion.id))
                      }
                    />
                    {t('rewrite.pro.useSuggestion')}
                  </label>
                  <Badge tone={suggestion.evidenceStatus === 'VERIFIED' ? 'success' : 'accent'}>
                    {t(`rewrite.pro.evidence.${suggestion.evidenceStatus}`)}
                  </Badge>
                </div>
              }
            />
          )
        })}
      </ul>
      {session.suggestions.length > 0 && accepted.length === 0 ? (
        <p className="mt-3 text-xs text-warning">{t('rewrite.pro.selectAtLeastOne')}</p>
      ) : null}
      {session.rejectedSuggestionCount > 0 ? (
        <p className="mt-3 text-xs text-muted">{t('rewrite.pro.rejected', { count: session.rejectedSuggestionCount })}</p>
      ) : null}
    </Card>
  )
}
