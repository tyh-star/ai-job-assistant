import { api } from './client'
import { endpoints } from './endpoints'
import type {
  CitySuggestion,
  JobCatalogDetail,
  JobCatalogSearchParams,
  JobCatalogSearchResponse,
  SaveCatalogJobResponse,
} from './types'

/**
 * Reads this deployment's own catalog of collected public postings. There is no
 * external job API behind these calls.
 */
export function searchJobCatalog(params: JobCatalogSearchParams) {
  return api.get<JobCatalogSearchResponse>(endpoints.jobCatalogSearch, {
    params: {
      q: params.q || undefined,
      location: params.location || undefined,
      city: params.city || undefined,
      cityCountry: params.cityCountry || undefined,
      country: params.country || undefined,
      remoteType: params.remoteType || undefined,
      employmentType: params.employmentType || undefined,
      postedWithinDays: params.postedWithinDays,
      page: params.page ?? 1,
      size: params.size ?? 20,
      sort: params.sort || undefined,
    },
  })
}

/**
 * Structured city suggestions for the search form's city facet -- never free text.
 * `q` is a prefix; a blank value is not sent (the backend already treats it as an empty
 * list, but skipping the call entirely avoids firing one on every keystroke before the
 * user has typed anything).
 */
export function searchCatalogCities(q: string, country?: string) {
  return api.get<CitySuggestion[]>(endpoints.jobCatalogCities, {
    params: {
      q: q || undefined,
      country: country || undefined,
    },
  })
}

/** `catalogJobId` is the opaque id `searchJobCatalog` returned -- pass it back verbatim. */
export function getJobCatalogJob(catalogJobId: string) {
  return api.get<JobCatalogDetail>(endpoints.jobCatalogJob(catalogJobId))
}

/** Copies a catalog posting into the caller's own jobs, returning a personal job id. */
export function saveJobCatalogJob(catalogJobId: string) {
  return api.post<SaveCatalogJobResponse>(endpoints.jobCatalogSave(catalogJobId))
}

/**
 * Confirms the user actually submitted an application -- only ever called after the
 * user's own explicit "yes" to a confirmation prompt, never automatically on a click of
 * the external apply link. Implicitly saves the posting first if it was not already
 * saved. Requires login.
 */
export function markJobCatalogApplied(catalogJobId: string) {
  return api.post<SaveCatalogJobResponse>(endpoints.jobCatalogApplied(catalogJobId))
}

/** Reverses a mistaken applied confirmation. Requires login. */
export function clearJobCatalogApplied(catalogJobId: string) {
  return api.delete<SaveCatalogJobResponse>(endpoints.jobCatalogApplied(catalogJobId))
}
