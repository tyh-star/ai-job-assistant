import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react-native'
import { QueryClientProvider } from '@tanstack/react-query'
import { catalogApi, jobsApi } from '../src/api/endpoints'
import type { JobCatalogSearchResponse } from '../src/api/types'
import { EMPTY_FILTERS } from '../src/jobs/model'
import { jobKeys, useCatalogJobActions } from '../src/jobs/queries'
import { catalogSummary, personalJob, searchPage } from '../test-utils/fixtures'
import { createTestQueryClient } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  catalogApi: { save: jest.fn(), markApplied: jest.fn(), clearApplied: jest.fn() },
  jobsApi: { list: jest.fn(), remove: jest.fn() },
  dashboardApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1 } }),
}))

const catalog = catalogApi as jest.Mocked<typeof catalogApi>
const jobs = jobsApi as jest.Mocked<typeof jobsApi>
const ID = 'freehire:a'

function setup() {
  const client = createTestQueryClient()
  client.setQueryData(jobKeys.mine(1), [])
  client.setQueryData(jobKeys.catalogSearch(1, EMPTY_FILTERS), {
    pages: [searchPage([catalogSummary(ID), catalogSummary('freehire:b')])],
    pageParams: [1],
  })
  function Provider({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  return { client, Provider }
}

function searchRow(client: ReturnType<typeof setup>['client'], id: string) {
  const data = client.getQueryData<{ pages: JobCatalogSearchResponse[] }>(jobKeys.catalogSearch(1, EMPTY_FILTERS))
  return data?.pages[0].results.find((row) => row.id === id)
}

beforeEach(() => {
  jest.resetAllMocks()
  jobs.list.mockResolvedValue([personalJob(42)])
})

it('writes a save into the loaded search results and refetches GET /jobs', async () => {
  const { client, Provider } = setup()
  catalog.save.mockResolvedValue({ jobId: 42, catalogJobId: ID, alreadySaved: false, job: personalJob(42) })
  const { result } = await renderHook(() => useCatalogJobActions(ID), { wrapper: Provider })

  await act(async () => {
    await result.current.save.mutateAsync()
  })

  expect(searchRow(client, ID)).toMatchObject({ saved: true, personalJobId: 42 })
  expect(searchRow(client, 'freehire:b')).toMatchObject({ saved: false, personalJobId: null })
  // GET /jobs is invalidated, so the dashboard and both lists refetch as soon as they are shown.
  expect(client.getQueryState(jobKeys.mine(1))?.isInvalidated).toBe(true)
  // Catalog data is only marked stale: no extra upstream search is fired.
  expect(client.getQueryState(jobKeys.catalogSearch(1, EMPTY_FILTERS))?.isInvalidated).toBe(true)
})

it('clears saved and applied state everywhere after an unsave', async () => {
  const { client, Provider } = setup()
  client.setQueryData(jobKeys.catalogSearch(1, EMPTY_FILTERS), {
    pages: [searchPage([catalogSummary(ID, { saved: true, applied: true, personalJobId: 42, appliedAt: '2026-09-10T00:00:00' })])],
    pageParams: [1],
  })
  jobs.remove.mockResolvedValue(undefined)
  const { result } = await renderHook(() => useCatalogJobActions(ID), { wrapper: Provider })

  await act(async () => {
    await result.current.unsave.mutateAsync(42)
  })

  expect(jobs.remove).toHaveBeenCalledWith(42)
  expect(searchRow(client, ID)).toMatchObject({ saved: false, applied: false, personalJobId: null, appliedAt: null })
})
