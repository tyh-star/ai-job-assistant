import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  searchCatalogCities,
  searchJobCatalog,
} from '../api/jobCatalog'
import {
  clearJobSearchHistory,
  deleteJobSearchHistory,
  listJobSearchHistory,
  recordJobSearchHistory,
} from '../api/jobSearchHistory'
import type {
  CatalogEmploymentType,
  CatalogRemoteType,
  CitySuggestion,
  JobCatalogSummary,
  JobSearchHistoryItem,
  RecordJobSearchHistoryRequest,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { catalogJobPath, isSpecifiedCatalogFacet } from '../jobCatalog/catalogJobPath'
import { countryLabel } from '../jobCatalog/countryLabel'
import {
  jobsSearchQueryToCriteria,
  parseJobsSearchQuery,
  serializeJobsSearchQuery,
} from '../jobCatalog/searchQuery'
import { consumeJobsSearchScroll, saveJobsSearchScroll } from '../jobCatalog/searchScroll'
import { catalogSearchParamsKey, mergeJobsTabIntoSearch } from '../jobs/jobsTab'
import { Badge } from './ui/Badge'
import { LoadingSkeleton } from './ui/LoadingSkeleton'
import { cx } from './ui/cx'
import {
  clearAnonymousHistory,
  deleteAnonymousHistory,
  isEmptySearchCriteria,
  loadAnonymousHistory,
  recordAnonymousHistory,
} from '../jobSearchHistory/anonymousHistory'

const POSTED_WITHIN_DAYS = [1, 3, 7, 14, 30] as const
// Fixed at 10 -- not user-configurable -- so the page count stays predictable and
// stays correctly aligned with FreeHire's own page numbering (limit/offset are derived
// straight from this constant; see FreeHireJobCatalogService#buildSearchParams).
const PAGE_SIZE = 10

const REMOTE_TYPES: CatalogRemoteType[] = ['REMOTE', 'HYBRID', 'ONSITE']
// TEMPORARY and OTHER are deliberately NOT offered here: the active job search
// provider is FreeHire's hosted API, whose employment-type vocabulary has no
// equivalent for either (see FreeHireJobCatalogService#employmentTypeOrNull, which
// rejects them with a 400 rather than silently ignoring the selection). Offering them
// only for the backend to reject would be its own kind of silent-looking failure, so
// the option is absent instead. If local_catalog (which does support both) ever
// becomes the default again, this list needs revisiting alongside that switch.
const EMPLOYMENT_TYPES: CatalogEmploymentType[] = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'INTERNSHIP',
]

/** ISO-3166 alpha-2 codes the collector currently populates. */
const COUNTRIES = ['CA', 'US', 'GB', 'DE', 'FR', 'NL', 'IE', 'AU', 'IN', 'SG'] as const

const CITY_QUERY_MIN_LENGTH = 2
const CITY_QUERY_DEBOUNCE_MS = 300

const INPUT_CLASS = 'ui-input mt-1'
const LABEL_CLASS = 'block text-sm text-ink'

function CheckIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M4 10.5 8 14l8-8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function ChevronIcon() {
  return (
    <svg
      className="h-4 w-4 shrink-0"
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden="true"
    >
      <path d="M7.5 5 12.5 10 7.5 15" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function SearchIcon() {
  return (
    <svg className="h-4 w-4 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <circle cx="8.5" cy="8.5" r="5" />
      <path d="M12.5 12.5 17 17" strokeLinecap="round" />
    </svg>
  )
}

function BookmarkIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M5 3.5A1.5 1.5 0 0 1 6.5 2h7A1.5 1.5 0 0 1 15 3.5v13.2l-5-2.7-5 2.7V3.5Z" />
    </svg>
  )
}

function CompanyMark({ name }: { name: string }) {
  const letter = (name.trim().charAt(0) || '?').toUpperCase()
  return (
    <span
      aria-hidden="true"
      className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-sm font-semibold text-brand"
    >
      {letter}
    </span>
  )
}

/**
 * Search over the site's job catalog. Search and browsing need no account.
 * Saving and applied-tracking live on the dedicated detail page.
 */
export function JobCatalogSearch() {
  const { t, i18n } = useTranslation()
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const initialQuery = parseJobsSearchQuery(searchParams)
  const syncedQueryRef = useRef(catalogSearchParamsKey(searchParams))
  const restoredScrollRef = useRef(false)
  const locationRef = useRef(location)
  locationRef.current = location

  const [keywords, setKeywords] = useState(initialQuery.q)
  const [country, setCountry] = useState(initialQuery.city ? initialQuery.cityCountry : initialQuery.country)
  const [remoteType, setRemoteType] = useState(initialQuery.workMode)
  const [employmentType, setEmploymentType] = useState(initialQuery.employmentType)
  const [postedWithinDays, setPostedWithinDays] = useState(initialQuery.postedWithin)
  const [sort, setSort] = useState(initialQuery.sort)

  // City: a structured facet, never free text -- `city` is only ever set to a `value`
  // `searchCatalogCities` actually returned. `cityQuery` is the raw text the user is
  // typing, which drives suggestions but is NEVER itself sent as a search filter.
  //
  // `country` is NOT disabled once a city is chosen -- it stays a real, always-usable
  // control with two roles depending on whether a city is currently selected:
  //   - no city selected: `country` IS the search filter, sent as-is.
  //   - a city IS selected: `country` instead shows that suggestion's OWN country (see
  //     onCitySelected) purely for display -- e.g. picking "London (GB)" shows "GB" in
  //     this select even though nothing forces the user to have picked GB themselves.
  //     It is never sent to the backend alongside city (see runSearch below): FreeHire's
  //     countries/cities facets share one OR-group, so sending both would only widen the
  //     search, never narrow it to "this city, in this country" -- see
  //     FreeHireJobCatalogService#buildSearchParams. The user manually changing this
  //     select (onCountryChanged) is what turns it back into a real filter, and clears
  //     the city so the two roles are never ambiguous.
  const [cityQuery, setCityQuery] = useState(initialQuery.city)
  const [city, setCity] = useState(initialQuery.city)
  const [citySuggestions, setCitySuggestions] = useState<CitySuggestion[]>([])
  const [cityLookupError, setCityLookupError] = useState(false)
  const [cityLookupLoading, setCityLookupLoading] = useState(false)
  const [showCitySuggestions, setShowCitySuggestions] = useState(false)

  const [results, setResults] = useState<JobCatalogSummary[]>([])
  const [page, setPage] = useState(initialQuery.page)
  const [totalPages, setTotalPages] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [hasSearched, setHasSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)

  // Scroll target for pagination -- see runSearch's `scrollToResultsOnSuccess`. Anchored
  // to the results-count line itself, never the top of the page.
  const resultsHeadingRef = useRef<HTMLParagraphElement>(null)
  const [history, setHistory] = useState<JobSearchHistoryItem[]>([])
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(false)

  async function refreshHistory() {
    try {
      if (isAuthenticated) {
        const response = await listJobSearchHistory()
        setHistory(response.data)
      } else {
        setHistory(loadAnonymousHistory())
      }
    } catch {
      setHistory(isAuthenticated ? [] : loadAnonymousHistory())
    }
  }

  async function persistHistory(payload: RecordJobSearchHistoryRequest) {
    try {
      if (isAuthenticated) {
        await recordJobSearchHistory(payload)
        await refreshHistory()
      } else {
        setHistory(recordAnonymousHistory(payload))
      }
    } catch {
      // History writes must never fail a search that already succeeded.
    }
  }

  function syncSearchUrl(targetPage: number, payload: RecordJobSearchHistoryRequest) {
    const query = mergeJobsTabIntoSearch(
      searchParams,
      serializeJobsSearchQuery({
        q: payload.keywords ?? '',
        city: payload.city ?? '',
        cityCountry: payload.cityCountry ?? '',
        country: payload.country ?? '',
        workMode: payload.remoteType ?? '',
        employmentType: payload.employmentType ?? '',
        postedWithin: payload.postedWithinDays ? String(payload.postedWithinDays) : '',
        sort: payload.sort ?? '',
        page: targetPage,
      }),
    )
    const next = query.toString()
    syncedQueryRef.current = catalogSearchParamsKey(query)
    if (next !== searchParams.toString()) {
      setSearchParams(query, { replace: true })
    }
    saveJobsSearchScroll(next ? `?${next}` : '', window.scrollY)
  }

  // Only Next/Previous pass scrollToResultsOnSuccess=true -- the initial on-mount load
  // and a brand-new keyword/filter search must never auto-scroll the page, only a
  // pagination change on an already-visible results list. recordHistory is true only
  // for an explicit Search click / Enter -- never mount or pagination.
  const runSearch = useCallback(
    async (
      targetPage: number,
      options: {
        scrollToResultsOnSuccess?: boolean
        recordHistory?: boolean
        criteria?: RecordJobSearchHistoryRequest
        syncUrl?: boolean
      } = {},
    ) => {
      setLoading(true)
      setErrorKey(null)
      const historyPayload: RecordJobSearchHistoryRequest = options.criteria ?? {
        keywords: keywords || undefined,
        city: city || undefined,
        cityCountry: city ? country || undefined : undefined,
        country: city ? undefined : country || undefined,
        remoteType: remoteType || undefined,
        employmentType: employmentType || undefined,
        postedWithinDays: postedWithinDays ? Number(postedWithinDays) : undefined,
        sort: sort || undefined,
      }
      try {
        const response = await searchJobCatalog({
          q: historyPayload.keywords ?? '',
          city: historyPayload.city ?? '',
          cityCountry: historyPayload.city ? historyPayload.cityCountry : undefined,
          country: historyPayload.city ? undefined : historyPayload.country,
          remoteType: historyPayload.remoteType ?? '',
          employmentType: historyPayload.employmentType ?? '',
          postedWithinDays: historyPayload.postedWithinDays,
          page: targetPage,
          size: PAGE_SIZE,
          sort: historyPayload.sort ?? '',
        })
        const nextResults = response.data.results
        setResults(nextResults)
        setPage(response.data.page)
        setTotalPages(response.data.totalPages)
        setTotalCount(response.data.totalCount)
        if (options.syncUrl !== false) {
          syncSearchUrl(response.data.page, historyPayload)
        }
        if (options.recordHistory && !isEmptySearchCriteria(historyPayload)) {
          void persistHistory(historyPayload)
        }
        if (options.scrollToResultsOnSuccess) {
          const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
          resultsHeadingRef.current?.scrollIntoView({
            behavior: reducedMotion ? 'auto' : 'smooth',
            block: 'start',
          })
        }
      } catch (error) {
        setResults([])
        setTotalPages(0)
        setTotalCount(0)
        setErrorKey(apiErrorKey(error))
      } finally {
        setLoading(false)
        setHasSearched(true)
      }
    },
    [keywords, city, country, remoteType, employmentType, postedWithinDays, sort, isAuthenticated, searchParams, setSearchParams],
  )

  useEffect(() => {
    const key = catalogSearchParamsKey(searchParams)
    if (key === syncedQueryRef.current && hasSearched) {
      return
    }
    const parsed = parseJobsSearchQuery(searchParams)
    setKeywords(parsed.q)
    setCity(parsed.city)
    setCityQuery(parsed.city)
    setCountry(parsed.city ? parsed.cityCountry : parsed.country)
    setRemoteType(parsed.workMode)
    setEmploymentType(parsed.employmentType)
    setPostedWithinDays(parsed.postedWithin)
    setSort(parsed.sort)
    syncedQueryRef.current = key
    void runSearch(parsed.page, {
      criteria: jobsSearchQueryToCriteria(parsed),
      syncUrl: false,
    })
    // URL is the source of truth when the visitor lands or uses the back button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  useEffect(() => {
    return () => {
      const { pathname, search } = locationRef.current
      if (pathname === '/jobs') {
        saveJobsSearchScroll(search, window.scrollY)
      }
    }
  }, [])

  useEffect(() => {
    if (loading || !hasSearched || restoredScrollRef.current) {
      return
    }
    const y = consumeJobsSearchScroll(location.search)
    if (y != null) {
      restoredScrollRef.current = true
      window.scrollTo(0, y)
    }
  }, [loading, hasSearched, results, location.search])

  useEffect(() => {
    void refreshHistory()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated])

  // Debounced city-suggestion lookup. Skipped once a city is already selected (typing
  // stops driving suggestions until the selection is cleared) and for very short
  // queries, so this does not fire on every single keystroke. Scoped by `country` when
  // one is already selected -- picking a country first and then typing a city narrows
  // the suggestions to that country instead of searching every city by that name.
  const cityLookupToken = useRef(0)
  useEffect(() => {
    if (city || cityQuery.trim().length < CITY_QUERY_MIN_LENGTH) {
      setCitySuggestions([])
      setCityLookupError(false)
      setCityLookupLoading(false)
      return
    }
    const token = ++cityLookupToken.current
    setCityLookupLoading(true)
    const timer = setTimeout(() => {
      searchCatalogCities(cityQuery, country || undefined)
        .then((response) => {
          if (cityLookupToken.current !== token) {
            return
          }
          setCitySuggestions(response.data)
          setCityLookupError(false)
        })
        .catch(() => {
          if (cityLookupToken.current !== token) {
            return
          }
          // Degrade gracefully: the city lookup failing must never block the rest of
          // the form -- keyword+country search keeps working either way.
          setCitySuggestions([])
          setCityLookupError(true)
        })
        .finally(() => {
          if (cityLookupToken.current === token) {
            setCityLookupLoading(false)
          }
        })
    }, CITY_QUERY_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [cityQuery, city, country])

  function onCitySelected(suggestion: CitySuggestion) {
    setCity(suggestion.value)
    setCityQuery(suggestion.value)
    setCitySuggestions([])
    setShowCitySuggestions(false)
    // Shows this suggestion's OWN country in the (still-enabled) country select -- purely
    // informational, never sent to the backend alongside city (see runSearch). This is
    // display/suggestion metadata, not a search-side AND condition: two postings named
    // "London" in different real countries would both still match a bare city=London
    // search regardless of what country is shown here (see buildSearchParams' javadoc).
    setCountry(suggestion.countryCode ? suggestion.countryCode.toUpperCase() : '')
  }

  function onClearCity() {
    setCity('')
    setCityQuery('')
    setCitySuggestions([])
    // The country shown while a city was selected was only ever that suggestion's own
    // metadata, never something the user chose as a filter -- clearing the city must not
    // leave it behind as a surprise country filter the user never actually picked.
    setCountry('')
  }

  function onCountryChanged(value: string) {
    setCountry(value)
    // A real, explicit change to this select -- as opposed to onCitySelected merely
    // reflecting a suggestion's own country -- means the user now wants country to be
    // the actual filter. Clear any selected city so the two roles are never ambiguous.
    if (city) {
      setCity('')
      setCityQuery('')
      setCitySuggestions([])
    }
  }

  return (
    <section id="job-catalog-search" className="mt-6 min-w-0 overflow-hidden rounded-lg border border-line bg-card p-5 shadow-card">
      <h2 className="section-title">{t('jobCatalog.title')}</h2>
      <p className="mt-1 text-sm text-muted">{t('jobCatalog.subtitle')}</p>

      <form
        className="mt-4 grid gap-3 md:grid-cols-2 lg:grid-cols-3"
        onSubmit={(event) => {
          event.preventDefault()
          void runSearch(1, { recordHistory: true })
        }}
      >
        <label className={`${LABEL_CLASS} md:col-span-2`}>
          <span>{t('jobCatalog.keywords')}</span>
          <input
            className={`${INPUT_CLASS} min-h-12 text-base`}
            value={keywords}
            onChange={(event) => setKeywords(event.target.value)}
          />
        </label>
        <div className="relative">
          <label className={LABEL_CLASS}>
            <span>{t('jobCatalog.city')}</span>
            <div className="mt-1 flex items-center gap-2">
              <input
                className={`${INPUT_CLASS} mt-0`}
                value={cityQuery}
                placeholder={t('jobCatalog.cityPlaceholder')}
                onChange={(event) => {
                  setCity('')
                  setCityQuery(event.target.value)
                  setShowCitySuggestions(true)
                }}
                onFocus={() => setShowCitySuggestions(true)}
                onBlur={() => {
                  // Delay so a click on a suggestion registers before the list unmounts.
                  setTimeout(() => setShowCitySuggestions(false), 150)
                }}
                role="combobox"
                aria-expanded={showCitySuggestions && citySuggestions.length > 0}
                aria-autocomplete="list"
              />
            </div>
          </label>
          {/* Outside the <label>: a wrapping label's accessible name includes ALL of
              its text content, so status text and the clear button live here instead --
              otherwise "城市" as a label query would stop matching the moment any of
              this conditional text renders. */}
          {city ? (
            <button
              type="button"
              className="absolute right-2 top-8 shrink-0 rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs text-slate-600"
              onClick={onClearCity}
              aria-label={t('jobCatalog.cityClear')}
            >
              ×
            </button>
          ) : null}
          {showCitySuggestions && citySuggestions.length > 0 ? (
            <ul className="absolute z-10 mt-1 w-full rounded-lg border border-slate-300 bg-white text-sm shadow-lg">
              {citySuggestions.map((suggestion) => (
                <li key={`${suggestion.value}-${suggestion.countryCode ?? ''}`}>
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left hover:bg-slate-100"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => onCitySelected(suggestion)}
                  >
                    {suggestion.value}
                    {suggestion.countryCode ? ` · ${countryLabel(suggestion.countryCode.toUpperCase(), i18n.language)}` : ''}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {cityLookupLoading ? (
            <span className="mt-1 block text-xs text-slate-500">{t('jobCatalog.cityLoading')}</span>
          ) : null}
          {cityLookupError ? (
            <span className="mt-1 block text-xs text-amber-700">{t('jobCatalog.cityLookupUnavailable')}</span>
          ) : null}
          {city ? (
            <span className="mt-1 block text-sm text-muted">{t('jobCatalog.cityNameOnlyMatchHint')}</span>
          ) : null}
        </div>
        <button
          type="button"
          className="rounded-md border border-line px-3 py-2 text-sm text-ink md:hidden"
          aria-expanded={moreFiltersOpen}
          onClick={() => setMoreFiltersOpen((open) => !open)}
        >
          {t('jobCatalog.moreFilters')}
        </button>
        <div className={cx(moreFiltersOpen ? 'grid gap-3 sm:grid-cols-2 md:contents' : 'hidden md:contents')}>
        <div>
          <label className={LABEL_CLASS}>
            <span>{t('jobCatalog.country')}</span>
            <select
              className={INPUT_CLASS}
              value={country}
              onChange={(event) => onCountryChanged(event.target.value)}
            >
              <option value="">{t('jobCatalog.anyCountry')}</option>
              {/* A selected city's own suggestion country (see onCitySelected) may be
                  outside this fixed list -- rendered here so the select's displayed value
                  always matches `country` exactly, never silently falling back to blank. */}
              {country && !(COUNTRIES as readonly string[]).includes(country) ? (
                <option value={country}>{countryLabel(country, i18n.language)}</option>
              ) : null}
              {COUNTRIES.map((code) => (
                <option key={code} value={code}>{countryLabel(code, i18n.language)}</option>
              ))}
            </select>
          </label>
          {city ? (
            <span className="mt-1 block text-sm text-muted">
              {t('jobCatalog.countryReflectsCitySuggestion')}
            </span>
          ) : null}
        </div>
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
        </div>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={loading}
            className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-brand disabled:opacity-60 md:w-auto"
          >
            <SearchIcon />
            {loading ? t('jobCatalog.searching') : t('jobCatalog.search')}
          </button>
        </div>
      </form>

      <RecentSearches
        items={history}
        onReplay={(item) => {
          const criteria: RecordJobSearchHistoryRequest = {
            keywords: item.keywords ?? undefined,
            city: item.city ?? undefined,
            cityCountry: item.cityCountry ?? undefined,
            country: item.country ?? undefined,
            remoteType: item.workMode ?? undefined,
            employmentType: item.employmentType ?? undefined,
            postedWithinDays: item.postedWithinDays ?? undefined,
            sort: item.sortBy === 'POSTED_AT' ? 'POSTED_AT' : undefined,
          }
          setKeywords(criteria.keywords ?? '')
          setCity(criteria.city ?? '')
          setCityQuery(criteria.city ?? '')
          setCountry((criteria.cityCountry ?? criteria.country ?? '').toUpperCase())
          setRemoteType(criteria.remoteType ?? '')
          setEmploymentType(criteria.employmentType ?? '')
          setPostedWithinDays(criteria.postedWithinDays ? String(criteria.postedWithinDays) : '')
          setSort(criteria.sort === 'POSTED_AT' ? 'POSTED_AT' : '')
          void runSearch(1, { recordHistory: true, criteria })
        }}
        onDelete={async (id) => {
          try {
            if (isAuthenticated) {
              await deleteJobSearchHistory(id)
              await refreshHistory()
            } else {
              setHistory(deleteAnonymousHistory(id))
            }
          } catch {
            /* ignore */
          }
        }}
        onClear={async () => {
          try {
            if (isAuthenticated) {
              await clearJobSearchHistory()
              await refreshHistory()
            } else {
              setHistory(clearAnonymousHistory())
            }
          } catch {
            /* ignore */
          }
        }}
      />

      {loading ? (
        <LoadingSkeleton className="mt-4" rows={5} label={t('jobCatalog.loading')} />
      ) : null}

      {errorKey ? (
        <p role="alert" className="mt-4 text-sm text-red-700">
          {t(errorKey)}
        </p>
      ) : null}

      {!loading && !errorKey && hasSearched && results.length === 0 ? (
        <p className="mt-4 text-sm text-slate-600">{t('jobCatalog.empty')}</p>
      ) : null}

      {!errorKey && results.length > 0 ? (
        <>
          <p ref={resultsHeadingRef} className="text-meta mt-4 text-slate-600">
            {/* A city filter's total comes from FreeHire's own aggregate count over a
                bare name match (see cityMatch below) -- confirmed live to include
                postings whose real location is a different country entirely, so it is
                never presented as an exact "here is what we have" count the way the
                unfiltered wording implies. */}
            {city
              ? t('jobCatalog.resultCountCityFiltered', { count: totalCount })
              : t('jobCatalog.resultCount', { count: totalCount })}
          </p>
          <ul className="mt-3 min-w-0 space-y-3">
            {results.map((job) => {
              const href = catalogJobPath(job.id)
              return (
                <li key={job.id} className="min-w-0 overflow-hidden rounded-lg border border-line bg-card shadow-card motion-safe:transition-shadow motion-safe:hover:-translate-y-0.5 motion-safe:hover:shadow-lift">
                  <div className="flex w-full min-w-0 items-start gap-3 p-4">
                    <CompanyMark name={job.companyName} />
                    <div className="min-w-0 flex-1 overflow-hidden">
                      <div className="flex min-w-0 flex-wrap items-center gap-2">
                        <Link
                          to={href}
                          className="break-words text-base font-semibold text-ink hover:text-brand hover:underline"
                          onClick={() => saveJobsSearchScroll(location.search, window.scrollY)}
                        >
                          {job.title}
                        </Link>
                        {job.status === 'EXPIRED' ? (
                          <Badge tone="warning">{t('jobCatalog.expiredBadge')}</Badge>
                        ) : null}
                        {job.saved ? (
                          <Badge tone="accent" icon={<BookmarkIcon />}>{t('jobCatalog.savedBadge')}</Badge>
                        ) : null}
                        {job.applied ? (
                          <Badge tone="success" icon={<CheckIcon />}>{t('jobCatalog.appliedBadge')}</Badge>
                        ) : null}
                      </div>
                      <p className="mt-1 break-words text-sm text-ink">{job.companyName}</p>
                      {job.cityMatch ? (
                        <p className="mt-1 break-words text-sm text-muted">
                          <span className="font-medium text-ink">{t('jobCatalog.cityMatchLabel')}</span>{' '}
                          {job.cityMatch.countryConfirmed && job.cityMatch.countryCode
                            ? `${job.cityMatch.city}, ${countryLabel(job.cityMatch.countryCode.toUpperCase(), i18n.language)}`
                            : t('jobCatalog.cityMatchUnconfirmed', { city: job.cityMatch.city })}
                        </p>
                      ) : null}
                      <p className="mt-1 break-words text-sm text-muted">
                        {job.cityMatch ? (
                          <span className="font-medium text-ink">{t('jobCatalog.originalLocationLabel')} </span>
                        ) : null}
                        {[job.locationText, job.countryCode].filter(Boolean).join(' · ')}
                      </p>
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {isSpecifiedCatalogFacet(job.remoteType) ? (
                          <Badge>{t(`jobCatalog.remote.${job.remoteType}`)}</Badge>
                        ) : null}
                        {isSpecifiedCatalogFacet(job.employmentType) ? (
                          <Badge>{t(`jobCatalog.employment.${job.employmentType}`)}</Badge>
                        ) : null}
                        {job.postedAt ? (
                          <Badge>{`${t('jobCatalog.postedAt')}: ${job.postedAt.slice(0, 10)}`}</Badge>
                        ) : null}
                      </div>
                    </div>
                    <Link
                      to={href}
                      className="flex shrink-0 items-center gap-1 pt-0.5 text-sm text-brand"
                      aria-label={`${job.title} — ${t('jobCatalog.viewDetails')}`}
                      onClick={() => saveJobsSearchScroll(location.search, window.scrollY)}
                    >
                      {t('jobCatalog.viewDetails')}
                      <ChevronIcon />
                    </Link>
                  </div>
                </li>
              )
            })}
          </ul>
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <button
              type="button"
              disabled={page <= 1 || loading}
              className="rounded-md border border-line px-4 py-2 text-sm disabled:opacity-60"
              onClick={() => void runSearch(page - 1, { scrollToResultsOnSuccess: true })}
            >
              {t('jobCatalog.previousPage')}
            </button>
            <span className="text-sm text-ink">
              {t('jobCatalog.pageLabel', { page, total: Math.max(totalPages, 1) })}
            </span>
            <button
              type="button"
              disabled={page >= totalPages || loading}
              className="rounded-md border border-line px-4 py-2 text-sm disabled:opacity-60"
              onClick={() => void runSearch(page + 1, { scrollToResultsOnSuccess: true })}
            >
              {t('jobCatalog.nextPage')}
            </button>
          </div>
        </>
      ) : null}
    </section>
  )
}

function RecentSearches({
  items,
  onReplay,
  onDelete,
  onClear,
}: {
  items: JobSearchHistoryItem[]
  onReplay: (item: JobSearchHistoryItem) => void
  onDelete: (id: number) => void
  onClear: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="mt-4 min-w-0">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-ink">{t('jobCatalog.recentSearches')}</h3>
        {items.length > 0 ? (
          <button type="button" className="text-sm text-muted underline" onClick={onClear}>
            {t('jobCatalog.recentSearchClear')}
          </button>
        ) : null}
      </div>
      {items.length === 0 ? (
        <p className="mt-2 text-sm text-muted">{t('jobCatalog.recentSearchEmpty')}</p>
      ) : (
        <ul className="mt-2 flex flex-wrap gap-2">
          {items.map((item) => (
            <li key={item.id} className="flex min-w-0 max-w-full items-center gap-1 rounded-full border border-line bg-slate-50 py-1 pl-1 pr-2">
              <button
                type="button"
                className="min-w-0 truncate rounded-full px-2 py-1 text-sm text-ink"
                onClick={() => onReplay(item)}
              >
                {item.keywords || t('jobCatalog.recentSearchKeywordsFallback')}
              </button>
              <button
                type="button"
                className="shrink-0 rounded-full px-2 py-1 text-sm text-muted hover:text-danger"
                aria-label={t('jobCatalog.recentSearchDelete')}
                onClick={() => onDelete(item.id)}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
