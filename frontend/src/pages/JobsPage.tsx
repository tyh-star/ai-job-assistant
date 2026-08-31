import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  bulkDeleteJobs,
  createJob,
  deleteJob,
  importSearchResults,
  fetchNextSearchPage,
  listJobs,
  searchJobs,
  updateJob,
} from '../api/jobs'
import type {
  ImportSearchResultsResponse,
  JobForm,
  JobResponse,
  JobSearchResult,
} from '../api/types'
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

const POSTED_WITHIN_DAYS = [1, 3, 7, 14, 30] as const
const ADZUNA_ATTRIBUTION_URL = 'https://www.adzuna.com'

type SourceFilter = 'ALL' | 'MANUAL' | 'GREENHOUSE' | 'ADZUNA' | 'JSEARCH'

export function JobsPage() {
  const { t } = useTranslation()
  const [items, setItems] = useState<JobResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [form, setForm] = useState<JobForm>(emptyForm)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('ALL')
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [keywords, setKeywords] = useState('')
  const [searchLocation, setSearchLocation] = useState('')
  const [postedWithinDays, setPostedWithinDays] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchSessionId, setSearchSessionId] = useState<string | null>(null)
  const [searchProvider, setSearchProvider] = useState<string | null>(null)
  const [loadedPages, setLoadedPages] = useState<Record<number, JobSearchResult[]>>({})
  const [currentPage, setCurrentPage] = useState(1)
  const [pageHasMore, setPageHasMore] = useState(false)
  const [limitReached, setLimitReached] = useState(false)
  const [loadingNext, setLoadingNext] = useState(false)
  const loadingNextRef = useRef(false)
  const resultsTopRef = useRef<HTMLElement | null>(null)
  const resultsListRef = useRef<HTMLUListElement | null>(null)
  const [selectedSearchIds, setSelectedSearchIds] = useState<string[]>([])
  const [importing, setImporting] = useState(false)
  const [importSummary, setImportSummary] = useState<ImportSearchResultsResponse | null>(null)
  const searchResults = loadedPages[currentPage] ?? []
  const searchResultsVisible = Object.keys(loadedPages).length > 0
  const nextPageLoaded = Boolean(loadedPages[currentPage + 1])
  const canGoNext = nextPageLoaded || (pageHasMore && !limitReached)

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

  async function onSearch(event: FormEvent) {
    event.preventDefault()
    if (!keywords.trim()) {
      setError(t('jobs.keywordsRequired'))
      return
    }
    setSearching(true)
    setError('')
    setImportSummary(null)
    setSearchSessionId(null)
    setSearchProvider(null)
    setLoadedPages({})
    setCurrentPage(1)
    setPageHasMore(false)
    setLimitReached(false)
    setSelectedSearchIds([])
    try {
      const days = postedWithinDays ? Number(postedWithinDays) : undefined
      const { data } = await searchJobs({
        keywords: keywords.trim(),
        location: searchLocation.trim() || undefined,
        postedWithinDays: days,
      })
      setSearchSessionId(data.searchSessionId)
      setSearchProvider(data.provider)
      setLoadedPages({ 1: data.results ?? [] })
      setCurrentPage(1)
      setPageHasMore(Boolean(data.hasMore))
      setLimitReached(Boolean(data.limitReached))
      setSelectedSearchIds([])
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSearching(false)
    }
  }

  function toggleSearchSelect(externalJobId: string, checked: boolean) {
    if (checked) {
      setSelectedSearchIds((current) => current.includes(externalJobId) ? current : [...current, externalJobId])
    } else {
      setSelectedSearchIds((current) => current.filter((item) => item !== externalJobId))
    }
  }

  async function onImport(ids: string[]) {
    if (!searchSessionId || ids.length === 0) {
      return
    }
    setImporting(true)
    setError('')
    try {
      const { data } = await importSearchResults({
        searchSessionId,
        selectedExternalJobIds: ids,
      })
      setImportSummary(data)
      const savedIds = new Set([
        ...data.imported.map((item) => item.externalJobId),
        ...data.existing.map((item) => item.externalJobId),
      ])
      setLoadedPages((pages) => {
        const next: Record<number, JobSearchResult[]> = {}
        for (const [page, results] of Object.entries(pages)) {
          next[Number(page)] = results.map((result) => (
            result.externalJobId && savedIds.has(result.externalJobId)
              ? { ...result, alreadySaved: true }
              : result
          ))
        }
        return next
      })
      await load()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setImporting(false)
    }
  }

  function isIndependentScrollContainer(element: HTMLElement | null): element is HTMLElement {
    if (!element) {
      return false
    }
    const overflowY = window.getComputedStyle(element).overflowY
    return (overflowY === 'auto' || overflowY === 'scroll') && element.scrollHeight > element.clientHeight
  }

  function scrollResultsToTop() {
    if (isIndependentScrollContainer(resultsListRef.current)) {
      resultsListRef.current.scrollTop = 0
      return
    }
    resultsTopRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  function onPreviousPage() {
    if (currentPage <= 1) {
      return
    }
    setCurrentPage(currentPage - 1)
    scrollResultsToTop()
  }

  async function onNextPage() {
    if (!searchSessionId || loadingNextRef.current) {
      return
    }
    const nextPage = currentPage + 1
    if (loadedPages[nextPage]) {
      setCurrentPage(nextPage)
      scrollResultsToTop()
      return
    }
    if (!pageHasMore || limitReached) {
      return
    }
    loadingNextRef.current = true
    setLoadingNext(true)
    setError('')
    try {
      const { data } = await fetchNextSearchPage(searchSessionId, currentPage)
      setLoadedPages((pages) => ({ ...pages, [data.page]: data.results ?? [] }))
      setCurrentPage(data.page)
      setPageHasMore(Boolean(data.hasMore) && !data.limitReached)
      setLimitReached(Boolean(data.limitReached))
      scrollResultsToTop()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      loadingNextRef.current = false
      setLoadingNext(false)
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

        <form className="rounded-xl border border-slate-200 bg-white p-5" onSubmit={(event) => void onSearch(event)}>
          <h2 className="text-lg font-medium">{t('jobs.searchTitle')}</h2>
          <label className="mt-4 block text-sm">
            <span>{t('jobs.keywords')}</span>
            <input
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={keywords}
              onChange={(event) => setKeywords(event.target.value)}
            />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.location')}</span>
            <input
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={searchLocation}
              onChange={(event) => setSearchLocation(event.target.value)}
            />
          </label>
          <label className="mt-3 block text-sm">
            <span>{t('jobs.postedWithin')}</span>
            <select
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
              value={postedWithinDays}
              onChange={(event) => setPostedWithinDays(event.target.value)}
            >
              <option value="">{t('jobs.postedAny')}</option>
              {POSTED_WITHIN_DAYS.map((days) => (
                <option key={days} value={days}>{t('jobs.postedDays', { count: days })}</option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            disabled={searching || saving || importing}
            className="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
          >
            {searching ? t('jobs.searching') : t('jobs.search')}
          </button>
        </form>
      </div>

      {searchResultsVisible ? (
        <section
          ref={resultsTopRef}
          className="mt-6 scroll-mt-20 rounded-xl border border-slate-200 bg-white p-5"
        >
          {searchProvider === 'ADZUNA' ? (
            <p className="text-sm text-slate-700">
              <a
                href={ADZUNA_ATTRIBUTION_URL}
                target="_blank"
                rel="noreferrer"
                className="font-medium underline"
              >
                {t('jobs.attribution')}
              </a>
            </p>
          ) : null}
          <p className="mt-3 text-sm text-slate-600">{t('jobs.quotaHint')}</p>
          {limitReached ? (
            <p className="mt-2 text-sm text-amber-800">{t('jobs.limitReached')}</p>
          ) : null}
          {searchResults.length === 0 ? (
            <p className="mt-3 text-sm text-slate-600">{t('jobs.searchEmpty')}</p>
          ) : (
            <>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  disabled={importing || selectedSearchIds.length === 0}
                  className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
                  onClick={() => void onImport(selectedSearchIds)}
                >
                  {t('jobs.importSelected')}
                </button>
                <button
                  type="button"
                  disabled={importing}
                  className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
                  onClick={() => void onImport(
                    searchResults
                      .map((result) => result.externalJobId)
                      .filter((id): id is string => Boolean(id)),
                  )}
                >
                  {t('jobs.importPage')}
                </button>
              </div>
              <ul ref={resultsListRef} className="mt-4 space-y-4">
                {searchResults.map((result) => {
                  const resultId = result.externalJobId ?? result.sourceUrl
                  return (
                    <li key={`${result.source}-${resultId}`}
                      className="rounded-lg border border-slate-200 p-4">
                      <label className="flex items-start gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={Boolean(result.externalJobId && selectedSearchIds.includes(result.externalJobId))}
                          disabled={!result.externalJobId}
                          onChange={(event) => {
                            if (result.externalJobId) {
                              toggleSearchSelect(result.externalJobId, event.target.checked)
                            }
                          }}
                        />
                        <span className="font-medium text-slate-900">{result.title}</span>
                      </label>
                      <p className="mt-1 text-sm text-slate-700">{result.company}</p>
                      <p className="mt-1 text-sm text-slate-600">
                        {[result.location, result.postedAt, result.publisher].filter(Boolean).join(' · ')}
                      </p>
                      <p className="mt-2 text-xs uppercase tracking-wide text-slate-500">{result.source}</p>
                      <p className="mt-2 text-sm text-slate-700 whitespace-pre-wrap">{result.descriptionSnippet}</p>
                      <p className="mt-1 text-xs text-slate-500">
                        {result.fullDescriptionAvailable
                          ? t('jobs.fullDescriptionReady')
                          : t('jobs.fullDescriptionUnavailable')}
                      </p>
                      {result.alreadySaved ? (
                        <p className="mt-1 text-xs text-slate-500">{t('jobs.alreadySavedBadge')}</p>
                      ) : null}
                      <div className="mt-3 flex flex-wrap gap-3">
                        <a
                          href={result.sourceUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="underline text-sm"
                        >
                          {t('jobs.openOriginal')}
                        </a>
                      </div>
                    </li>
                  )
                })}
              </ul>
              <div className="mt-4 flex flex-wrap items-center gap-3">
                <button
                  type="button"
                  disabled={currentPage <= 1 || loadingNext}
                  className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
                  onClick={onPreviousPage}
                >
                  {t('jobs.previousPage')}
                </button>
                <span className="text-sm text-slate-700">{t('jobs.pageLabel', { page: currentPage })}</span>
                <button
                  type="button"
                  aria-label={t('jobs.nextPage')}
                  disabled={!canGoNext || loadingNext}
                  className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
                  onClick={() => void onNextPage()}
                >
                  {loadingNext ? t('jobs.searching') : t('jobs.nextPage')}
                </button>
              </div>
            </>
          )}
          {importSummary ? (
            <div className="mt-5 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm">
              <h3 className="font-medium text-slate-900">{t('jobs.importSummaryTitle')}</h3>
              <p className="mt-2">{t('jobs.importedCount', { count: importSummary.imported.length })}</p>
              <p>{t('jobs.existingCount', { count: importSummary.existing.length })}</p>
              <p>{t('jobs.failedCount', { count: importSummary.failed.length })}</p>
              {[...importSummary.imported, ...importSummary.existing].map((item) => (
                <div key={`${item.externalJobId}-${item.jobId}`} className="mt-3 flex flex-wrap gap-3">
                  <span className="text-slate-700">{item.externalJobId}</span>
                  <a className="underline" href={`/tailor?jobId=${item.jobId}`}>{t('jobs.analyzeMatch')}</a>
                  <a className="underline" href={`/tailor?jobId=${item.jobId}`}>{t('jobs.generateSuggestions')}</a>
                </div>
              ))}
              {importSummary.failed.map((item) => (
                <p key={item.externalJobId} className="mt-2 text-red-700">
                  {item.externalJobId}: {t(`jobs.importReason.${item.reason}`, { defaultValue: item.reason })}
                </p>
              ))}
            </div>
          ) : null}
        </section>
      ) : null}

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
