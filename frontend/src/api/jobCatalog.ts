import { api } from './client'
import { endpoints } from './endpoints'
import type {
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

export function getJobCatalogJob(catalogJobId: number) {
  return api.get<JobCatalogDetail>(endpoints.jobCatalogJob(catalogJobId))
}

/** Copies a catalog posting into the caller's own jobs, returning a personal job id. */
export function saveJobCatalogJob(catalogJobId: number) {
  return api.post<SaveCatalogJobResponse>(endpoints.jobCatalogSave(catalogJobId))
}
