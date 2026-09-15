import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
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
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { Alert } from '../components/Alert'
import { ApplicationStatsPanel } from '../components/ApplicationStatsPanel'
import { EmptyState } from '../components/EmptyState'
import { JobCatalogSearch } from '../components/JobCatalogSearch'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { LoadingSkeleton } from '../components/ui/LoadingSkeleton'
import {
  jobsTabFromLocation,
  jobsTabReturnPath,
  writeJobsTabParam,
  type JobsTab,
} from '../jobs/jobsTab'

const emptyForm: JobForm = {
  company: '',
  title: '',
  location: '',
  jobUrl: '',
  description: '',
}

type SourceFilter = 'ALL' | 'MANUAL' | 'GREENHOUSE' | 'ADZUNA' | 'JSEARCH' | 'JOB_CATALOG'

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
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const requestedTab = jobsTabFromLocation(searchParams, location.hash)
  const activeTab: JobsTab = !isAuthenticated && requestedTab !== 'SEARCH' ? 'SEARCH' : requestedTab
  const [items, setItems] = useState<JobResponse[]>([])
  const [privateLoaded, setPrivateLoaded] = useState(false)
  const [loading, setLoading] = useState(false)
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
      setPrivateLoaded(true)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // Personal jobs (favorites/applied, counts, bulk actions) are account data.
    // Public catalog search must never wait on GET /jobs — including for tab counts.
    if (!isAuthenticated || activeTab === 'SEARCH' || privateLoaded) {
      return
    }
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated, activeTab, privateLoaded])

  useEffect(() => {
    if (!isAuthenticated) {
      if (requestedTab !== 'SEARCH') {
        navigate(loginPathForAnonymousFeature(jobsTabReturnPath(requestedTab)), { replace: true })
      }
      return
    }
    if (location.hash !== '#applied' && location.hash !== '#saved' && location.hash !== '#favorites') {
      return
    }
    const next = writeJobsTabParam(searchParams, requestedTab)
    const search = next.toString()
    navigate({ pathname: '/jobs', search: search ? `?${search}` : '', hash: '' }, { replace: true })
  }, [isAuthenticated, requestedTab, location.hash, navigate, searchParams])

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

  function commitTab(tab: JobsTab) {
    const next = writeJobsTabParam(searchParams, tab)
    const search = next.toString()
    navigate({ pathname: '/jobs', search: search ? `?${search}` : '', hash: '' }, { replace: true })
  }

  function onTabClick(tab: JobsTab) {
    if (tab !== 'SEARCH' && !isAuthenticated) {
      navigate(loginPathForAnonymousFeature(jobsTabReturnPath(tab)))
      return
    }
    commitTab(tab)
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
      commitTab('APPLIED')
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
      commitTab('SAVED')
    } catch {
      setAppliedErrorId(id)
    } finally {
      setAppliedBusyId(null)
    }
  }

  function tabClass(selected: boolean) {
    return selected
      ? 'min-h-11 min-w-0 rounded-md bg-accent-soft px-4 py-2 text-sm font-medium text-brand'
      : 'min-h-11 min-w-0 rounded-md px-4 py-2 text-sm text-muted hover:bg-slate-100 hover:text-ink'
  }

  function renderJobCards(list: JobResponse[], mode: 'SAVED' | 'APPLIED') {
    return (
      <ul className="mt-6 space-y-3">
        {list.map((job) => (
          <li key={job.id}>
            <Card className="flex flex-col gap-3 sm:flex-row sm:items-start">
              <div className="flex min-w-0 flex-1 gap-3">
                {job.source === 'GREENHOUSE' ? (
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={selectedIds.includes(job.id)}
                    onChange={(event) => toggleSelect(job.id, event.target.checked)}
                  />
                ) : null}
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-base font-semibold text-ink">{job.title}</p>
                    {mode === 'APPLIED' ? (
                      <Badge tone="success" icon={<CheckIcon />}>{t('jobs.appliedBadge')}</Badge>
                    ) : null}
                  </div>
                  <p className="mt-1 text-sm text-ink">{job.company}</p>
                  <p className="mt-1 text-sm text-muted">{job.location}</p>
                  <p className="mt-1 text-sm text-muted">
                    {t(`status.jobSource.${job.source}`, { defaultValue: job.source })}
                  </p>
                  <p className="mt-1 text-sm text-muted">
                    {t(`status.job.${job.status}`, { defaultValue: job.status })}
                  </p>
                  {mode === 'APPLIED' && job.appliedAt ? (
                    <p className="mt-1 text-sm text-muted">
                      {t('jobs.appliedAtLabel')}: {job.appliedAt.slice(0, 10)}
                    </p>
                  ) : null}
                </div>
              </div>
              <div className="flex min-w-0 flex-col items-stretch gap-2 sm:items-end">
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" size="sm" onClick={() => startEdit(job)}>
                    {t('jobs.edit')}
                  </Button>
                  <Button variant="danger" size="sm" onClick={() => void onDelete(job.id)}>
                    {t('jobs.delete')}
                  </Button>
                  {mode === 'SAVED' ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={appliedBusyId === job.id}
                      onClick={() => void onMarkApplied(job.id)}
                    >
                      {t('jobs.confirmApplied')}
                    </Button>
                  ) : (
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={appliedBusyId === job.id}
                      onClick={() => void onClearApplied(job.id)}
                    >
                      {t('jobs.revokeApplied')}
                    </Button>
                  )}
                </div>
                {appliedErrorId === job.id ? (
                  <p role="alert" className="text-sm text-danger">{t('jobs.appliedFailed')}</p>
                ) : null}
              </div>
            </Card>
          </li>
        ))}
      </ul>
    )
  }

  function renderSourceFilterAndBulkActions() {
    return (
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <label className="text-sm">
          <span className="mr-2">{t('jobs.source')}</span>
          <select
            className="ui-input mt-0 w-auto min-w-40"
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
              className="rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger disabled:opacity-60"
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
      <h1 className="page-title">{t('jobs.title')}</h1>

      <div role="tablist" aria-label={t('jobs.tabsLabel')} className="mt-6 flex min-w-0 flex-wrap gap-1 rounded-lg border border-line bg-card p-1">
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
          {isAuthenticated
            ? (privateLoaded ? t('jobs.tabSaved', { count: savedCount }) : t('jobs.tabSavedLocked'))
            : (
            <span className="inline-flex min-w-0 flex-wrap items-center gap-1">
              <span>{t('jobs.tabSavedLocked')}</span>
              <span aria-hidden="true">🔒</span>
              <span className="sr-only">{t('jobs.loginToView')}</span>
            </span>
          )}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'APPLIED'}
          className={tabClass(activeTab === 'APPLIED')}
          onClick={() => onTabClick('APPLIED')}
        >
          {isAuthenticated
            ? (privateLoaded ? t('jobs.tabApplied', { count: appliedCount }) : t('jobs.tabAppliedLocked'))
            : (
            <span className="inline-flex min-w-0 flex-wrap items-center gap-1">
              <span>{t('jobs.tabAppliedLocked')}</span>
              <span aria-hidden="true">🔒</span>
              <span className="sr-only">{t('jobs.loginToView')}</span>
            </span>
          )}
        </button>
      </div>

      {isAuthenticated && loading && activeTab !== 'SEARCH' ? (
        <div className="mt-6"><LoadingSkeleton rows={3} label={t('common.loading')} /></div>
      ) : null}

      {/* Always mounted, just hidden -- switching tabs must never lose an in-progress
          search (typed keywords, results, city selection) the way unmounting would.
          `hidden` (not a CSS class) so it is truly removed from the accessibility tree
          in every environment, matching how a real browser would hide it. */}
      <div hidden={activeTab !== 'SEARCH'}>
        <JobCatalogSearch />
      </div>

      {activeTab === 'SAVED' && isAuthenticated ? (
        <>
          {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
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
                <h2 className="section-title">
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

          {savedJobs.length > 0 ? renderJobCards(savedJobs, 'SAVED') : null}
        </>
      ) : null}

      {activeTab === 'APPLIED' && isAuthenticated ? (
        <>
          {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}
          <ApplicationStatsPanel />
          {renderSourceFilterAndBulkActions()}

          {!loading && appliedJobs.length === 0 && !error ? (
            <div className="mt-6"><EmptyState title={t('jobs.emptyApplied')} /></div>
          ) : null}

          {appliedJobs.length > 0 ? renderJobCards(appliedJobs, 'APPLIED') : null}
        </>
      ) : null}
    </div>
  )
}
