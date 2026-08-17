import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  bulkDeleteJobs,
  createJob,
  deleteJob,
  importGreenhouse,
  listJobs,
  updateJob,
} from '../api/jobs'
import type { JobForm, JobResponse } from '../api/types'
import { Alert } from '../components/Alert'
import { EmptyState } from '../components/EmptyState'
import { Spinner } from '../components/Spinner'

const emptyForm: JobForm = {
  company: '',
  title: '',
  location: '',
  jobUrl: '',
  description: '',
}

type SourceFilter = 'ALL' | 'MANUAL' | 'GREENHOUSE'

export function JobsPage() {
  const { t } = useTranslation()
  const [items, setItems] = useState<JobResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [form, setForm] = useState<JobForm>(emptyForm)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [boardToken, setBoardToken] = useState('')
  const [importMessage, setImportMessage] = useState('')
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('ALL')
  const [selectedIds, setSelectedIds] = useState<number[]>([])

  async function load() {
    setLoading(true)
    setError('')
    try {
      const { data } = await listJobs()
      setItems(data)
      setSelectedIds([])
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const filtered = useMemo(() => {
    if (sourceFilter === 'ALL') {
      return items
    }
    return items.filter((job) => job.source === sourceFilter)
  }, [items, sourceFilter])

  const greenhouseFiltered = filtered.filter((job) => job.source === 'GREENHOUSE')
  const allGreenhouseSelected =
    greenhouseFiltered.length > 0
    && greenhouseFiltered.every((job) => selectedIds.includes(job.id))

  function startEdit(job: JobResponse) {
    setEditingId(job.id)
    setForm({
      company: job.company ?? '',
      title: job.title ?? '',
      location: job.location ?? '',
      jobUrl: job.jobUrl ?? '',
      description: job.description ?? '',
    })
  }

  function validate(): string | null {
    if (!form.company.trim()) {
      return t('jobs.companyRequired')
    }
    if (!form.title.trim()) {
      return t('jobs.titleRequired')
    }
    if (!form.description.trim()) {
      return t('jobs.descriptionRequired')
    }
    return null
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const invalid = validate()
    if (invalid) {
      setError(invalid)
      return
    }
    setSaving(true)
    setError('')
    try {
      if (editingId === null) {
        await createJob(form)
      } else {
        await updateJob(editingId, form)
      }
      setForm(emptyForm)
      setEditingId(null)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  async function onDelete(id: number) {
    if (!window.confirm(t('jobs.confirmDelete'))) {
      return
    }
    setError('')
    try {
      await deleteJob(id)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    }
  }

  async function onImport() {
    if (!boardToken.trim()) {
      setError(t('jobs.boardTokenRequired'))
      return
    }
    setSaving(true)
    setError('')
    setImportMessage('')
    try {
      const { data } = await importGreenhouse(boardToken.trim())
      setImportMessage(t('jobs.importResult', {
        imported: data.importedCount,
        duplicates: data.skippedDuplicateCount,
      }))
      setBoardToken('')
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  function toggleSelect(id: number, checked: boolean) {
    if (checked) {
      setSelectedIds((current) => current.includes(id) ? current : [...current, id])
    } else {
      setSelectedIds((current) => current.filter((item) => item !== id))
    }
  }

  function toggleSelectAllFiltered(checked: boolean) {
    const ids = greenhouseFiltered.map((job) => job.id)
    if (checked) {
      setSelectedIds((current) => Array.from(new Set([...current, ...ids])))
    } else {
      setSelectedIds((current) => current.filter((id) => !ids.includes(id)))
    }
  }

  async function onDeleteSelected() {
    if (selectedIds.length === 0) {
      return
    }
    if (!window.confirm(t('jobs.deleteSelectedConfirm', { count: selectedIds.length }))) {
      return
    }
    setSaving(true)
    setError('')
    try {
      await bulkDeleteJobs(selectedIds)
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-900">{t('jobs.title')}</h1>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
      {importMessage ? (
        <p className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
          {importMessage}
        </p>
      ) : null}

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <form className="rounded-xl border border-slate-200 bg-white p-5" onSubmit={onSubmit}>
          <h2 className="text-lg font-medium">
            {editingId === null ? t('jobs.create') : t('jobs.edit')}
          </h2>
          <label className="mt-4 block text-sm">
            <span>{t('jobs.company')}</span>
            <input className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.jobTitle')}</span>
            <input className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.location')}</span>
            <input className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={form.location} onChange={(event) => setForm({ ...form, location: event.target.value })} />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.jobUrl')}</span>
            <input className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={form.jobUrl} onChange={(event) => setForm({ ...form, jobUrl: event.target.value })} />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.description')}</span>
            <textarea className="mt-1 h-32 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
          </label>
          <div className="mt-4 flex gap-2">
            <button type="submit" disabled={saving}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60">
              {saving ? t('common.saving') : t('jobs.save')}
            </button>
            {editingId !== null ? (
              <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm"
                onClick={() => { setEditingId(null); setForm(emptyForm) }}>
                {t('jobs.cancel')}
              </button>
            ) : null}
          </div>
        </form>

        <div className="rounded-xl border border-slate-200 bg-white p-5">
          <h2 className="text-lg font-medium">{t('jobs.greenhouse')}</h2>
          <label className="mt-4 block text-sm">
            <span>{t('jobs.boardToken')}</span>
            <input className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={boardToken} onChange={(event) => setBoardToken(event.target.value)} />
          </label>
          <button type="button" disabled={saving}
            className="mt-4 rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
            onClick={() => void onImport()}>
            {t('jobs.import')}
          </button>
        </div>
      </div>

      <div className="mt-6 flex flex-wrap items-center gap-3">
        <label className="text-sm">
          <span className="mr-2">{t('jobs.source')}</span>
          <select
            className="rounded-lg border border-slate-300 px-3 py-2"
            value={sourceFilter}
            onChange={(event) => {
              setSourceFilter(event.target.value as SourceFilter)
              setSelectedIds([])
            }}
          >
            <option value="ALL">{t('jobs.filterAll')}</option>
            <option value="MANUAL">{t('jobs.filterManual')}</option>
            <option value="GREENHOUSE">{t('jobs.filterGreenhouse')}</option>
          </select>
        </label>
        {greenhouseFiltered.length > 0 ? (
          <>
            <label className="inline-flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={allGreenhouseSelected}
                onChange={(event) => toggleSelectAllFiltered(event.target.checked)}
              />
              {t('jobs.selectAllFiltered')}
            </label>
            <button
              type="button"
              disabled={saving || selectedIds.length === 0}
              className="rounded-lg border border-red-300 px-3 py-2 text-sm text-red-800 disabled:opacity-60"
              onClick={() => void onDeleteSelected()}
            >
              {t('jobs.deleteSelected')}
            </button>
          </>
        ) : null}
      </div>

      {!loading && filtered.length === 0 && !error ? (
        <div className="mt-6"><EmptyState title={t('jobs.empty')} /></div>
      ) : null}

      {filtered.length > 0 ? (
        <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
              <tr>
                <th className="px-4 py-3 w-10"></th>
                <th className="px-4 py-3">{t('jobs.company')}</th>
                <th className="px-4 py-3">{t('jobs.jobTitle')}</th>
                <th className="px-4 py-3">{t('jobs.location')}</th>
                <th className="px-4 py-3">{t('jobs.source')}</th>
                <th className="px-4 py-3">{t('jobs.status')}</th>
                <th className="px-4 py-3">{t('resumes.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((job) => (
                <tr key={job.id} className="border-b border-slate-100 last:border-0 align-top">
                  <td className="px-4 py-3">
                    {job.source === 'GREENHOUSE' ? (
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(job.id)}
                        onChange={(event) => toggleSelect(job.id, event.target.checked)}
                      />
                    ) : null}
                  </td>
                  <td className="px-4 py-3">{job.company}</td>
                  <td className="px-4 py-3">{job.title}</td>
                  <td className="px-4 py-3">{job.location}</td>
                  <td className="px-4 py-3">
                    {t(`status.jobSource.${job.source}`, { defaultValue: job.source })}
                  </td>
                  <td className="px-4 py-3">
                    {t(`status.job.${job.status}`, { defaultValue: job.status })}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-3">
                      <button type="button" className="underline" onClick={() => startEdit(job)}>
                        {t('jobs.edit')}
                      </button>
                      <button type="button" className="text-red-700 underline" onClick={() => void onDelete(job.id)}>
                        {t('jobs.delete')}
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
