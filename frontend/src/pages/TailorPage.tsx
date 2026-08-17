import { useEffect, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { confirmTailorProposal, listJobs, tailorResume } from '../api/jobs'
import {
  canonicalizeResume,
  downloadByUrl,
  getResumeStructure,
  listResumes,
} from '../api/resumes'
import type { JobResponse, ResumeResponse, TailorMode, TailoredResumeResponse } from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import {
  IncompletePdfStructureError,
  ResumeFileUnavailableError,
  runTailorFlow,
  type TailorProgress,
} from '../tailor/runTailorFlow'

export function TailorPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const [resumes, setResumes] = useState<ResumeResponse[]>([])
  const [jobs, setJobs] = useState<JobResponse[]>([])
  const [resumeId, setResumeId] = useState(searchParams.get('resumeId') ?? '')
  const [jobId, setJobId] = useState(searchParams.get('jobId') ?? '')
  const [mode, setMode] = useState<TailorMode>('CONSERVATIVE')
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState<TailorProgress | null>(null)
  const [error, setError] = useState('')
  const [structureResumeId, setStructureResumeId] = useState<number | null>(null)
  const [result, setResult] = useState<TailoredResumeResponse | null>(null)
  const [selected, setSelected] = useState<string[]>([])

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

  const visibleResumes = resumes.filter((resume) => resume.origin !== 'TEMPLATE')
  const selectedResume = visibleResumes.find((item) => String(item.id) === resumeId)
  const fileUnavailable = Boolean(selectedResume && !selectedResume.fileAvailable)
  const selectedPdf =
    selectedResume?.fileType.toLowerCase() === 'pdf' && selectedResume.fileAvailable
      ? selectedResume
      : null
  const needsConfirm = Boolean(result?.proposalId) && !result?.downloadUrl

  async function onRun() {
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
    setResult(null)
    setSelected([])
    try {
      const data = await runTailorFlow({
        resume,
        jobId: Number(jobId),
        mode,
        getResumeStructure,
        canonicalizeResume,
        tailorResume,
        onProgress: setProgress,
      })
      setResult(sanitizeResult(data))
      setSelected(
        (data.changes ?? [])
          .filter((change) => change.requiresConfirmation)
          .map((change) => change.nodeId),
      )
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
    }
  }

  async function onConfirm() {
    if (!result?.proposalId || !jobId) {
      return
    }
    if (selected.length === 0) {
      setError(t('tailor.needSelection'))
      return
    }
    setRunning(true)
    setError('')
    try {
      const { data } = await confirmTailorProposal(
        Number(jobId),
        result.proposalId,
        selected,
      )
      setResult(sanitizeResult(data))
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setRunning(false)
    }
  }

  const progressLabel = progressLabelKey(progress)

  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-900">{t('tailor.title')}</h1>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? (
        <div className="mt-4">
          <Alert message={error} />
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

      <div className="mt-6 grid gap-4 rounded-xl border border-slate-200 bg-white p-5 md:grid-cols-2">
        <label className="text-sm">
          <span>{t('tailor.selectResume')}</span>
          <select className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            value={resumeId} onChange={(event) => setResumeId(event.target.value)}>
            <option value="">{t('tailor.selectResume')}</option>
            {visibleResumes.map((resume) => (
              <option key={resume.id} value={resume.id}>
                {resume.fileName}
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
        <fieldset className="md:col-span-2 text-sm">
          <legend className="mb-2">{t('tailor.mode')}</legend>
          <label className="mr-6 inline-flex items-center gap-2">
            <input type="radio" checked={mode === 'CONSERVATIVE'}
              onChange={() => setMode('CONSERVATIVE')} />
            {t('tailor.conservative')}
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="radio" checked={mode === 'AGGRESSIVE'}
              onChange={() => setMode('AGGRESSIVE')} />
            {t('tailor.aggressive')}
          </label>
        </fieldset>
        <div>
          <button type="button" disabled={running || fileUnavailable}
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
            onClick={() => void onRun()}>
            {running && progressLabel ? t(progressLabel) : running ? t('tailor.tailoringJob') : t('tailor.run')}
          </button>
        </div>
      </div>

      {!loading && visibleResumes.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">{t('tailor.emptyResumes')}</p>
      ) : null}
      {!loading && jobs.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500">{t('tailor.emptyJobs')}</p>
      ) : null}

      {result ? <ResultPanel result={result} selected={selected} setSelected={setSelected}
        needsConfirm={needsConfirm} running={running} onConfirm={() => void onConfirm()} /> : null}
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

function ResultPanel({
  result,
  selected,
  setSelected,
  needsConfirm,
  running,
  onConfirm,
}: {
  result: TailoredResumeResponse
  selected: string[]
  setSelected: (value: string[]) => void
  needsConfirm: boolean
  running: boolean
  onConfirm: () => void
}) {
  const { t } = useTranslation()
  const [downloadError, setDownloadError] = useState('')
  const changes = result.changes ?? []
  const rejected = result.rejectedChanges ?? []

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
            {t('tailor.proposalStatus')}: {t(`status.proposal.${result.status}`, {
              defaultValue: result.status,
            })}
          </Badge>
        ) : null}
      </div>

      <section className="rounded-xl border border-slate-200 bg-white p-5">
        <h2 className="text-lg font-medium">{t('tailor.changes')}</h2>
        {changes.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">{t('tailor.noChanges')}</p>
        ) : (
          <ul className="mt-4 space-y-4">
            {changes.map((change) => (
              <li key={change.nodeId} className="rounded-lg border border-slate-200 p-4">
                {needsConfirm ? (
                  <label className="mb-2 flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={selected.includes(change.nodeId)}
                      onChange={(event) => {
                        if (event.target.checked) {
                          setSelected([...selected, change.nodeId])
                        } else {
                          setSelected(selected.filter((id) => id !== change.nodeId))
                        }
                      }}
                    />
                    {t('tailor.selectChange')}
                  </label>
                ) : null}
                <p className="text-xs text-slate-500">{t('tailor.nodeId')}: {change.nodeId}</p>
                {change.evidenceType ? (
                  <p className="text-xs text-slate-500">
                    {t('tailor.evidenceType')}: {t(`status.evidence.${change.evidenceType}`, {
                      defaultValue: change.evidenceType,
                    })}
                  </p>
                ) : null}
                {change.reason ? (
                  <p className="mt-1 text-sm text-slate-600">{t('tailor.reason')}: {change.reason}</p>
                ) : null}
                <div className="mt-3 grid gap-3 md:grid-cols-2">
                  <div>
                    <p className="mb-1 text-xs text-slate-500">{t('tailor.original')}</p>
                    <pre className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{change.originalText}</pre>
                  </div>
                  <div>
                    <p className="mb-1 text-xs text-slate-500">{t('tailor.rewritten')}</p>
                    <pre className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{change.newText}</pre>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
        {needsConfirm ? (
          <button type="button" disabled={running}
            className="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
            onClick={onConfirm}>
            {running ? t('tailor.confirming') : t('tailor.confirm')}
          </button>
        ) : null}
      </section>

      {rejected.length > 0 ? (
        <section className="rounded-xl border border-slate-200 bg-white p-5">
          <h2 className="text-lg font-medium">{t('tailor.rejected')}</h2>
          <ul className="mt-3 space-y-2 text-sm">
            {rejected.map((item) => (
              <li key={`${item.nodeId}-${item.reasonCode}`}>
                <span className="font-mono text-xs">{item.nodeId}</span>
                {' · '}
                <span className="font-mono text-xs">{item.reasonCode}</span>
                {' — '}
                {t(`reasonCode.${item.reasonCode}`, { defaultValue: item.reasonCode })}
              </li>
            ))}
          </ul>
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
