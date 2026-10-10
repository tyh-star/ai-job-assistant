import '../src/i18n'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { catalogApi, jobsApi } from '../src/api/endpoints'
import { JobsScreen } from '../src/screens/JobsScreen'
import { catalogSummary, personalJob, searchPage } from '../test-utils/fixtures'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  catalogApi: { search: jest.fn(), cities: jest.fn(), detail: jest.fn() },
  jobsApi: { list: jest.fn() },
  dashboardApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const catalog = catalogApi as jest.Mocked<typeof catalogApi>
const jobs = jobsApi as jest.Mocked<typeof jobsApi>

async function renderJobs(tabParam?: string) {
  const onOpenCatalogJob = jest.fn()
  const onOpenJob = jest.fn()
  await renderWithQuery(<JobsScreen tabParam={tabParam} onOpenCatalogJob={onOpenCatalogJob} onOpenJob={onOpenJob} />)
  return { onOpenCatalogJob, onOpenJob }
}

beforeEach(() => {
  jest.resetAllMocks()
  jobs.list.mockResolvedValue([])
})

describe('visitor', () => {
  it('searches the public catalog without ever requesting the personal job list', async () => {
    catalog.search.mockResolvedValue(searchPage([catalogSummary('freehire:analyst', { title: 'Data Analyst' })]))
    const onBack = jest.fn()
    await renderWithQuery(<JobsScreen guest onBack={onBack} onOpenCatalogJob={jest.fn()} onOpenJob={jest.fn()} />)
    expect(await screen.findByText('Data Analyst')).toBeOnTheScreen()
    expect(screen.getByText(/正在以游客身份浏览公开职位/)).toBeOnTheScreen()
    expect(screen.queryByRole('tab', { name: '已收藏' })).not.toBeOnTheScreen()
    expect(jobs.list).not.toHaveBeenCalled()
    await userEvent.setup().press(screen.getByRole('button', { name: '返回首页' }))
    expect(onBack).toHaveBeenCalled()
  })
})

describe('catalog', () => {
  it('shows skeletons while the first search is loading, not an empty or error state', async () => {
    catalog.search.mockReturnValue(new Promise(() => {}))
    await renderJobs()
    expect(screen.getByRole('heading', { name: '职位' })).toBeOnTheScreen()
    expect(screen.getByRole('tab', { name: '职位库' })).toBeSelected()
    expect(screen.queryByText('没有找到符合条件的职位')).not.toBeOnTheScreen()
    expect(screen.queryByText('职位搜索暂时不可用')).not.toBeOnTheScreen()
  })

  it('lists real results with their tags, loads the next page, then says everything is shown', async () => {
    catalog.search
      .mockResolvedValueOnce(
        searchPage(
          [
            catalogSummary('freehire:a', { title: 'Data Analyst', salaryMin: 80000, salaryMax: 95000, currency: 'CAD' }),
            catalogSummary('freehire:b', { title: 'Support Specialist', saved: true, personalJobId: 5 }),
          ],
          { totalCount: 3, totalPages: 2, hasMore: true, size: 2 },
        ),
      )
      .mockResolvedValueOnce(
        searchPage([catalogSummary('freehire:c', { title: 'Office Coordinator', applied: true, saved: true })], {
          page: 2,
          totalCount: 3,
          totalPages: 2,
          hasMore: false,
          size: 2,
        }),
      )
    const { onOpenCatalogJob } = await renderJobs()
    const user = userEvent.setup()

    expect(await screen.findByText('Data Analyst')).toBeOnTheScreen()
    expect(screen.getByText('共 3 个职位')).toBeOnTheScreen()
    expect(screen.getByText('80,000–95,000 CAD')).toBeOnTheScreen()
    expect(screen.getAllByText('远程').length).toBeGreaterThan(0)
    // the segment label plus the badge on the saved result
    expect(screen.getAllByText('已收藏')).toHaveLength(2)

    await user.press(screen.getByRole('button', { name: '加载更多' }))
    expect(await screen.findByText('Office Coordinator')).toBeOnTheScreen()
    expect(catalog.search).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    expect(screen.getByText('已显示全部 3 个职位')).toBeOnTheScreen()
    expect(screen.getAllByText('已投递')).toHaveLength(2)

    await user.press(screen.getByRole('link', { name: 'Data Analyst, Test Company' }))
    expect(onOpenCatalogJob).toHaveBeenCalledWith('freehire:a')
  })

  it('searches with the typed keyword and the picked city', async () => {
    catalog.search.mockResolvedValue(searchPage([catalogSummary('freehire:a')]))
    catalog.cities.mockResolvedValue([{ value: 'Toronto', countryCode: 'ca' }])
    await renderJobs()
    const user = userEvent.setup()
    await screen.findByText('Catalog role freehire:a')

    await user.type(screen.getByLabelText('关键词'), 'nurse')
    await user.type(screen.getByLabelText('城市'), 'To')
    await user.press(await screen.findByRole('button', { name: 'Toronto · CA' }, { timeout: 2000 }))
    expect(catalog.cities).toHaveBeenCalledWith('To')
    await user.press(screen.getByRole('button', { name: '搜索' }))

    await waitFor(() =>
      expect(catalog.search).toHaveBeenLastCalledWith({
        q: 'nurse',
        city: 'Toronto',
        cityCountry: 'ca',
        page: 1,
        size: 20,
      }),
    )
  })

  it('keeps "no results" and "request failed" as two different states', async () => {
    catalog.search.mockResolvedValueOnce(searchPage([]))
    await renderJobs()
    expect(await screen.findByText('没有找到符合条件的职位')).toBeOnTheScreen()
    expect(screen.queryByText('职位搜索暂时不可用')).not.toBeOnTheScreen()
    expect(screen.queryByText(/共 0 个职位/)).not.toBeOnTheScreen()
  })

  it('shows the failure with the reason and retries on request', async () => {
    catalog.search
      .mockRejectedValueOnce(new ApiError('/job-catalog/search', 502, { message: 'upstream' }))
      .mockResolvedValueOnce(searchPage([catalogSummary('freehire:a', { title: 'Recovered Role' })]))
    await renderJobs()
    expect(await screen.findByText('职位搜索暂时不可用')).toBeOnTheScreen()
    expect(screen.getByText('服务暂时不可用，请稍后再试。')).toBeOnTheScreen()
    expect(screen.queryByText('没有找到符合条件的职位')).not.toBeOnTheScreen()

    await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('Recovered Role')).toBeOnTheScreen()
  })
})

describe('saved and applied', () => {
  it('splits one GET /jobs response into both lists and opens records by personal id', async () => {
    jobs.list.mockResolvedValue([
      personalJob(7, { title: 'Saved Role' }),
      personalJob(8, { title: 'Applied Role', applied: true, appliedAt: '2026-09-10T12:00:00' }),
    ])
    const { onOpenJob } = await renderJobs('saved')
    const user = userEvent.setup()

    expect(await screen.findByText('Saved Role')).toBeOnTheScreen()
    expect(screen.queryByText('Applied Role')).not.toBeOnTheScreen()

    await user.press(screen.getByRole('tab', { name: '已投递' }))
    expect(await screen.findByText('Applied Role')).toBeOnTheScreen()
    expect(screen.getByText('投递于 9月10日')).toBeOnTheScreen()
    await user.press(screen.getByRole('link', { name: 'Applied Role, Org 8' }))
    expect(onOpenJob).toHaveBeenCalledWith(8)

    expect(jobs.list).toHaveBeenCalledTimes(1)
    expect(catalog.search).not.toHaveBeenCalled()
  })

  it('shows an empty list as empty and a failed request as failed', async () => {
    await renderJobs('applied')
    expect(await screen.findByText('还没有投递记录')).toBeOnTheScreen()

    jest.resetAllMocks()
    jobs.list.mockRejectedValue(new ApiError('/jobs', null, null))
    await renderJobs('saved')
    expect(await screen.findByText('职位数据暂时无法加载')).toBeOnTheScreen()
    expect(screen.getByText('网络异常，请检查连接。')).toBeOnTheScreen()
  })
})
