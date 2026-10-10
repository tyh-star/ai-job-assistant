import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  deleteResume,
  deleteResumeFamily,
  downloadByUrl,
  listResumes,
  uploadResume,
} from '../api/resumes'
import type { ResumeResponse } from '../api/types'
import { Alert } from '../components/Alert'
import { EmptyState } from '../components/EmptyState'
import { Badge } from '../components/ui/Badge'
import { Card } from '../components/ui/Card'
import { LoadingSkeleton } from '../components/ui/LoadingSkeleton'
import { SectionHeader } from '../components/ui/SectionHeader'
import { displayFileName, safeDownloadFileName } from '../resumes/fileName'

export function ResumesPage() {
  const { t, i18n } = useTranslation()
  const [items, setItems] = useState<ResumeResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      const { data } = await listResumes()
      setItems(data.filter((resume) => resume.origin !== 'TEMPLATE'))
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function onFile(file: File | undefined) {
    if (!file) {
      return
    }
    const lower = file.name.toLowerCase()
    if (!lower.endsWith('.pdf') && !lower.endsWith('.docx')) {
      setError(t('resumes.invalidType'))
      return
    }
    setUploading(true)
    setError('')
    try {
      await uploadResume(file)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setUploading(false)
    }
  }

  async function onDownload(resume: ResumeResponse) {
    if (!resume.fileAvailable || !resume.downloadUrl) {
      return
    }
    setError('')
    try {
      await downloadByUrl(resume.downloadUrl, safeDownloadFileName(resume.fileName, `resume.${resume.fileType.toLowerCase()}`))
    } catch (err) {
      const message = err instanceof Error ? err.message : ''
      setError(message || t('resumes.downloadFailed'))
    }
  }

  async function onDelete(resume: ResumeResponse) {
    const isPdf = resume.fileType.toLowerCase() === 'pdf'
    const hasChild = resume.canonicalResumeId != null
    const confirmed = window.confirm(
      isPdf && hasChild ? t('resumes.deleteFamilyConfirm') : t('resumes.deleteConfirm'),
    )
    if (!confirmed) {
      return
    }
    setError('')
    try {
      if (isPdf && hasChild) {
        await deleteResumeFamily(resume.id)
      } else {
        await deleteResume(resume.id)
      }
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  return (
    <div>
      <SectionHeader
        title={t('resumes.title')}
        description={t('resumes.chooseFile')}
        action={
          <>
            <Link
              to="/resumes/builder"
              className="inline-flex min-h-11 items-center rounded-md bg-accent px-4 py-2 text-sm font-medium text-white"
            >
              {t('resumes.buildNew')}
            </Link>
            <label className="inline-flex min-h-11 cursor-pointer items-center rounded-md border border-line bg-card px-4 py-2 text-sm font-medium text-ink">
              {uploading ? t('resumes.uploading') : t('resumes.upload')}
              <input
                type="file"
                className="hidden"
                accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                disabled={uploading}
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  event.target.value = ''
                  void onFile(file)
                }}
              />
            </label>
          </>
        }
      />
      {loading ? <div className="mt-6"><LoadingSkeleton rows={3} label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
      {!loading && items.length === 0 && !error ? (
        <div className="mt-6">
          <EmptyState
            title={t('resumes.empty')}
            action={
              <Link className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white" to="/resumes/builder">
                {t('resumes.buildNew')}
              </Link>
            }
          />
        </div>
      ) : null}
      {items.length > 0 ? (
        <ul className="mt-6 grid gap-3">
          {items.map((resume) => (
            <li key={resume.id}>
              <Card>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="break-words text-base font-semibold text-ink">{displayFileName(resume.fileName)}</p>
                    <p className="mt-1 text-sm text-muted">
                      {t('resumes.createdAt')}: {new Date(resume.createdAt).toLocaleString(i18n.language)}
                    </p>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {resume.origin === 'GENERATED' ? (
                        <Badge tone="accent">{t('resumes.generatedBadge')}</Badge>
                      ) : (
                        <Badge>{t('resumes.uploadedBadge')}</Badge>
                      )}
                      <Badge>{resume.fileType.toUpperCase()}</Badge>
                      <Badge tone={statusTone(resume)}>{t(statusKey(resume))}</Badge>
                    </div>
                  </div>
                  <div className="flex min-w-0 flex-wrap gap-2">
                    {resume.fileAvailable && resume.downloadUrl ? (
                      <button type="button" className="rounded-md border border-line px-3 py-2 text-sm" onClick={() => void onDownload(resume)}>
                        {t('resumes.download')}
                      </button>
                    ) : null}
                    {resume.fileType.toLowerCase() === 'pdf' ? (
                      <Link className="rounded-md border border-line px-3 py-2 text-sm" to={`/resumes/${resume.id}/structure`}>
                        {t('resumes.confirmContent')}
                      </Link>
                    ) : null}
                    <Link className="rounded-md border border-line px-3 py-2 text-sm" to={`/resumes/${resume.id}/quality-score`}>
                      {t('resumes.viewQualityScore')}
                    </Link>
                    <Link className="rounded-md border border-line px-3 py-2 text-sm" to={`/tailor?resumeId=${resume.id}`}>
                      {t('resumes.startTailor')}
                    </Link>
                    <button type="button" className="rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger" onClick={() => void onDelete(resume)}>
                      {t('resumes.delete')}
                    </button>
                  </div>
                </div>
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

function statusKey(resume: ResumeResponse): string {
  if (!resume.fileAvailable) {
    return 'resumes.statusUnavailable'
  }
  if (resume.fileType.toLowerCase() === 'pdf' && resume.reviewStatus === 'PENDING_REVIEW') {
    return 'resumes.statusNeedsReview'
  }
  return 'resumes.statusReady'
}

function statusTone(resume: ResumeResponse): 'neutral' | 'warning' | 'success' {
  if (!resume.fileAvailable) {
    return 'warning'
  }
  if (resume.fileType.toLowerCase() === 'pdf' && resume.reviewStatus === 'PENDING_REVIEW') {
    return 'warning'
  }
  return 'success'
}
