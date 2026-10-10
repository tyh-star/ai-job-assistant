import '../src/i18n'
import { Alert } from 'react-native'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { catalogApi, jobsApi } from '../src/api/endpoints'
import { jobKeys } from '../src/jobs/queries'
import { CatalogJobDetailScreen } from '../src/screens/CatalogJobDetailScreen'
import { catalogDetail, personalJob } from '../test-utils/fixtures'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  catalogApi: { detail: jest.fn(), save: jest.fn(), markApplied: jest.fn(), clearApplied: jest.fn() },
  jobsApi: { list: jest.fn(), remove: jest.fn() },
  dashboardApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const catalog = catalogApi as jest.Mocked<typeof catalogApi>
const jobs = jobsApi as jest.Mocked<typeof jobsApi>
const ID = 'freehire:senior-dev'

/** Confirms every dialog by pressing its last (confirm) button. */
function confirmDialogs() {
  jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    buttons?.[buttons.length - 1]?.onPress?.()
  })
}

async function renderDetail() {
  return renderWithQuery(<CatalogJobDetailScreen catalogJobId={ID} onBack={jest.fn()} />)
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  jobs.list.mockResolvedValue([])
})

it('shows the fields the posting actually has', async () => {
  catalog.detail.mockResolvedValue(
    catalogDetail(ID, { title: 'Senior Developer', salaryMin: 80000, salaryMax: 95000, currency: 'CAD' }),
  )
  await renderDetail()
  expect(await screen.findByText('Senior Developer')).toBeOnTheScreen()
  expect(catalog.detail).toHaveBeenCalledWith(ID)
  expect(screen.getByText('80,000–95,000 CAD')).toBeOnTheScreen()
  expect(screen.getByText('远程')).toBeOnTheScreen()
  expect(screen.getByText('全职')).toBeOnTheScreen()
  expect(screen.getByText('Test description for this posting.')).toBeOnTheScreen()
})

it('saves by catalog reference, updates the button and refreshes the shared jobs cache', async () => {
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  catalog.save.mockResolvedValue({ jobId: 42, catalogJobId: ID, alreadySaved: false, job: personalJob(42) })
  const client = await renderDetail()
  const invalidate = jest.spyOn(client, 'invalidateQueries')
  const user = userEvent.setup()

  await user.press(await screen.findByRole('button', { name: '收藏职位' }))
  expect(await screen.findByRole('button', { name: '取消收藏' })).toBeOnTheScreen()
  expect(screen.getByRole('status')).toHaveTextContent('已收藏')
  expect(catalog.save).toHaveBeenCalledWith(ID)
  expect(invalidate).toHaveBeenCalledWith({ queryKey: jobKeys.mine(1) })
  expect(invalidate).toHaveBeenCalledWith({ queryKey: jobKeys.catalog(1), refetchType: 'none' })
})

it('unsaves by deleting the personal record id, never the catalog reference', async () => {
  confirmDialogs()
  catalog.detail.mockResolvedValue(catalogDetail(ID, { saved: true, personalJobId: 42 }))
  jobs.remove.mockResolvedValue(undefined)
  await renderDetail()

  await userEvent.setup().press(await screen.findByRole('button', { name: '取消收藏' }))
  expect(await screen.findByText('已取消收藏')).toBeOnTheScreen()
  expect(jobs.remove).toHaveBeenCalledWith(42)
  expect(catalog.save).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: '收藏职位' })).toBeOnTheScreen()
})

it('marks and clears applied through the catalog routes after confirmation', async () => {
  confirmDialogs()
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  catalog.markApplied.mockResolvedValue({
    jobId: 42,
    catalogJobId: ID,
    alreadySaved: false,
    job: personalJob(42, { applied: true, appliedAt: '2026-09-10T12:00:00' }),
  })
  catalog.clearApplied.mockResolvedValue({ jobId: 42, catalogJobId: ID, alreadySaved: true, job: personalJob(42) })
  await renderDetail()
  const user = userEvent.setup()

  await user.press(await screen.findByRole('button', { name: '标记已投递' }))
  expect(await screen.findByText('已标记为已投递')).toBeOnTheScreen()
  expect(catalog.markApplied).toHaveBeenCalledWith(ID)

  await user.press(screen.getByRole('button', { name: '取消已投递' }))
  expect(await screen.findByText('已取消已投递标记')).toBeOnTheScreen()
  expect(catalog.clearApplied).toHaveBeenCalledWith(ID)
  expect(screen.getByRole('button', { name: '标记已投递' })).toBeOnTheScreen()
})

it('does nothing when the confirmation is cancelled', async () => {
  jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    buttons?.[0]?.onPress?.()
  })
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  await renderDetail()
  await userEvent.setup().press(await screen.findByRole('button', { name: '标记已投递' }))
  expect(catalog.markApplied).not.toHaveBeenCalled()
})

it('ignores a second press while the first save is still running', async () => {
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  catalog.save.mockReturnValue(new Promise(() => {}))
  await renderDetail()
  const user = userEvent.setup()
  const button = await screen.findByRole('button', { name: '收藏职位' })
  await user.press(button)
  await waitFor(() => expect(screen.getByRole('button', { name: '收藏职位' })).toBeDisabled())
  await user.press(screen.getByRole('button', { name: '收藏职位' }))
  expect(catalog.save).toHaveBeenCalledTimes(1)
})

it('reports a failed action without pretending it worked', async () => {
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  catalog.save.mockRejectedValue(new ApiError(`/job-catalog/${ID}/save`, 502, { message: 'upstream' }))
  await renderDetail()
  await userEvent.setup().press(await screen.findByRole('button', { name: '收藏职位' }))
  expect(await screen.findByText('操作没有成功：服务暂时不可用，请稍后再试。')).toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '收藏职位' })).toBeOnTheScreen()
})

it('does not offer tailoring before the posting is saved', async () => {
  catalog.detail.mockResolvedValue(catalogDetail(ID))
  await renderWithQuery(<CatalogJobDetailScreen catalogJobId={ID} onBack={jest.fn()} onTailor={jest.fn()} />)
  await screen.findByRole('button', { name: '收藏职位' })
  expect(screen.queryByRole('button', { name: '按职位改写' })).not.toBeOnTheScreen()
})

it('offers tailoring with the personal job id once the posting is saved', async () => {
  const onTailor = jest.fn()
  catalog.detail.mockResolvedValue(catalogDetail(ID, { saved: true, personalJobId: 42 }))
  await renderWithQuery(<CatalogJobDetailScreen catalogJobId={ID} onBack={jest.fn()} onTailor={onTailor} />)
  await userEvent.setup().press(await screen.findByRole('button', { name: '按职位改写' }))
  expect(onTailor).toHaveBeenCalledWith(42)
})

it('shows a public posting to a visitor and asks to log in instead of saving', async () => {
  catalog.detail.mockResolvedValue(catalogDetail(ID, { title: 'Senior Developer' }))
  const onRequireLogin = jest.fn()
  await renderWithQuery(<CatalogJobDetailScreen catalogJobId={ID} onBack={jest.fn()} guest onRequireLogin={onRequireLogin} />)
  expect(await screen.findByText('Senior Developer')).toBeOnTheScreen()
  expect(screen.queryByRole('button', { name: '收藏职位' })).not.toBeOnTheScreen()
  expect(screen.queryByRole('button', { name: '按职位改写' })).not.toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('button', { name: '登录后收藏或投递' }))
  expect(onRequireLogin).toHaveBeenCalledTimes(1)
  expect(catalog.save).not.toHaveBeenCalled()
  expect(jobs.list).not.toHaveBeenCalled()
})

it('explains a posting that is gone upstream', async () => {
  catalog.detail.mockRejectedValue(new ApiError(`/job-catalog/${ID}`, 404, { message: 'JOB_DETAIL_NO_LONGER_AVAILABLE' }))
  await renderDetail()
  expect(await screen.findByText('这个职位已经失效')).toBeOnTheScreen()
})
