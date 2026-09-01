import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { saveJobCatalogJob, searchJobCatalog } from '../api/jobCatalog'
import type {
  CatalogEmploymentType,
  CatalogRemoteType,
  JobCatalogSummary,
} from '../api/types'

const POSTED_WITHIN_DAYS = [1, 3, 7, 14, 30] as const
const PAGE_SIZE = 20

const REMOTE_TYPES: CatalogRemoteType[] = ['REMOTE', 'HYBRID', 'ONSITE']
const EMPLOYMENT_TYPES: CatalogEmploymentType[] = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'INTERNSHIP',
  'TEMPORARY',
]

/** ISO-3166 alpha-2 codes the collector currently populates. */
const COUNTRIES = ['CA', 'US', 'GB', 'DE', 'FR', 'NL', 'IE', 'AU', 'IN', 'SG'] as const

const INPUT_CLASS = 'mt-1 w-full rounded-lg border border-slate-300 px-3 py-2'
const LABEL_CLASS = 'block text-sm'

/**
 * Search over jobs this deployment has already collected from public ATS boards.
 *
 * <p>Every request goes to `/api/v1/job-catalog/*`, which reads only the local
 * database. Saving a posting returns an id in the user's own jobs, so the existing
 * Match and Tailor flows continue to work unchanged.
 */
export function JobCatalogSearch({ onSaved }: { onSaved?: () => void } = {}) {
  const { t } = useTranslation()

  const [keywords, setKeywords] = useState('')
  const [location, setLocation] = useState('')
  const [country, setCountry] = useState('')
  const [remoteType, setRemoteType] = useState('')
  const [employmentType, setEmploymentType] = useState('')
  const [postedWithinDays, setPostedWithinDays] = useState('')
  const [sort, setSort] = useState('')

  const [results, setResults] = useState<JobCatalogSummary[]>([])
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [hasSearched, setHasSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const [savingId, setSavingId] = useState<number | null>(null)
  const [saveErrorId, setSaveErrorId] = useState<number | null>(null)

  const runSearch = useCallback(
    async (targetPage: number) => {
      setLoading(true)
      setErrorKey(null)
      try {
        const response = await searchJobCatalog({
          q: keywords,
          location,
          country,
          remoteType,
          employmentType,
          postedWithinDays: postedWithinDays ? Number(postedWithinDays) : undefined,
          page: targetPage,
          size: PAGE_SIZE,
          sort,
        })
        setResults(response.data.results)
        setPage(response.data.page)
        setTotalPages(response.data.totalPages)
        setTotalCount(response.data.totalCount)
      } catch (error) {
        // A local database problem is surfaced as an error; it never silently
        // falls back to an external job API.
        setResults([])
        setTotalPages(0)
        setTotalCount(0)
        setErrorKey(apiErrorKey(error))
      } finally {
        setLoading(false)
        setHasSearched(true)
      }
    },
    [keywords, location, country, remoteType, employmentType, postedWithinDays, sort],
  )

  useEffect(() => {
    void runSearch(1)
    // Loads the newest indexed jobs once on mount; later runs are user-driven.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function onSave(catalogJobId: number) {
    setSavingId(catalogJobId)
    setSaveErrorId(null)
    try {
      const response = await saveJobCatalogJob(catalogJobId)
      const personalJobId = response.data.jobId
      setResults((current) =>
        current.map((item) =>
          item.id === catalogJobId ? { ...item, saved: true, personalJobId } : item,
        ),
      )
      onSaved?.()
    } catch {
      setSaveErrorId(catalogJobId)
    } finally {
      setSavingId(null)
    }
  }

  return (
    <section className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium">{t('jobCatalog.title')}</h2>
      <p className="mt-1 text-sm text-slate-600">{t('jobCatalog.subtitle')}</p>

      <form
        className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3"
        onSubmit={(event) => {
          event.preventDefault()
          void runSearch(1)
        }}
      >
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.keywords')}</span>
          <input
            className={INPUT_CLASS}
            value={keywords}
            onChange={(event) => setKeywords(event.target.value)}
          />
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.location')}</span>
          <input
            className={INPUT_CLASS}
            value={location}
            onChange={(event) => setLocation(event.target.value)}
          />
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.country')}</span>
          <select
            className={INPUT_CLASS}
            value={country}
            onChange={(event) => setCountry(event.target.value)}
          >
            <option value="">{t('jobCatalog.anyCountry')}</option>
            {COUNTRIES.map((code) => (
              <option key={code} value={code}>{code}</option>
            ))}
          </select>
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.remoteType')}</span>
          <select
            className={INPUT_CLASS}
            value={remoteType}
            onChange={(event) => setRemoteType(event.target.value)}
          >
            <option value="">{t('jobCatalog.anyRemoteType')}</option>
            {REMOTE_TYPES.map((value) => (
              <option key={value} value={value}>{t(`jobCatalog.remote.${value}`)}</option>
            ))}
          </select>
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.employmentType')}</span>
          <select
            className={INPUT_CLASS}
            value={employmentType}
            onChange={(event) => setEmploymentType(event.target.value)}
          >
            <option value="">{t('jobCatalog.anyEmploymentType')}</option>
            {EMPLOYMENT_TYPES.map((value) => (
              <option key={value} value={value}>{t(`jobCatalog.employment.${value}`)}</option>
            ))}
          </select>
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.postedWithin')}</span>
          <select
            className={INPUT_CLASS}
            value={postedWithinDays}
            onChange={(event) => setPostedWithinDays(event.target.value)}
          >
            <option value="">{t('jobCatalog.postedAny')}</option>
            {POSTED_WITHIN_DAYS.map((days) => (
              <option key={days} value={days}>{t('jobCatalog.postedDays', { count: days })}</option>
            ))}
          </select>
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('jobCatalog.sort')}</span>
          <select
            className={INPUT_CLASS}
            value={sort}
            onChange={(event) => setSort(event.target.value)}
          >
            <option value="">{t('jobCatalog.sortRelevance')}</option>
            <option value="POSTED_AT">{t('jobCatalog.sortPostedAt')}</option>
          </select>
        </label>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={loading}
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
          >
            {loading ? t('jobCatalog.searching') : t('jobCatalog.search')}
          </button>
        </div>
      </form>

      {loading ? (
        <p className="mt-4 text-sm text-slate-600">{t('jobCatalog.loading')}</p>
      ) : null}

      {errorKey ? (
        <p role="alert" className="mt-4 text-sm text-red-700">
          {t('jobCatalog.error')}
        </p>
      ) : null}

      {!loading && !errorKey && hasSearched && results.length === 0 ? (
        <p className="mt-4 text-sm text-slate-600">{t('jobCatalog.empty')}</p>
      ) : null}

      {!errorKey && results.length > 0 ? (
        <>
          <p className="mt-4 text-sm text-slate-600">
            {t('jobCatalog.resultCount', { count: totalCount })}
          </p>
          <ul className="mt-3 space-y-4">
            {results.map((job) => (
              <li key={job.id} className="rounded-lg border border-slate-200 p-4">
                <div className="flex flex-wrap items-baseline gap-2">
                  <span className="font-medium text-slate-900">{job.title}</span>
                  {job.status === 'EXPIRED' ? (
                    <span className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">
                      {t('jobCatalog.expiredBadge')}
                    </span>
                  ) : null}
                  {job.saved ? (
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                      {t('jobCatalog.savedBadge')}
                    </span>
                  ) : null}
                </div>
                <p className="mt-1 text-sm text-slate-700">{job.companyName}</p>
                <p className="mt-1 text-sm text-slate-600">
                  {[
                    job.locationText,
                    job.countryCode,
                    t(`jobCatalog.remote.${job.remoteType}`),
                    t(`jobCatalog.employment.${job.employmentType}`),
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </p>
                <p className="mt-1 text-xs text-slate-500">
                  {[
                    job.source ? `${t('jobCatalog.source')}: ${job.source}` : null,
                    job.postedAt ? `${t('jobCatalog.postedAt')}: ${job.postedAt.slice(0, 10)}` : null,
                    job.firstSeenAt
                      ? `${t('jobCatalog.indexedAt')}: ${job.firstSeenAt.slice(0, 10)}`
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </p>
                {job.descriptionSnippet ? (
                  <p className="mt-2 text-sm text-slate-700">{job.descriptionSnippet}</p>
                ) : null}
                {saveErrorId === job.id ? (
                  <p role="alert" className="mt-2 text-sm text-red-700">
                    {t('jobCatalog.saveFailed')}
                  </p>
                ) : null}
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  {job.saved ? null : (
                    <button
                      type="button"
                      disabled={savingId === job.id}
                      className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
                      onClick={() => void onSave(job.id)}
                    >
                      {savingId === job.id ? t('jobCatalog.saving') : t('jobCatalog.save')}
                    </button>
                  )}
                  {job.saved && job.personalJobId ? (
                    <>
                      <a className="underline text-sm" href={`/tailor?jobId=${job.personalJobId}`}>
                        {t('jobCatalog.analyzeMatch')}
                      </a>
                      <a className="underline text-sm" href={`/tailor?jobId=${job.personalJobId}`}>
                        {t('jobCatalog.generateSuggestions')}
                      </a>
                    </>
                  ) : null}
                  {job.applyUrl ?? job.canonicalUrl ? (
                    <a
                      className="underline text-sm"
                      href={job.applyUrl ?? job.canonicalUrl ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {t('jobCatalog.openOriginal')}
                    </a>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <button
              type="button"
              disabled={page <= 1 || loading}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
              onClick={() => void runSearch(page - 1)}
            >
              {t('jobCatalog.previousPage')}
            </button>
            <span className="text-sm text-slate-700">
              {t('jobCatalog.pageLabel', { page, total: Math.max(totalPages, 1) })}
            </span>
            <button
              type="button"
              disabled={page >= totalPages || loading}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
              onClick={() => void runSearch(page + 1)}
            >
              {t('jobCatalog.nextPage')}
            </button>
          </div>
        </>
      ) : null}
    </section>
  )
}
