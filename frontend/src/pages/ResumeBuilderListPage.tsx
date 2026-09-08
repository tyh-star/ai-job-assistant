import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  createResumeBuilderDraft,
  deleteResumeBuilderDraft,
  duplicateResumeBuilderDraft,
  listResumeBuilderDrafts,
} from '../api/resumeBuilder'
import type { ResumeBuilderDraftSummary, ResumeBuilderTemplateType } from '../api/types'
import { Alert } from '../components/Alert'
import { EmptyState } from '../components/EmptyState'
import { Spinner } from '../components/Spinner'
import { TEMPLATE_TYPES } from '../resumeBuilder/constants'

const TEMPLATE_LABEL_KEY: Record<ResumeBuilderTemplateType, string> = {
  GENERIC_ATS: 'resumeBuilder.templates.genericAts',
  STUDENT: 'resumeBuilder.templates.student',
  INTERNSHIP: 'resumeBuilder.templates.internship',
  EXPERIENCED: 'resumeBuilder.templates.experienced',
  CAREER_CHANGE: 'resumeBuilder.templates.careerChange',
  TECHNICAL: 'resumeBuilder.templates.technical',
}

export function ResumeBuilderListPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [items, setItems] = useState<ResumeBuilderDraftSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [templateType, setTemplateType] = useState<ResumeBuilderTemplateType>('GENERIC_ATS')

  async function load() {
    setLoading(true)
    setError('')
    try {
      const { data } = await listResumeBuilderDrafts()
      setItems(data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function onCreate() {
    setCreating(true)
    setError('')
    try {
      const locale = i18n.language.startsWith('zh') ? 'zh-CN' : 'en'
      const { data } = await createResumeBuilderDraft({
        title: t('resumeBuilder.list.untitled'),
        templateType,
        locale,
      })
      navigate(`/resumes/builder/${data.id}`)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setCreating(false)
    }
  }

  async function onDuplicate(id: number) {
    setError('')
    try {
      await duplicateResumeBuilderDraft(id)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  async function onDelete(id: number) {
    if (!window.confirm(t('resumeBuilder.list.deleteConfirm'))) {
      return
    }
    setError('')
    try {
      await deleteResumeBuilderDraft(id)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold text-slate-900">{t('resumeBuilder.list.title')}</h1>
        <Link to="/resumes" className="text-sm text-slate-600 underline">
          {t('resumeBuilder.list.backToResumes')}
        </Link>
      </div>
      <p className="mt-1 text-sm text-slate-500">{t('resumeBuilder.list.subtitle')}</p>

      <div className="mt-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-white p-4">
        <label className="text-sm">
          <span className="mb-1 block text-slate-600">{t('resumeBuilder.list.templateType')}</span>
          <select
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
            value={templateType}
            onChange={(event) => setTemplateType(event.target.value as ResumeBuilderTemplateType)}
          >
            {TEMPLATE_TYPES.map((type) => (
              <option key={type} value={type}>
                {t(TEMPLATE_LABEL_KEY[type])}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
          disabled={creating}
          onClick={() => void onCreate()}
        >
          {creating ? t('common.processing') : t('resumeBuilder.list.createNew')}
        </button>
      </div>

      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
      {!loading && items.length === 0 && !error ? (
        <div className="mt-6"><EmptyState title={t('resumeBuilder.list.empty')} /></div>
      ) : null}

      {items.length > 0 ? (
        <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
              <tr>
                <th className="px-4 py-3">{t('resumeBuilder.list.columnTitle')}</th>
                <th className="px-4 py-3">{t('resumeBuilder.list.columnTargetRole')}</th>
                <th className="px-4 py-3">{t('resumeBuilder.list.columnUpdated')}</th>
                <th className="px-4 py-3">{t('resumes.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((draft) => (
                <tr key={draft.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-3">
                    <Link className="text-slate-800 underline" to={`/resumes/builder/${draft.id}`}>
                      {draft.title}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{draft.targetRole || '—'}</td>
                  <td className="px-4 py-3 text-slate-600">{new Date(draft.updatedAt).toLocaleString()}</td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-3">
                      <button type="button" className="text-slate-700 underline" onClick={() => void onDuplicate(draft.id)}>
                        {t('resumeBuilder.list.duplicate')}
                      </button>
                      <button type="button" className="text-red-700 underline" onClick={() => void onDelete(draft.id)}>
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
