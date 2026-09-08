import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { apiErrorKey } from '../api/client'
import {
  bulkDeleteJobs,
  clearJobApplied,
  createJob,
  deleteJob,
  listJobs,
  markJobApplied,
  updateJob,
} from '../api/jobs'
import type { JobForm, JobResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { loginPathWithReturnTo } from '../auth/returnTo'
import { Alert } from '../components/Alert'
import { ApplicationStatsPanel } from '../components/ApplicationStatsPanel'
import { EmptyState } from '../components/EmptyState'
import { JobCatalogSearch } from '../components/JobCatalogSearch'
import { Spinner } from '../components/Spinner'

const emptyForm: JobForm = {
  company: '',
  title: '',
  location: '',
  jobUrl: '',
  description: '',
}

type SourceFilter = 'ALL' | 'MANUAL' | 'GREENHOUSE' | 'ADZUNA' | 'JSEARCH' | 'JOB_CATALOG'
type JobsTab = 'SEARCH' | 'SAVED' | 'APPLIED'

// Same small check icon JobCatalogSearch's own "已投递" badge uses -- kept visually
// identical here rather than shared, since the two components are otherwise decoupled.
function CheckIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M4 10.5 8 14l8-8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function JobsPage() {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()

  const [activeTab, setActiveTab] = useState<JobsTab>('SEARCH')
  const [items, setItems] = useState<JobResponse[]>([])
  const [loading, setLoading] = useState(isAuthenticated)
  const [error, setError] = useState('')
  const [form, setForm] = useState<JobForm>(emptyForm)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [showManualForm, setShowManualForm] = useState(false)
  const [saving, setSaving] = useState(false)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('ALL')
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [appliedBusyId, setAppliedBusyId] = useState<number | null>(null)
  const [appliedErrorId, setAppliedErrorId] = useState<number | null>(null)

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
    // The personal jobs list (manual create/edit/delete, saved/applied tabs, the
    // Greenhouse-import selection table) is account data -- never fetched for a
    // visitor who is not logged in. The catalog search below still renders for
    // everyone, and clicking either personal-jobs tab redirects to /login instead of
    // ever reaching this effect's data.
    if (isAuthenticated) {
      void load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated])

  const filtered = useMemo(() => {
    if (sourceFilter === 'ALL') {
      return items
    }
    return items.filter((job) => job.source === sourceFilter)
  }, [items, sourceFilter])

  // Tab counts are over ALL of this user's jobs, unaffected by the source filter below
  // -- so the number next to "已保存/已投递" never shifts just because the filter
  // dropdown changed.
  const savedCount = useMemo(() => items.filter((job) => !job.applied).length, [items])
  const appliedCount = useMemo(() => items.filter((job) => job.applied).length, [items])

  const savedJobs = useMemo(() => filtered.filter((job) => !job.applied), [filtered])
  const appliedJobs = useMemo(() => filtered.filter((job) => job.applied), [filtered])
  // Bulk-select is scoped to whichever tab is actually visible -- selecting "all" in
  // 已保存 must never silently include a row that only exists in 已投递.
  const visibleJobs = activeTab === 'APPLIED' ? appliedJobs : savedJobs
  const greenhouseVisible = visibleJobs.filter((job) => job.source === 'GREENHOUSE')
  const allGreenhouseSelected =
    greenhouseVisible.length > 0
    && greenhouseVisible.every((job) => selectedIds.includes(job.id))

  function onTabClick(tab: JobsTab) {
    if (tab !== 'SEARCH' && !isAuthenticated) {
      navigate(loginPathWithReturnTo('/jobs'))
      return
    }
    setActiveTab(tab)
  }

  function startEdit(job: JobResponse) {
    setEditingId(job.id)
    setForm({
      company: job.company ?? '',
      title: job.title ?? '',
      location: job.location ?? '',
      jobUrl: job.jobUrl ?? '',
      description: job.description ?? '',
    })
    setShowManualForm(true)
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
      setShowManualForm(false)
      // A manually added job always starts unapplied (see JobMapper#insert) -- it
      // belongs in 已保存职位, which is exactly where this reload puts it.
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

  function toggleSelect(id: number, checked: boolean) {
    if (checked) {
      setSelectedIds((current) => current.includes(id) ? current : [...current, id])
    } else {
      setSelectedIds((current) => current.filter((item) => item !== id))
    }
  }

  function toggleSelectAllFiltered(checked: boolean) {
    const ids = greenhouseVisible.map((job) => job.id)
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

  async function onMarkApplied(id: number) {
    setAppliedBusyId(id)
    setAppliedErrorId(null)
    try {
      await markJobApplied(id)
      await load()
      // Moves from 已保存职位 to 已投递职位 -- switching tabs is what makes that move
      // visible rather than the row just silently changing state off-screen.
      setActiveTab('APPLIED')
    } catch {
      setAppliedErrorId(id)
    } finally {
      setAppliedBusyId(null)
    }
  }

  async function onClearApplied(id: number) {
    setAppliedBusyId(id)
    setAppliedErrorId(null)
    try {
      await clearJobApplied(id)
      await load()
      // Moves back from 已投递职位 to 已保存职位.
      setActiveTab('SAVED')
    } catch {
      setAppliedErrorId(id)
    } finally {
      setAppliedBusyId(null)
    }
  }

  function tabClass(selected: boolean) {
    return selected
      ? 'rounded-lg bg-slate-900 px-4 py-2 text-sm text-white'
      : 'rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700'
  }

  function renderJobsTable(list: JobResponse[], mode: 'SAVED' | 'APPLIED') {
    return (
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
          {list.map((job) => (
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
              <td className="px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span>{job.title}</span>
                  {mode === 'APPLIED' ? (
                    <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-0.5 text-xs text-emerald-800">
                      <CheckIcon />
                      {t('jobs.appliedBadge')}
                    </span>
                  ) : null}
                </div>
                {mode === 'APPLIED' && job.appliedAt ? (
                  <p className="mt-1 text-xs text-slate-500">
                    {t('jobs.appliedAtLabel')}: {job.appliedAt.slice(0, 10)}
                  </p>
                ) : null}
              </td>
              <td className="px-4 py-3">{job.location}</td>
              <td className="px-4 py-3">
                {t(`status.jobSource.${job.source}`, { defaultValue: job.source })}
              </td>
              <td className="px-4 py-3">
                {t(`status.job.${job.status}`, { defaultValue: job.status })}
              </td>
              <td className="px-4 py-3">
                <div className="flex flex-col items-start gap-2">
                  <div className="flex flex-wrap gap-3">
                    <button type="button" className="underline" onClick={() => startEdit(job)}>
                      {t('jobs.edit')}
                    </button>
                    <button type="button" className="text-red-700 underline" onClick={() => void onDelete(job.id)}>
                      {t('jobs.delete')}
                    </button>
                    {mode === 'SAVED' ? (
                      <button
                        type="button"
                        disabled={appliedBusyId === job.id}
                        className="text-emerald-700 underline disabled:opacity-60"
                        onClick={() => void onMarkApplied(job.id)}
                      >
                        {t('jobs.confirmApplied')}
                      </button>
                    ) : (
                      <button
                        type="button"
                        disabled={appliedBusyId === job.id}
                        className="text-slate-500 underline disabled:opacity-60"
                        onClick={() => void onClearApplied(job.id)}
                      >
                        {t('jobs.revokeApplied')}
                      </button>
                    )}
                  </div>
                  {appliedErrorId === job.id ? (
                    <p role="alert" className="text-xs text-red-700">{t('jobs.appliedFailed')}</p>
                  ) : null}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    )
  }

  function renderSourceFilterAndBulkActions() {
    return (
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
            <option value="ADZUNA">{t('jobs.filterAdzuna')}</option>
            <option value="JSEARCH">{t('jobs.filterJsearch')}</option>
            <option value="JOB_CATALOG">{t('jobs.filterJobCatalog')}</option>
          </select>
        </label>
        {greenhouseVisible.length > 0 ? (
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
    )
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-900">{t('jobs.title')}</h1>

      <div role="tablist" aria-label={t('jobs.tabsLabel')} className="mt-4 flex flex-wrap gap-2">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'SEARCH'}
          className={tabClass(activeTab === 'SEARCH')}
          onClick={() => onTabClick('SEARCH')}
        >
          {t('jobs.tabSearch')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'SAVED'}
          className={tabClass(activeTab === 'SAVED')}
          onClick={() => onTabClick('SAVED')}
        >
          {t('jobs.tabSaved', { count: savedCount })}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'APPLIED'}
          className={tabClass(activeTab === 'APPLIED')}
          onClick={() => onTabClick('APPLIED')}
        >
          {t('jobs.tabApplied', { count: appliedCount })}
        </button>
      </div>

      {isAuthenticated && loading && activeTab !== 'SEARCH' ? (
        <div className="mt-6"><Spinner label={t('common.loading')} /></div>
      ) : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}

      {/* Always mounted, just hidden -- switching tabs must never lose an in-progress
          search (typed keywords, results, city selection) the way unmounting would.
          `hidden` (not a CSS class) so it is truly removed from the accessibility tree
          in every environment, matching how a real browser would hide it. */}
      <div hidden={activeTab !== 'SEARCH'}>
        <JobCatalogSearch
          onSaved={() => { void load(); setActiveTab('SAVED') }}
          onApplied={() => { void load(); setActiveTab('APPLIED') }}
        />
      </div>

      {activeTab !== 'SEARCH' && !isAuthenticated ? (
        <p className="mt-6 rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600">
          {t('jobs.loginToTrackPrompt')}
        </p>
      ) : null}

      {activeTab === 'SAVED' && isAuthenticated ? (
        <>
          <div className="mt-6">
            <button
              type="button"
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm"
              onClick={() => {
                if (showManualForm) {
                  setEditingId(null)
                  setForm(emptyForm)
                }
                setShowManualForm((current) => !current)
              }}
            >
              {showManualForm ? t('jobs.manualAddCancel') : t('jobs.manualAddToggle')}
            </button>
          </div>

          {showManualForm ? (
            <div className="mt-4">
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
            </div>
          ) : null}

          {renderSourceFilterAndBulkActions()}

          {!loading && savedJobs.length === 0 && !error ? (
            <div className="mt-6"><EmptyState title={t('jobs.emptySaved')} /></div>
          ) : null}

          {savedJobs.length > 0 ? (
            <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
              {renderJobsTable(savedJobs, 'SAVED')}
            </div>
          ) : null}
        </>
      ) : null}

      {activeTab === 'APPLIED' && isAuthenticated ? (
        <>
          <ApplicationStatsPanel />
          {renderSourceFilterAndBulkActions()}

          {!loading && appliedJobs.length === 0 && !error ? (
            <div className="mt-6"><EmptyState title={t('jobs.emptyApplied')} /></div>
          ) : null}

          {appliedJobs.length > 0 ? (
            <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white">
              {renderJobsTable(appliedJobs, 'APPLIED')}
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  )
}
