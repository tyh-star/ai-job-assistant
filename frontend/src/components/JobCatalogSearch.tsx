import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { apiErrorKey } from '../api/client'
import {
  clearJobCatalogApplied,
  markJobCatalogApplied,
  saveJobCatalogJob,
  searchCatalogCities,
  searchJobCatalog,
} from '../api/jobCatalog'
import type {
  CatalogEmploymentType,
  CatalogRemoteType,
  CitySuggestion,
  JobCatalogSummary,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { loginPathWithReturnTo } from '../auth/returnTo'

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

const INPUT_CLASS = 'mt-1 w-full rounded-lg border border-slate-300 px-3 py-2'
const LABEL_CLASS = 'block text-sm'

function CheckIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M4 10.5 8 14l8-8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/**
 * "Canada (CA)" / "加拿大（CA）" -- never a bare code. `Intl.DisplayNames` is the same
 * built-in idiom FreeHire's own frontend uses for this (confirmed in the pinned
 * upstream source's geo.go: "the caller already has countryLabel() ... Intl.DisplayNames
 * -backed"). Falls back to the bare code if the runtime lacks the API or does not
 * recognise it -- never throws, never blocks rendering the option.
 */
function countryLabel(code: string, language: string): string {
  try {
    const displayNames = new Intl.DisplayNames([language], { type: 'region' })
    const name = displayNames.of(code)
    if (!name || name === code) {
      return code
    }
    return language.startsWith('zh') ? `${name}（${code}）` : `${name} (${code})`
  } catch {
    return code
  }
}

/**
 * Search over the site's job catalog. Search, browsing and detail need no account --
 * only saving a posting and confirming/revoking "applied" require login (see
 * `useAuth().isAuthenticated` gates below).
 */
export function JobCatalogSearch(
  { onSaved, onApplied }: { onSaved?: () => void; onApplied?: () => void } = {},
) {
  const { t, i18n } = useTranslation()
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()

  const [keywords, setKeywords] = useState('')
  const [country, setCountry] = useState('')
  const [remoteType, setRemoteType] = useState('')
  const [employmentType, setEmploymentType] = useState('')
  const [postedWithinDays, setPostedWithinDays] = useState('')
  const [sort, setSort] = useState('')

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
  const [cityQuery, setCityQuery] = useState('')
  const [city, setCity] = useState('')
  const [citySuggestions, setCitySuggestions] = useState<CitySuggestion[]>([])
  const [cityLookupError, setCityLookupError] = useState(false)
  const [cityLookupLoading, setCityLookupLoading] = useState(false)
  const [showCitySuggestions, setShowCitySuggestions] = useState(false)

  const [results, setResults] = useState<JobCatalogSummary[]>([])
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [hasSearched, setHasSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const [savingId, setSavingId] = useState<string | null>(null)
  const [saveErrorId, setSaveErrorId] = useState<string | null>(null)
  // Distinct from saveErrorId: the UPSTREAM posting's own detail lookup 404'd (see
  // JobDetailUnavailableException) -- a genuinely different fact from a transient save
  // failure, so it gets its own persistent per-card marker (a Set, since more than one
  // card in the same result page could independently hit this) rather than a single
  // "last error" id that a later action would silently clear. Once marked, the card
  // stays marked "详情不可用" -- retrying a save can never succeed for a gone posting.
  const [detailUnavailableIds, setDetailUnavailableIds] = useState<Set<string>>(new Set())

  // Scroll target for pagination -- see runSearch's `scrollToResultsOnSuccess`. Anchored
  // to the results-count line itself, never the top of the page.
  const resultsHeadingRef = useRef<HTMLParagraphElement>(null)

  // "Applied" confirm-after-return flow: clicking the external apply link never marks
  // anything by itself. It only remembers (in memory -- the tab never unloads, since
  // the link opens in a new tab) which job the user was looking at, and asks once the
  // user comes back to THIS tab.
  const [pendingApplyId, setPendingApplyId] = useState<string | null>(null)
  const [confirmApplyId, setConfirmApplyId] = useState<string | null>(null)
  const [applyBusyId, setApplyBusyId] = useState<string | null>(null)
  const [applyErrorId, setApplyErrorId] = useState<string | null>(null)

  // Only Next/Previous pass scrollToResultsOnSuccess=true -- the initial on-mount load
  // and a brand-new keyword/filter search must never auto-scroll the page, only a
  // pagination change on an already-visible results list.
  const runSearch = useCallback(
    async (targetPage: number, options: { scrollToResultsOnSuccess?: boolean } = {}) => {
      setLoading(true)
      setErrorKey(null)
      try {
        const response = await searchJobCatalog({
          q: keywords,
          city,
          // Sent ONLY alongside city, as display/validation metadata the backend never
          // forwards to FreeHire as a country filter -- see cityMatch below and
          // JobCatalogSearchRequest#cityCountry's javadoc. `country` here, while a city
          // is selected, only ever holds that suggestion's OWN country (see the
          // `country` state comment above).
          cityCountry: city ? country || undefined : undefined,
          // Never sent alongside city -- see the `country` state comment above. When a
          // city is selected, `country` here only ever holds that suggestion's OWN
          // country for display, which must not reach the backend as a real filter.
          country: city ? undefined : country,
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
        // Results updated above BEFORE scrolling -- a failed load (catch below) never
        // reaches this line at all, so the page stays exactly where it was.
        if (options.scrollToResultsOnSuccess) {
          const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
          resultsHeadingRef.current?.scrollIntoView({
            behavior: reducedMotion ? 'auto' : 'smooth',
            block: 'start',
          })
        }
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
    [keywords, city, country, remoteType, employmentType, postedWithinDays, sort],
  )

  useEffect(() => {
    void runSearch(1)
    // Loads the newest indexed jobs once on mount; later runs are user-driven.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Prompts for confirmation the moment the user comes back to this tab after
  // following an apply link -- not on a timer, not automatically.
  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === 'visible' && pendingApplyId) {
        setConfirmApplyId(pendingApplyId)
        setPendingApplyId(null)
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onVisible)
    }
  }, [pendingApplyId])

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

  async function onSave(catalogJobId: string) {
    if (!isAuthenticated) {
      navigate(loginPathWithReturnTo('/jobs'))
      return
    }
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
    } catch (error) {
      if (apiErrorKey(error) === 'errors.jobDetailUnavailable') {
        // Never "保存职位失败，请重试" -- retrying a save can never succeed once the
        // upstream posting's own detail is gone. Marked permanently for this card
        // (not cleared by a later action the way saveErrorId would be).
        setDetailUnavailableIds((current) => new Set(current).add(catalogJobId))
      } else {
        setSaveErrorId(catalogJobId)
      }
    } finally {
      setSavingId(null)
    }
  }

  function onApplyLinkClicked(catalogJobId: string) {
    if (isAuthenticated) {
      setPendingApplyId(catalogJobId)
    }
    // Anonymous visitors just follow the link -- applied-tracking needs an account,
    // and no confirmation prompt is shown for them.
  }

  async function onConfirmApplied(catalogJobId: string) {
    setApplyBusyId(catalogJobId)
    setApplyErrorId(null)
    try {
      const response = await markJobCatalogApplied(catalogJobId)
      setResults((current) =>
        current.map((item) =>
          item.id === catalogJobId
            ? {
                ...item,
                saved: true,
                personalJobId: response.data.jobId,
                applied: true,
                appliedAt: response.data.job.appliedAt,
              }
            : item,
        ),
      )
      setConfirmApplyId(null)
      // A distinct signal from onSaved -- this job just moved to "已投递职位", not
      // merely "已保存职位" -- so the caller can switch the Jobs page to the right tab
      // instead of guessing which of the two things just happened from one callback.
      onApplied?.()
    } catch (error) {
      if (apiErrorKey(error) === 'errors.jobDetailUnavailable') {
        setDetailUnavailableIds((current) => new Set(current).add(catalogJobId))
      } else {
        setApplyErrorId(catalogJobId)
      }
    } finally {
      setApplyBusyId(null)
    }
  }

  function onDismissApplyPrompt() {
    setConfirmApplyId(null)
  }

  async function onRevokeApplied(catalogJobId: string) {
    setApplyBusyId(catalogJobId)
    setApplyErrorId(null)
    try {
      await clearJobCatalogApplied(catalogJobId)
      setResults((current) =>
        current.map((item) =>
          item.id === catalogJobId ? { ...item, applied: false, appliedAt: null } : item,
        ),
      )
      // Keeps the Jobs page's own personal-jobs list (已保存/已投递 tabs) in sync --
      // this job just moved back to 已保存职位.
      onSaved?.()
    } catch {
      setApplyErrorId(catalogJobId)
    } finally {
      setApplyBusyId(null)
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
            <span className="mt-1 block text-xs text-slate-500">{t('jobCatalog.cityNameOnlyMatchHint')}</span>
          ) : null}
        </div>
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
            <span className="mt-1 block text-xs text-slate-500">
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
          {t(errorKey)}
        </p>
      ) : null}

      {!loading && !errorKey && hasSearched && results.length === 0 ? (
        <p className="mt-4 text-sm text-slate-600">{t('jobCatalog.empty')}</p>
      ) : null}

      {!errorKey && results.length > 0 ? (
        <>
          <p ref={resultsHeadingRef} className="mt-4 text-sm text-slate-600">
            {/* A city filter's total comes from FreeHire's own aggregate count over a
                bare name match (see cityMatch below) -- confirmed live to include
                postings whose real location is a different country entirely, so it is
                never presented as an exact "here is what we have" count the way the
                unfiltered wording implies. */}
            {city
              ? t('jobCatalog.resultCountCityFiltered', { count: totalCount })
              : t('jobCatalog.resultCount', { count: totalCount })}
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
                  {job.applied ? (
                    <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-0.5 text-xs text-emerald-800">
                      <CheckIcon />
                      {t('jobCatalog.appliedBadge')}
                    </span>
                  ) : null}
                  {detailUnavailableIds.has(job.id) ? (
                    <span className="rounded bg-red-100 px-2 py-0.5 text-xs text-red-800">
                      {t('jobCatalog.detailUnavailableBadge')}
                    </span>
                  ) : null}
                </div>
                <p className="mt-1 text-sm text-slate-700">{job.companyName}</p>
                {job.cityMatch ? (
                  // A multi-location posting can genuinely match a city search while its
                  // OWN location text names a different place entirely (confirmed live:
                  // a Toronto search returning a posting whose own location reads
                  // "Remote (US)", because that same posting's structured cities list
                  // also includes Toronto) -- shown as its own line, separate from and
                  // never replacing the posting's own location below, so this never
                  // looks like the filter silently stopped working.
                  <p className="mt-1 text-sm text-slate-700">
                    <span className="font-medium">{t('jobCatalog.cityMatchLabel')}</span>{' '}
                    {job.cityMatch.countryConfirmed && job.cityMatch.countryCode
                      ? `${job.cityMatch.city}, ${countryLabel(job.cityMatch.countryCode.toUpperCase(), i18n.language)}`
                      : t('jobCatalog.cityMatchUnconfirmed', { city: job.cityMatch.city })}
                  </p>
                ) : null}
                <p className="mt-1 text-sm text-slate-600">
                  {job.cityMatch ? (
                    <span className="font-medium">{t('jobCatalog.originalLocationLabel')} </span>
                  ) : null}
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
                {detailUnavailableIds.has(job.id) ? (
                  <p role="alert" className="mt-2 text-sm text-red-700">
                    {t('errors.jobDetailUnavailable')}
                  </p>
                ) : saveErrorId === job.id ? (
                  <p role="alert" className="mt-2 text-sm text-red-700">
                    {t('jobCatalog.saveFailed')}
                  </p>
                ) : null}
                {applyErrorId === job.id ? (
                  <p role="alert" className="mt-2 text-sm text-red-700">
                    {t('jobCatalog.appliedFailed')}
                  </p>
                ) : null}
                {confirmApplyId === job.id ? (
                  <div className="mt-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm">
                    <p>{t('jobCatalog.appliedConfirmPrompt')}</p>
                    <div className="mt-2 flex gap-2">
                      <button
                        type="button"
                        disabled={applyBusyId === job.id}
                        className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white disabled:opacity-60"
                        onClick={() => void onConfirmApplied(job.id)}
                      >
                        {t('jobCatalog.appliedConfirmYes')}
                      </button>
                      <button
                        type="button"
                        disabled={applyBusyId === job.id}
                        className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                        onClick={onDismissApplyPrompt}
                      >
                        {t('jobCatalog.appliedConfirmNo')}
                      </button>
                    </div>
                  </div>
                ) : null}
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  {job.saved ? null : (
                    <button
                      type="button"
                      disabled={savingId === job.id || detailUnavailableIds.has(job.id)}
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
                  {job.applied ? (
                    <button
                      type="button"
                      disabled={applyBusyId === job.id}
                      className="text-xs text-slate-500 underline disabled:opacity-60"
                      onClick={() => void onRevokeApplied(job.id)}
                    >
                      {t('jobCatalog.appliedRevoke')}
                    </button>
                  ) : null}
                  {job.applyUrl ?? job.canonicalUrl ? (
                    <a
                      className="underline text-sm"
                      href={job.applyUrl ?? job.canonicalUrl ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                      onClick={() => onApplyLinkClicked(job.id)}
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
              onClick={() => void runSearch(page - 1, { scrollToResultsOnSuccess: true })}
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
