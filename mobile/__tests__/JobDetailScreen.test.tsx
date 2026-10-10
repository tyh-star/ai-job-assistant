import '../src/i18n'
import { Alert } from 'react-native'
import { screen, userEvent } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { jobsApi } from '../src/api/endpoints'
import { jobKeys } from '../src/jobs/queries'
import { JobDetailScreen } from '../src/screens/JobDetailScreen'
import { personalJob } from '../test-utils/fixtures'
import { createTestQueryClient, renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  jobsApi: { list: jest.fn(), get: jest.fn(), markApplied: jest.fn(), clearApplied: jest.fn(), remove: jest.fn() },
  catalogApi: {},
  dashboardApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const jobs = jobsApi as jest.Mocked<typeof jobsApi>

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    buttons?.[buttons.length - 1]?.onPress?.()
  })
  jobs.list.mockResolvedValue([])
})

it('marks and clears applied by the numeric personal id and updates the shared list', async () => {
  const client = createTestQueryClient()
  client.setQueryData(jobKeys.mine(1), [personalJob(7, { title: 'Office Coordinator' })])
  // The fake backend keeps one record, so a refetch after each action returns the new state.
  let record = personalJob(7, { title: 'Office Coordinator' })
  jobs.get.mockImplementation(async () => record)
  jobs.markApplied.mockImplementation(async () => {
    record = { ...record, applied: true, appliedAt: '2026-09-10T12:00:00' }
    return record
  })
  jobs.clearApplied.mockImplementation(async () => {
    record = { ...record, applied: false, appliedAt: null }
    return record
  })
  await renderWithQuery(<JobDetailScreen jobId={7} onBack={jest.fn()} onDeleted={jest.fn()} />, client)
  const user = userEvent.setup()

  expect(await screen.findByText('Office Coordinator')).toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '标记已投递' }))
  expect(await screen.findByText('已标记为已投递')).toBeOnTheScreen()
  expect(jobs.markApplied).toHaveBeenCalledWith(7)
  expect(client.getQueryData<{ applied: boolean }[]>(jobKeys.mine(1))?.[0].applied).toBe(true)

  await user.press(screen.getByRole('button', { name: '取消已投递' }))
  expect(await screen.findByText('已取消已投递标记')).toBeOnTheScreen()
  expect(jobs.clearApplied).toHaveBeenCalledWith(7)
})

it('deletes the record after confirmation and leaves the screen', async () => {
  const client = createTestQueryClient()
  client.setQueryData(jobKeys.mine(1), [personalJob(7), personalJob(8)])
  jobs.get.mockResolvedValue(personalJob(7))
  jobs.remove.mockResolvedValue(undefined)
  const onDeleted = jest.fn()
  await renderWithQuery(<JobDetailScreen jobId={7} onBack={jest.fn()} onDeleted={onDeleted} />, client)

  await userEvent.setup().press(await screen.findByRole('button', { name: '删除这条记录' }))
  expect(jobs.remove).toHaveBeenCalledWith(7)
  expect(onDeleted).toHaveBeenCalledTimes(1)
  expect(client.getQueryData<{ id: number }[]>(jobKeys.mine(1))?.map((job) => job.id)).toEqual([8])
})

it('says a missing record is missing, and never calls the API for an invalid id', async () => {
  jobs.get.mockRejectedValue(new ApiError('/jobs/9', 404, { message: 'Job not found' }))
  await renderWithQuery(<JobDetailScreen jobId={9} onBack={jest.fn()} onDeleted={jest.fn()} />)
  expect(await screen.findByText('找不到这条职位记录')).toBeOnTheScreen()

  jest.resetAllMocks()
  await renderWithQuery(<JobDetailScreen jobId={null} onBack={jest.fn()} onDeleted={jest.fn()} />)
  expect(screen.getAllByText('找不到这条职位记录').length).toBeGreaterThan(0)
  expect(jobs.get).not.toHaveBeenCalled()
})

it('shows a load failure with retry, distinct from not found', async () => {
  jobs.get.mockRejectedValueOnce(new ApiError('/jobs/9', null, null)).mockResolvedValueOnce(personalJob(9, { title: 'Back Again' }))
  await renderWithQuery(<JobDetailScreen jobId={9} onBack={jest.fn()} onDeleted={jest.fn()} />)
  expect(await screen.findByText('职位详情暂时无法加载')).toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
  expect(await screen.findByText('Back Again')).toBeOnTheScreen()
})
