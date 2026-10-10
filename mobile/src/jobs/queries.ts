import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { catalogApi, jobsApi } from '../api/endpoints'
import type { JobCatalogDetail, JobCatalogSearchResponse, JobResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { nextCatalogPage, toSearchQuery, type SearchFilters } from './model'

type UserKey = number | null

/**
 * One GET /jobs cache per user feeds the dashboard, 已收藏 and 已投递 alike.
 * Personal detail keys nest under it, so invalidating the list covers them too.
 */
export const jobKeys = {
  mine: (userId: UserKey) => ['jobs', userId] as const,
  personal: (userId: UserKey, jobId: number) => ['jobs', userId, 'detail', jobId] as const,
  catalog: (userId: UserKey) => ['job-catalog', userId] as const,
  catalogSearch: (userId: UserKey, filters: SearchFilters) =>
    ['job-catalog', userId, 'search', { q: filters.q.trim(), city: filters.city?.value ?? null }] as const,
  catalogDetail: (userId: UserKey, catalogJobId: string) => ['job-catalog', userId, 'detail', catalogJobId] as const,
  cities: (q: string) => ['job-catalog-cities', q] as const,
}

function useUserKey(): UserKey {
  return useAuth().user?.userId ?? null
}

/** `enabled: false` for visitors: GET /jobs is a personal route and is never called without an account. */
export function useMyJobs(enabled = true) {
  const userId = useUserKey()
  return useQuery({ queryKey: jobKeys.mine(userId), queryFn: jobsApi.list, enabled })
}

export function usePersonalJob(jobId: number | null) {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: jobKeys.personal(userId, jobId ?? 0),
    queryFn: () => jobsApi.get(jobId as number),
    enabled: jobId !== null,
    placeholderData: () =>
      queryClient.getQueryData<JobResponse[]>(jobKeys.mine(userId))?.find((job) => job.id === jobId),
  })
}

/** `enabled` stays false until the catalog segment is first shown, so opening 已收藏 costs no search. */
export function useCatalogSearch(filters: SearchFilters, enabled = true) {
  const userId = useUserKey()
  return useInfiniteQuery({
    queryKey: jobKeys.catalogSearch(userId, filters),
    initialPageParam: 1,
    queryFn: ({ pageParam }) => catalogApi.search(toSearchQuery(filters, pageParam)),
    getNextPageParam: nextCatalogPage,
    enabled,
  })
}

export function useCatalogDetail(catalogJobId: string) {
  const userId = useUserKey()
  return useQuery({
    queryKey: jobKeys.catalogDetail(userId, catalogJobId),
    queryFn: () => catalogApi.detail(catalogJobId),
    enabled: catalogJobId.length > 0,
  })
}

export function useCitySuggestions(q: string, enabled: boolean) {
  return useQuery({
    queryKey: jobKeys.cities(q),
    queryFn: () => catalogApi.cities(q),
    enabled,
    staleTime: 5 * 60_000,
  })
}

type CatalogFlags = Pick<JobCatalogDetail, 'saved' | 'personalJobId' | 'applied' | 'appliedAt'>

/** Writes the new saved/applied state into every cached copy of one catalog posting. */
function patchCatalogCaches(queryClient: QueryClient, userId: UserKey, catalogJobId: string, flags: CatalogFlags) {
  queryClient.setQueryData<JobCatalogDetail>(jobKeys.catalogDetail(userId, catalogJobId), (current) =>
    current ? { ...current, ...flags } : current,
  )
  queryClient.setQueriesData<{ pages: JobCatalogSearchResponse[]; pageParams: unknown[] }>(
    { queryKey: [...jobKeys.catalog(userId), 'search'] },
    (current) =>
      current
        ? {
            ...current,
            pages: current.pages.map((page) => ({
              ...page,
              results: page.results.map((row) => (row.id === catalogJobId ? { ...row, ...flags } : row)),
            })),
          }
        : current,
  )
}

/**
 * After any saved/applied change: refetch the shared GET /jobs data (dashboard,
 * 已收藏, 已投递, personal detail) and mark catalog data stale without re-running
 * every loaded search page against the upstream provider.
 */
export async function refreshAfterJobChange(queryClient: QueryClient, userId: UserKey): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: jobKeys.mine(userId) }),
    queryClient.invalidateQueries({ queryKey: jobKeys.catalog(userId), refetchType: 'none' }),
  ])
}

export function useCatalogJobActions(catalogJobId: string) {
  const userId = useUserKey()
  const queryClient = useQueryClient()

  const save = useMutation({
    mutationFn: () => catalogApi.save(catalogJobId),
    onSuccess: async (response) => {
      patchCatalogCaches(queryClient, userId, catalogJobId, {
        saved: true,
        personalJobId: response.jobId,
        applied: response.job.applied,
        appliedAt: response.job.appliedAt,
      })
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  // The catalog has no "unsave" route: un-saving deletes the personal record by its
  // numeric id, which also removes any applied state on it.
  const unsave = useMutation({
    mutationFn: (personalJobId: number) => jobsApi.remove(personalJobId),
    onSuccess: async (_data, personalJobId) => {
      patchCatalogCaches(queryClient, userId, catalogJobId, {
        saved: false,
        personalJobId: null,
        applied: false,
        appliedAt: null,
      })
      queryClient.removeQueries({ queryKey: jobKeys.personal(userId, personalJobId) })
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const markApplied = useMutation({
    mutationFn: () => catalogApi.markApplied(catalogJobId),
    onSuccess: async (response) => {
      patchCatalogCaches(queryClient, userId, catalogJobId, {
        saved: true,
        personalJobId: response.jobId,
        applied: true,
        appliedAt: response.job.appliedAt,
      })
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const clearApplied = useMutation({
    mutationFn: () => catalogApi.clearApplied(catalogJobId),
    onSuccess: async (response) => {
      patchCatalogCaches(queryClient, userId, catalogJobId, {
        saved: true,
        personalJobId: response.jobId,
        applied: false,
        appliedAt: null,
      })
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const busy = save.isPending || unsave.isPending || markApplied.isPending || clearApplied.isPending
  return { save, unsave, markApplied, clearApplied, busy }
}

export function usePersonalJobActions(jobId: number) {
  const userId = useUserKey()
  const queryClient = useQueryClient()

  const writeRecord = (job: JobResponse) => {
    queryClient.setQueryData(jobKeys.personal(userId, jobId), job)
    queryClient.setQueryData<JobResponse[]>(jobKeys.mine(userId), (current) =>
      current?.map((row) => (row.id === job.id ? job : row)),
    )
  }

  const markApplied = useMutation({
    mutationFn: () => jobsApi.markApplied(jobId),
    onSuccess: async (job) => {
      writeRecord(job)
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const clearApplied = useMutation({
    mutationFn: () => jobsApi.clearApplied(jobId),
    onSuccess: async (job) => {
      writeRecord(job)
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const remove = useMutation({
    mutationFn: () => jobsApi.remove(jobId),
    onSuccess: async () => {
      queryClient.setQueryData<JobResponse[]>(jobKeys.mine(userId), (current) =>
        current?.filter((row) => row.id !== jobId),
      )
      queryClient.removeQueries({ queryKey: jobKeys.personal(userId, jobId) })
      await refreshAfterJobChange(queryClient, userId)
    },
  })

  const busy = markApplied.isPending || clearApplied.isPending || remove.isPending
  return { markApplied, clearApplied, remove, busy }
}
