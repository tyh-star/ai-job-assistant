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
import { Spinner } from '../components/Spinner'

export function ResumesPage() {
  const { t } = useTranslation()
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
      await downloadByUrl(resume.downloadUrl, resume.fileName)
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
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold text-slate-900">{t('resumes.title')}</h1>
        <label className="cursor-pointer rounded-lg bg-slate-900 px-4 py-2 text-sm text-white">
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
      </div>
      <p className="mt-1 text-sm text-slate-500">{t('resumes.chooseFile')}</p>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
      {!loading && items.length === 0 && !error ? (
        <div className="mt-6"><EmptyState title={t('resumes.empty')} /></div>
      ) : null}
      {items.length > 0 ? (
        <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
              <tr>
                <th className="px-4 py-3">{t('resumes.fileName')}</th>
                <th className="px-4 py-3">{t('resumes.type')}</th>
                <th className="px-4 py-3">{t('resumes.status')}</th>
                <th className="px-4 py-3">{t('resumes.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((resume) => (
                <tr key={resume.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-3">{resume.fileName}</td>
                  <td className="px-4 py-3 uppercase">{resume.fileType}</td>
                  <td className="px-4 py-3">{t(statusKey(resume))}</td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-3">
                      {resume.fileAvailable && resume.downloadUrl ? (
                        <button
                          type="button"
                          className="text-slate-700 underline"
                          onClick={() => void onDownload(resume)}
                        >
                          {t('resumes.download')}
                        </button>
                      ) : null}
                      {resume.fileType.toLowerCase() === 'pdf' ? (
                        <Link className="text-slate-700 underline" to={`/resumes/${resume.id}/structure`}>
                          {t('resumes.confirmContent')}
                        </Link>
                      ) : null}
                      <Link className="text-slate-700 underline" to={`/tailor?resumeId=${resume.id}`}>
                        {t('resumes.startTailor')}
                      </Link>
                      <button
                        type="button"
                        className="text-red-700 underline"
                        onClick={() => void onDelete(resume)}
                      >
                        {t('resumes.delete')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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

