export type JobsTab = 'SEARCH' | 'SAVED' | 'APPLIED'

export const JOBS_TAB_PARAM = 'tab'

export function jobsTabQueryValue(tab: JobsTab): string | null {
  if (tab === 'SAVED') {
    return 'favorites'
  }
  if (tab === 'APPLIED') {
    return 'applied'
  }
  return null
}

export function jobsTabReturnPath(tab: 'SAVED' | 'APPLIED'): string {
  return `/jobs?${JOBS_TAB_PARAM}=${jobsTabQueryValue(tab)}`
}

export function jobsTabFromLocation(params: URLSearchParams, hash: string): JobsTab {
  const tab = params.get(JOBS_TAB_PARAM)
  if (tab === 'favorites') {
    return 'SAVED'
  }
  if (tab === 'applied') {
    return 'APPLIED'
  }
  if (tab === 'search') {
    return 'SEARCH'
  }
  if (hash === '#applied') {
    return 'APPLIED'
  }
  if (hash === '#saved' || hash === '#favorites') {
    return 'SAVED'
  }
  return 'SEARCH'
}

export function writeJobsTabParam(params: URLSearchParams, tab: JobsTab): URLSearchParams {
  const next = new URLSearchParams(params)
  const value = jobsTabQueryValue(tab)
  if (value) {
    next.set(JOBS_TAB_PARAM, value)
  } else {
    next.delete(JOBS_TAB_PARAM)
  }
  return next
}

/** Catalog search criteria only -- switching Favorites/Applied must not re-run search. */
export function catalogSearchParamsKey(params: URLSearchParams): string {
  const next = new URLSearchParams(params)
  next.delete(JOBS_TAB_PARAM)
  return next.toString()
}

export function mergeJobsTabIntoSearch(
  source: URLSearchParams,
  catalogQuery: URLSearchParams,
): URLSearchParams {
  const next = new URLSearchParams(catalogQuery)
  const tab = source.get(JOBS_TAB_PARAM)
  if (tab) {
    next.set(JOBS_TAB_PARAM, tab)
  }
  return next
}
