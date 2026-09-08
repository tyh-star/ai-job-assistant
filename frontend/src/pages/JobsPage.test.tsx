import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { endpoints } from '../api/endpoints'
import {
  analyzeTailorFit,
  clearJobApplied,
  createTailorProposal,
  getJobApplicationStats,
  importSearchResults,
  listJobs,
  markJobApplied,
  fetchNextSearchPage,
  saveSearchResult,
  searchJobs,
} from '../api/jobs'
import {
  clearJobCatalogApplied,
  getJobCatalogJob,
  markJobCatalogApplied,
  saveJobCatalogJob,
  searchCatalogCities,
  searchJobCatalog,
} from '../api/jobCatalog'
import {
  listJobSearchHistory,
  recordJobSearchHistory,
} from '../api/jobSearchHistory'
import type { JobCatalogDetail, JobCatalogSummary } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession } from '../auth/session'
import { JobsPage } from './JobsPage'

vi.mock('../api/jobs', () => ({
  listJobs: vi.fn(),
  createJob: vi.fn(),
  updateJob: vi.fn(),
  deleteJob: vi.fn(),
  markJobApplied: vi.fn(),
  clearJobApplied: vi.fn(),
  bulkDeleteJobs: vi.fn(),
  importGreenhouse: vi.fn(),
  searchJobs: vi.fn(),
  fetchNextSearchPage: vi.fn(),
  saveSearchResult: vi.fn(),
  importSearchResults: vi.fn(),
  analyzeTailorFit: vi.fn(),
  createTailorProposal: vi.fn(),
  refreshJobRequirementProfile: vi.fn(),
  getJobApplicationStats: vi.fn(),
}))

vi.mock('../api/jobSearchHistory', () => ({
  listJobSearchHistory: vi.fn(),
  recordJobSearchHistory: vi.fn(),
  deleteJobSearchHistory: vi.fn(),
  clearJobSearchHistory: vi.fn(),
}))

vi.mock('../api/jobCatalog', () => ({
  searchJobCatalog: vi.fn(),
  getJobCatalogJob: vi.fn(),
  saveJobCatalogJob: vi.fn(),
  markJobCatalogApplied: vi.fn(),
  clearJobCatalogApplied: vi.fn(),
  searchCatalogCities: vi.fn(),
}))

const activeJob: JobCatalogSummary = {
  id: '501',
  title: 'Java Developer',
  companyName: 'Acme',
  locationText: 'Toronto, ON',
  countryCode: 'CA',
  remoteType: 'HYBRID',
  employmentType: 'FULL_TIME',
  salaryMin: null,
  salaryMax: null,
  currency: null,
  canonicalUrl: 'https://boards.greenhouse.io/acme/jobs/501',
  source: 'GREENHOUSE',
  postedAt: '2026-08-01T12:00:00Z',
  firstSeenAt: '2026-08-02T00:00:00Z',
  lastSeenAt: '2026-08-20T00:00:00Z',
  status: 'ACTIVE',
  saved: false,
  personalJobId: null,
  applied: false,
  appliedAt: null,
  cityMatch: null,
}

const expiredJob: JobCatalogSummary = {
  ...activeJob,
  id: '502',
  title: 'Platform Engineer',
  status: 'EXPIRED',
}

function catalogDetailFor(
  summary: JobCatalogSummary,
  overrides: Partial<JobCatalogDetail> = {},
): JobCatalogDetail {
  const { cityMatch: _cityMatch, ...rest } = summary
  return {
    ...rest,
    descriptionText: `${summary.title} full job description.`,
    descriptionHtml: `<p>${summary.title} full job description.</p>`,
    city: null,
    region: null,
    applyUrl: 'https://boards.greenhouse.io/acme/jobs/501',
    ...overrides,
  }
}

function pageOf(results: JobCatalogSummary[], overrides: Record<string, unknown> = {}) {
  return {
    data: {
      page: 1,
      size: 10,
      totalCount: results.length,
      totalPages: results.length === 0 ? 0 : 1,
      hasMore: false,
      results,
      ...overrides,
    },
  } as never
}

/** Seeds a logged-in session before render -- the login boundary itself is covered by
 *  the dedicated 'anonymous visitor' describe block below, which renders without this. */
function signIn() {
  saveSession('test-token', { userId: 9, name: 'Test User', email: 'test@example.com', role: 'USER' })
}

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter>
        <AuthProvider>
          <JobsPage />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

/**
 * The catalog panel and the manual "add a job" form share label text such as
 * 地点, so every catalog assertion is scoped to the catalog section.
 */
function catalog(headingName = '全球公开职位') {
  const heading = screen.getByRole('heading', { name: headingName })
  const section = heading.closest('section')
  if (!section) {
    throw new Error('catalog section not found')
  }
  return within(section)
}

async function expandCatalogJob(title: string, headingName = '全球公开职位') {
  const user = userEvent.setup()
  const item = (await catalog(headingName).findByText(title)).closest('li')
  if (!item) {
    throw new Error(`job row not found: ${title}`)
  }
  const viewDetails = headingName === 'Global public jobs' ? /View details/ : /查看详情/
  await user.click(within(item).getByRole('button', { name: viewDetails }))
  return { user, item: item as HTMLElement }
}

async function renderAndSettle() {
  renderPage()
  await waitFor(() => expect(listJobs).toHaveBeenCalled())
  await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
}

describe('JobsPage catalog search', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    signIn()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob]))
    vi.mocked(saveJobCatalogJob).mockResolvedValue({
      data: { jobId: 101, catalogJobId: '501', alreadySaved: false, job: null },
    } as never)
    vi.mocked(markJobCatalogApplied).mockResolvedValue({
      data: {
        jobId: 101,
        catalogJobId: '501',
        alreadySaved: false,
        job: { id: 101, applied: true, appliedAt: '2026-09-04T12:00:00Z' },
      },
    } as never)
    vi.mocked(clearJobCatalogApplied).mockResolvedValue({
      data: { jobId: 101, catalogJobId: '501', alreadySaved: true, job: { id: 101, applied: false, appliedAt: null } },
    } as never)
    vi.mocked(searchCatalogCities).mockResolvedValue({
      data: [{ value: 'Toronto', countryCode: 'ca' }],
    } as never)
    vi.mocked(listJobSearchHistory).mockResolvedValue({ data: [] } as never)
    vi.mocked(recordJobSearchHistory).mockResolvedValue({} as never)
    vi.mocked(getJobCatalogJob).mockImplementation(async (id: string) => ({
      data: catalogDetailFor(id === expiredJob.id ? expiredJob : { ...activeJob, id }),
    }) as never)
    vi.mocked(getJobApplicationStats).mockResolvedValue({
      data: {
        todayCount: 0,
        last7DaysCount: 0,
        last30DaysCount: 0,
        daily: [],
        totalApplied: 0,
      },
    } as never)
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('reads the local catalog and never calls the external job search', async () => {
    await renderAndSettle()

    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
    expect(catalog().getByText('Acme')).toBeInTheDocument()
    expect(catalog().getByText(/Toronto, ON/)).toBeInTheDocument()
    expect(catalog().queryByText(/GREENHOUSE/)).not.toBeInTheDocument()
    expect(catalog().queryByText(/full job description/)).not.toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
    expect(searchJobs).not.toHaveBeenCalled()
    expect(fetchNextSearchPage).not.toHaveBeenCalled()
    expect(importSearchResults).not.toHaveBeenCalled()
    expect(saveSearchResult).not.toHaveBeenCalled()
    expect(analyzeTailorFit).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
  })

  it('targets relative catalog paths with a single /api/v1 prefix and no hardcoded host', async () => {
    await renderAndSettle()

    for (const path of [
      endpoints.jobCatalogSearch,
      endpoints.jobCatalogJob(501),
      endpoints.jobCatalogSave(501),
    ]) {
      expect(path.startsWith('/')).toBe(true)
      expect(path).not.toContain('/api/v1')
      expect(path).not.toMatch(/localhost|127\.0\.0\.1|\d+\.\d+\.\d+\.\d+|https?:/)
    }
  })

  it('sends keyword, country and other filter parameters to the catalog', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('关键词'), 'java')
    await user.selectOptions(panel.getByLabelText('国家'), 'CA')
    await user.selectOptions(panel.getByLabelText('工作方式'), 'REMOTE')
    await user.selectOptions(panel.getByLabelText('职位类型'), 'FULL_TIME')
    await user.selectOptions(panel.getByLabelText('发布时间'), '7')
    await user.click(panel.getByRole('button', { name: '搜索' }))

    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchJobCatalog).mock.calls[1][0]).toMatchObject({
      q: 'java',
      city: '',
      country: 'CA',
      remoteType: 'REMOTE',
      employmentType: 'FULL_TIME',
      postedWithinDays: 7,
      page: 1,
      size: 10,
    })
    expect(searchJobs).not.toHaveBeenCalled()
  })

  // ---------------------------------------------------------------------------
  // city facet: the real fix for the free-text "地点" box that always 400ed
  // ---------------------------------------------------------------------------

  it('there is no free-text location box that leads to a submit-time 400 -- it is a city search instead', async () => {
    await renderAndSettle()
    // The old control is gone entirely, not just relabelled -- there is nothing left
    // that looks answerable but always fails once submitted.
    expect(catalog().queryByLabelText('地点')).not.toBeInTheDocument()
    expect(catalog().getByLabelText('城市')).toBeInTheDocument()
  })

  it('selecting a Toronto suggestion sends city=Toronto AND cityCountry=CA, never country, never folded into keyword', async () => {
    // Exact shape confirmed live against real freehire.me (2026-09-06, via
    // RealFreeHireTorontoInvestigation): city="Toronto", cityCountry="CA", country=null.
    // cityCountry must reach our OWN backend as real display/validation metadata -- it
    // must never be silently dropped just because it is a newer field, and it must
    // never masquerade as `country` (which the backend forwards to FreeHire as a real
    // filter FreeHire would OR against cities -- see buildSearchParams' javadoc).
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('关键词'), 'java')
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await panel.findByRole('button', { name: /Toronto/ }))
    await user.click(panel.getByRole('button', { name: '搜索' }))

    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    const sent = vi.mocked(searchJobCatalog).mock.calls[1][0]
    expect(sent).toMatchObject({ q: 'java', city: 'Toronto', cityCountry: 'CA' })
    expect(sent.country).toBeFalsy()
    expect(sent.q).not.toContain('Toronto')
  })

  it('selecting a city shows that suggestion\'s own country, but the country select is never disabled', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.selectOptions(panel.getByLabelText('国家'), 'US')
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await panel.findByRole('button', { name: /Toronto/ }))

    // Toronto's own suggestion country (ca) is shown -- not blank, not the previously
    // selected US -- and the select stays a real, always-usable control.
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('CA')
    expect((panel.getByLabelText('国家') as HTMLSelectElement).disabled).toBe(false)
  })

  it('never sends country alongside city, even though the country select displays the suggestion\'s country', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await panel.findByRole('button', { name: /Toronto/ }))
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('CA')

    await user.click(panel.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    const sent = vi.mocked(searchJobCatalog).mock.calls[1][0]
    expect(sent.city).toBe('Toronto')
    expect(sent.country).toBeUndefined()
  })

  it('manually changing the country while a city is selected clears the city and switches to a country search', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await panel.findByRole('button', { name: /Toronto/ }))
    expect((panel.getByLabelText('城市') as HTMLInputElement).value).toBe('Toronto')

    await user.selectOptions(panel.getByLabelText('国家'), 'US')
    expect((panel.getByLabelText('城市') as HTMLInputElement).value).toBe('')
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('US')

    await user.click(panel.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    const sent = vi.mocked(searchJobCatalog).mock.calls[1][0]
    expect(sent.country).toBe('US')
    expect(sent.city).toBe('')
  })

  it('clearing a selected city resets the country select back to "any country"', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await panel.findByRole('button', { name: /Toronto/ }))
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('CA')

    await user.click(panel.getByRole('button', { name: '清除已选城市' }))
    expect((panel.getByLabelText('城市') as HTMLInputElement).value).toBe('')
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('')
    expect((panel.getByLabelText('国家') as HTMLSelectElement).disabled).toBe(false)

    await user.selectOptions(panel.getByLabelText('国家'), 'US')
    expect((panel.getByLabelText('国家') as HTMLSelectElement).value).toBe('US')
    expect((panel.getByLabelText('城市') as HTMLInputElement).value).toBe('')
  })

  it('selecting a country first scopes city suggestions to that country', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.selectOptions(panel.getByLabelText('国家'), 'CA')
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalledWith('Tor', 'CA'))
  })

  it('degrades gracefully when the city lookup fails, without blocking keyword+country search', async () => {
    const user = userEvent.setup()
    vi.mocked(searchCatalogCities).mockRejectedValue({ response: { status: 502, data: {} } })
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('城市'), 'Tor')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    expect(await panel.findByText(/城市查询暂时不可用/)).toBeInTheDocument()

    // The rest of the form is unaffected by the city lookup failing.
    await user.selectOptions(panel.getByLabelText('国家'), 'CA')
    await user.click(panel.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchJobCatalog).mock.calls[1][0]).toMatchObject({ country: 'CA' })
  })

  it('always requests a fixed page size of 10, matching FreeHire page numbering', async () => {
    await renderAndSettle()
    expect(vi.mocked(searchJobCatalog).mock.calls[0][0]).toMatchObject({ page: 1, size: 10 })
  })

  it('never auto-marks applied on the click -- only after returning and confirming', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('link', { name: '查看原始职位' }))
    // The click itself must never call the API -- only an explicit confirmation does.
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(catalog().queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
  })

  it('prompts to confirm applied once the user returns to the tab, then can be revoked', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('link', { name: '查看原始职位' }))
    window.dispatchEvent(new Event('focus'))

    expect(await catalog().findByText('你是否已经提交申请？')).toBeInTheDocument()
    await user.click(catalog().getByRole('button', { name: '是，标记为已投递' }))

    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalledWith('501'))
    // Moves to 已投递职位 -- the tab itself reflects the new state immediately, not
    // just the card.
    expect(await screen.findByRole('tab', { name: /已投递/ })).toHaveAttribute('aria-selected', 'true')

    await user.click(screen.getByRole('tab', { name: '查找职位' }))
    expect(await catalog().findByText('已投递')).toBeInTheDocument()
    expect(catalog().queryByText('你是否已经提交申请？')).not.toBeInTheDocument()

    await user.click(catalog().getByRole('button', { name: '撤销已投递标记' }))
    await waitFor(() => expect(clearJobCatalogApplied).toHaveBeenCalledWith('501'))
    // Moves back to 已收藏职位.
    expect(await screen.findByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('declining the confirm prompt marks nothing', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('link', { name: '查看原始职位' }))
    window.dispatchEvent(new Event('focus'))
    expect(await catalog().findByText('你是否已经提交申请？')).toBeInTheDocument()

    await user.click(catalog().getByRole('button', { name: '还没有' }))
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(catalog().queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
  })


  it('pages forward and back on the server', async () => {
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await renderAndSettle()
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([expiredJob], { page: 2, totalCount: 40, totalPages: 2, hasMore: false }),
    )
    await user.click(catalog().getByRole('button', { name: '下一页' }))

    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchJobCatalog).mock.calls[1][0].page).toBe(2)
    expect(await catalog().findByText('Platform Engineer')).toBeInTheDocument()

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await user.click(catalog().getByRole('button', { name: '上一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(3))
    expect(vi.mocked(searchJobCatalog).mock.calls[2][0].page).toBe(1)
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
  })

  it('honestly labels a genuine multi-location mismatch instead of hiding the posting\'s own location', async () => {
    // Shaped after the real evidence captured live against freehire.me (2026-09-06): a
    // Toronto search returning a posting whose own location/country is US, because that
    // posting's cities array also lists Toronto among several remote-eligible cities.
    const confirmedMatch: JobCatalogSummary = {
      ...activeJob,
      id: 'freehire:toronto-clean',
      title: 'Data Engineer',
      locationText: 'Toronto, Canada',
      countryCode: 'CA',
      cityMatch: { city: 'Toronto', countryCode: 'CA', countryConfirmed: true },
    }
    const unconfirmedMismatch: JobCatalogSummary = {
      ...activeJob,
      id: 'freehire:toronto-multi-us',
      title: 'Brand & Marketing Manager',
      locationText: 'Remote (US)',
      countryCode: 'US',
      cityMatch: { city: 'Toronto', countryCode: 'CA', countryConfirmed: false },
    }
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([confirmedMatch, unconfirmedMismatch]))
    await renderAndSettle()

    // Both jobs have a cityMatch -- two "匹配地点：" labels, one per card.
    expect(await catalog().findAllByText('匹配地点：')).toHaveLength(2)
    // The country name itself may render in English or Chinese depending on the
    // runtime's ICU data (see countryLabel's Intl.DisplayNames fallback) -- what this
    // asserts is the confirmed-match SHAPE ("Toronto, <name>（CA）"), not one exact
    // localization of "Canada".
    expect(catalog().getByText(/^Toronto, .+（CA）$/)).toBeInTheDocument()
    expect(catalog().getByText('Toronto（按名称匹配，未确认国家）')).toBeInTheDocument()
    // The posting's own location must never be hidden or overwritten by the match label.
    expect(catalog().getAllByText('职位原始地点：')).toHaveLength(2)
    expect(catalog().getByText(/Remote \(US\)/)).toBeInTheDocument()
  })

  it('shows the honest upstream-estimate result count when a city filter is active', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { totalCount: 15036 }),
    )
    const user = userEvent.setup()
    await renderAndSettle()
    // No city selected yet: the plain "已收录" wording.
    expect(catalog().getByText(/已收录 \d+ 个职位/)).toBeInTheDocument()

    await user.type(catalog().getByRole('combobox', { name: '城市' }), 'To')
    await waitFor(() => expect(searchCatalogCities).toHaveBeenCalled())
    await user.click(await catalog().findByRole('button', { name: /Toronto/ }))
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob], { totalCount: 15036 }))
    await user.click(catalog().getByRole('button', { name: '搜索' }))

    await waitFor(() => expect(catalog().getByText(/找到的职位结果/)).toBeInTheDocument())
    expect(catalog().queryByText(/已收录 \d+ 个职位/)).not.toBeInTheDocument()
  })

  it('always pages in groups of 10, never a different size', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 4, hasMore: true }),
    )
    await renderAndSettle()
    expect(vi.mocked(searchJobCatalog).mock.calls[0][0].size).toBe(10)

    await userEvent.setup().click(catalog().getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchJobCatalog).mock.calls[1][0].size).toBe(10)
  })

  it('scrolls to the results heading exactly once after a successful next-page load', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await renderAndSettle()
    // The initial on-mount load must never scroll -- there is nothing to page back to yet.
    expect(scrollSpy).not.toHaveBeenCalled()

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([expiredJob], { page: 2, totalCount: 40, totalPages: 2, hasMore: false }),
    )
    await user.click(catalog().getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(1))
    expect(scrollSpy.mock.calls[0][0]).toMatchObject({ behavior: 'smooth', block: 'start' })
  })

  it('scrolls to the results heading exactly once after a successful previous-page load', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([expiredJob], { page: 2, totalCount: 40, totalPages: 2, hasMore: false }),
    )
    await renderAndSettle()
    await waitFor(() => expect(catalog().getByRole('button', { name: '上一页' })).not.toBeDisabled())
    expect(scrollSpy).not.toHaveBeenCalled()

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await user.click(catalog().getByRole('button', { name: '上一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(1))
  })

  it('respects prefers-reduced-motion by scrolling without animation', async () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList)
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await renderAndSettle()

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([expiredJob], { page: 2, totalCount: 40, totalPages: 2, hasMore: false }),
    )
    await user.click(catalog().getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(1))
    expect(scrollSpy.mock.calls[0][0]).toMatchObject({ behavior: 'auto', block: 'start' })
  })

  it('never scrolls when a page-forward request fails', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 40, totalPages: 2, hasMore: true }),
    )
    await renderAndSettle()

    vi.mocked(searchJobCatalog).mockRejectedValue({ response: { status: 500, data: {} } })
    await user.click(catalog().getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(await catalog().findByRole('alert')).toBeInTheDocument()
    expect(scrollSpy).not.toHaveBeenCalled()
  })

  it('shows an empty state without falling back to another provider', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([]))
    await renderAndSettle()

    expect(await catalog().findByText(/没有符合条件的已收录职位/)).toBeInTheDocument()
    expect(searchJobs).not.toHaveBeenCalled()
  })

  it('surfaces a local error instead of silently retrying elsewhere', async () => {
    vi.mocked(searchJobCatalog).mockRejectedValue({
      response: { status: 500, data: { message: 'boom' } },
    })
    await renderAndSettle()

    const alert = await catalog().findByRole('alert')
    expect(alert).toHaveTextContent('请求失败')
    expect(searchJobs).not.toHaveBeenCalled()
  })

  it('shows the search source as temporarily unavailable, not a generic failure', async () => {
    vi.mocked(searchJobCatalog).mockRejectedValue({
      response: { status: 502, data: { message: 'Job search is temporarily unavailable.' } },
    })
    await renderAndSettle()

    const alert = await catalog().findByRole('alert')
    expect(alert).toHaveTextContent('职位搜索暂时不可用')
  })

  it('shows a rate-limited status distinctly, not a generic failure', async () => {
    vi.mocked(searchJobCatalog).mockRejectedValue({
      response: { status: 429, data: { message: 'Search is temporarily busy. Please try again shortly.' } },
    })
    await renderAndSettle()

    const alert = await catalog().findByRole('alert')
    expect(alert).toHaveTextContent('频率限制')
  })

  it('a stale free-text location value from a direct API call shows a clear message, not a generic one', async () => {
    // The UI itself no longer offers a free-text location box (see the city-facet
    // tests above), so a normal user cannot trigger this anymore. This proves the
    // backend's defense-in-depth rejection (for any OTHER caller: an old cached
    // frontend bundle, a direct API call) still surfaces as a clear, specific message
    // rather than a generic failure if it is ever hit.
    vi.mocked(searchJobCatalog).mockRejectedValue({
      response: {
        status: 400,
        data: {
          message:
            'location is not supported as a text filter by the current job search provider; use city or country to filter by location',
        },
      },
    })
    await renderAndSettle()

    const alert = await catalog().findByRole('alert')
    expect(alert).toHaveTextContent('请改用国家筛选项')
  })

  it('saves a posting, exposes the personal job id, and blocks a second save', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('button', { name: '收藏职位' }))

    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith('501'))
    // Moves to 已收藏职位 immediately, no page reload needed.
    expect(await screen.findByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('tab', { name: '查找职位' }))

    expect(await catalog().findByText('已收藏')).toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(catalog().getByRole('link', { name: '分析职位匹配' })).toHaveAttribute(
      'href',
      '/tailor?jobId=101',
    )
    expect(catalog().getByRole('link', { name: '生成针对性建议' })).toHaveAttribute(
      'href',
      '/tailor?jobId=101',
    )
    // Saving copies the posting into the user's own jobs, so the list reloads.
    await waitFor(() => expect(listJobs).toHaveBeenCalledTimes(2))
    expect(saveJobCatalogJob).toHaveBeenCalledTimes(1)
  })

  it('a posting whose upstream detail 404s never shows the generic save-failed message', async () => {
    // The real scenario (confirmed live against freehire.me): a posting still shown in
    // search results, but its own detail lookup 404s by the time save is clicked. Must
    // never say "收藏职位失败，请重试" -- retrying can never help a genuinely gone
    // posting -- and the card must be marked so the user can't keep trying.
    vi.mocked(saveJobCatalogJob).mockRejectedValue({
      response: { status: 404, data: { message: 'JOB_DETAIL_NO_LONGER_AVAILABLE' } },
    })
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('button', { name: '收藏职位' }))
    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith('501'))

    expect(await catalog().findByText('详情不可用')).toBeInTheDocument()
    expect(catalog().queryByText('收藏职位失败，请重试。')).not.toBeInTheDocument()
    expect(await catalog().findByText(/该职位的详情已经失效/)).toBeInTheDocument()
    expect(catalog().getByRole('button', { name: '收藏职位' })).toBeDisabled()
    // A safe original-listing link, if there is one, must still be usable.
    expect(catalog().getByRole('link', { name: '查看原始职位' })).toBeInTheDocument()
  })

  it('a confirm-applied attempt whose upstream detail 404s gets the same specific marker', async () => {
    vi.mocked(markJobCatalogApplied).mockRejectedValue({
      response: { status: 404, data: { message: 'JOB_DETAIL_NO_LONGER_AVAILABLE' } },
    })
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('link', { name: '查看原始职位' }))
    window.dispatchEvent(new Event('focus'))
    await user.click(await catalog().findByRole('button', { name: '是，标记为已投递' }))

    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalledWith('501'))
    expect(await catalog().findByText('详情不可用')).toBeInTheDocument()
    expect(catalog().queryByText('更新投递状态失败，请重试。')).not.toBeInTheDocument()
  })

  it('marks a job already saved when the catalog says so', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([{ ...activeJob, saved: true, personalJobId: 77 }]),
    )
    await renderAndSettle()

    expect(await catalog().findByText('已收藏')).toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '分析职位匹配' })).not.toBeInTheDocument()
    await expandCatalogJob('Java Developer')
    expect(catalog().getByRole('link', { name: '分析职位匹配' })).toHaveAttribute(
      'href',
      '/tailor?jobId=77',
    )
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
  })

  it('labels postings that are no longer listed', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([expiredJob]))
    await renderAndSettle()

    const item = (await catalog().findByText('Platform Engineer')).closest('li')
    expect(item).not.toBeNull()
    expect(within(item as HTMLElement).getByText('已下线')).toBeInTheDocument()
  })

  it('does not offer an employment type FreeHire has no equivalent for', async () => {
    await renderAndSettle()

    const select = catalog().getByLabelText('职位类型') as HTMLSelectElement
    const offered = Array.from(select.options).map((option) => option.value)
    expect(offered).not.toContain('TEMPORARY')
    expect(offered).not.toContain('OTHER')
  })

  it('translates the catalog panel without claiming full global coverage', async () => {
    await renderAndSettle()
    expect(screen.getByRole('heading', { name: '全球公开职位' })).toBeInTheDocument()
    expect(screen.queryByText(/所有全球职位/)).not.toBeInTheDocument()

    await i18n.changeLanguage('en')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Global public jobs' })).toBeInTheDocument(),
    )
    expect(catalog('Global public jobs').getByRole('button', { name: 'Search' })).toBeInTheDocument()
    expect(screen.queryByText(/all jobs worldwide/i)).not.toBeInTheDocument()
  })

  it('renders the three tabs correctly in both Chinese and English', async () => {
    await renderAndSettle()
    expect(screen.getByRole('tab', { name: '查找职位' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /已收藏/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /已投递/ })).toBeInTheDocument()

    await i18n.changeLanguage('en')
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Find Jobs' })).toBeInTheDocument())
    expect(screen.getByRole('tab', { name: /Favorites/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Applied/ })).toBeInTheDocument()
  })

  it('the manual-add-job form only ever appears on the 已收藏 tab', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    // 查找职位 (default tab): no manual-add toggle at all.
    expect(screen.queryByRole('button', { name: '手动添加职位' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /已投递/ }))
    expect(screen.queryByRole('button', { name: '手动添加职位' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(await screen.findByRole('button', { name: '手动添加职位' })).toBeInTheDocument()
    // Collapsed by default -- the form itself is not shown until toggled.
    expect(screen.queryByRole('heading', { name: '添加职位' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '手动添加职位' }))
    expect(screen.getByRole('heading', { name: '添加职位' })).toBeInTheDocument()
  })

  it('the three tabs stay on one flex-wrapping row, never a fixed width that could overflow', async () => {
    // A real viewport/zoom check needs an actual browser (jsdom does no layout), so this
    // asserts the actual defensive property instead: the tablist wraps (flex-wrap) and
    // no tab carries a fixed/min width that would force horizontal scrolling at 375px or
    // 200% zoom -- both of which force wrapping instead of a fixed-width overflow.
    await renderAndSettle()
    const tablist = screen.getByRole('tablist')
    expect(tablist.className).toMatch(/flex-wrap/)
    for (const tab of screen.getAllByRole('tab')) {
      expect(tab.className).not.toMatch(/\bw-\d|\bmin-w-\[|\bwhitespace-nowrap\b/)
    }
  })

  it('shows 10 compact rows with no JD, no apply link and no favorite actions', async () => {
    const ten = Array.from({ length: 10 }, (_, index) => ({
      ...activeJob,
      id: `job-${index + 1}`,
      title: `Compact Job ${index + 1}`,
    }))
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf(ten, { totalCount: 10 }))
    await renderAndSettle()

    expect(catalog().getAllByRole('button', { name: /查看详情/ })).toHaveLength(10)
    expect(catalog().queryByText(/full job description/)).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '查看原始职位' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
    expect(searchJobCatalog).toHaveBeenCalledTimes(1)
  })

  it('loads one job detail on first expand and reuses the in-memory cache after collapse', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    expect(getJobCatalogJob).not.toHaveBeenCalled()

    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
    expect(getJobCatalogJob).toHaveBeenCalledWith('501')
    expect(searchJobCatalog).toHaveBeenCalledTimes(1)

    await user.click(catalog().getByRole('button', { name: '收起详情' }))
    expect(catalog().queryByText('Java Developer full job description.')).not.toBeInTheDocument()

    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
  })

  it('keeps only one row expanded at a time', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob, expiredJob]))
    await renderAndSettle()

    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()

    await expandCatalogJob('Platform Engineer')
    expect(await catalog().findByText('Platform Engineer full job description.')).toBeInTheDocument()
    expect(catalog().queryByText('Java Developer full job description.')).not.toBeInTheDocument()

    const javaItem = catalog().getByText('Java Developer').closest('li') as HTMLElement
    const platItem = catalog().getByText('Platform Engineer').closest('li') as HTMLElement
    expect(within(javaItem).getByRole('button', { name: /查看详情/ })).toHaveAttribute('aria-expanded', 'false')
    expect(within(platItem).getByRole('button', { name: /查看详情/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('ignores a slow first-job response after the second job is already open', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob, expiredJob]))
    let resolveFirst: ((value: { data: JobCatalogDetail }) => void) | undefined
    vi.mocked(getJobCatalogJob).mockImplementation((id: string) => {
      if (id === '501') {
        return new Promise((resolve) => {
          resolveFirst = resolve
        })
      }
      return Promise.resolve({ data: catalogDetailFor(expiredJob) })
    })
    const user = userEvent.setup()
    await renderAndSettle()

    const javaItem = (await catalog().findByText('Java Developer')).closest('li') as HTMLElement
    const platItem = catalog().getByText('Platform Engineer').closest('li') as HTMLElement
    await user.click(within(javaItem).getByRole('button', { name: /查看详情/ }))
    await user.click(within(platItem).getByRole('button', { name: /查看详情/ }))

    expect(await within(platItem).findByText('Platform Engineer full job description.')).toBeInTheDocument()
    resolveFirst?.({ data: catalogDetailFor(activeJob) })
    await waitFor(() => expect(getJobCatalogJob).toHaveBeenCalledTimes(2))
    expect(within(javaItem).queryByText('Java Developer full job description.')).not.toBeInTheDocument()
    expect(within(platItem).getByText('Platform Engineer full job description.')).toBeInTheDocument()
    expect(within(javaItem).getByRole('button', { name: /查看详情/ })).toHaveAttribute('aria-expanded', 'false')
    expect(within(platItem).getByRole('button', { name: /查看详情/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('does not fire a second detail request when the same row is clicked twice quickly', async () => {
    let started = 0
    vi.mocked(getJobCatalogJob).mockImplementation(
      () =>
        new Promise((resolve) => {
          started += 1
          window.setTimeout(() => resolve({ data: catalogDetailFor(activeJob) }), 30)
        }),
    )
    const user = userEvent.setup()
    await renderAndSettle()
    const toggle = (await catalog().findByText('Java Developer'))
      .closest('li')!
      .querySelector('button[aria-controls]') as HTMLButtonElement
    await user.click(toggle)
    await user.click(toggle)
    await waitFor(() => expect(started).toBe(1))
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
  })

  it('collapses the open row and drops the detail cache when filters or page change', async () => {
    const user = userEvent.setup()
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 20, totalPages: 2, hasMore: true }),
    )
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([expiredJob], { page: 2, totalCount: 20, totalPages: 2, hasMore: false }),
    )
    await user.click(catalog().getByRole('button', { name: '下一页' }))
    expect(await catalog().findByText('Platform Engineer')).toBeInTheDocument()
    expect(catalog().queryByText('Java Developer full job description.')).not.toBeInTheDocument()
    expect(catalog().getByRole('button', { name: /查看详情/ })).toHaveAttribute('aria-expanded', 'false')

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { page: 1, totalCount: 20, totalPages: 2, hasMore: true }),
    )
    await user.click(catalog().getByRole('button', { name: '上一页' }))
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(2)
  })

  it('shows a per-row 404 without clearing other search results', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob, expiredJob]))
    vi.mocked(getJobCatalogJob).mockImplementation((id: string) => {
      if (id === '501') {
        return Promise.reject({ response: { status: 404, data: { message: 'not found' } } })
      }
      return Promise.resolve({ data: catalogDetailFor(expiredJob) })
    })
    await renderAndSettle()

    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('该职位详情已经失效，可能已经下架')).toBeInTheDocument()
    expect(catalog().getByText('Java Developer')).toBeInTheDocument()
    expect(catalog().getByText('Platform Engineer')).toBeInTheDocument()

    await expandCatalogJob('Platform Engineer')
    expect(await catalog().findByText('Platform Engineer full job description.')).toBeInTheDocument()
    expect(catalog().getByText('Java Developer')).toBeInTheDocument()
    expect(catalog().queryByText('该职位详情已经失效，可能已经下架')).not.toBeInTheDocument()
  })

  it('maps 429 Retry-After, 502 and timeout to per-row messages and retries the request', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      response: { status: 429, headers: { 'retry-after': '30' }, data: {} },
    })
    await expandCatalogJob('Java Developer')
    expect(await catalog().findByRole('alert')).toHaveTextContent(/过于频繁/)
    expect(catalog().getByRole('alert')).toHaveTextContent('30')

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      response: { status: 502, data: { message: 'bad gateway' } },
    })
    await user.click(catalog().getByRole('button', { name: '重试' }))
    expect(await catalog().findByText('职位详情暂时不可用，请稍后再试。')).toBeInTheDocument()

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      code: 'ECONNABORTED',
      message: 'timeout of 10000ms exceeded',
    })
    await user.click(catalog().getByRole('button', { name: '重试' }))
    expect(await catalog().findByText('职位详情暂时不可用，请稍后再试。')).toBeInTheDocument()

    vi.mocked(getJobCatalogJob).mockResolvedValueOnce({ data: catalogDetailFor(activeJob) } as never)
    await user.click(catalog().getByRole('button', { name: '重试' }))
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(4)
  })

  it('updates compact saved and applied badges as soon as those actions succeed', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    await user.click(await catalog().findByRole('button', { name: '收藏职位' }))
    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith('501'))
    expect(await screen.findByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('tab', { name: '查找职位' }))
    expect(await catalog().findByText('已收藏')).toBeInTheDocument()

    await user.click(await catalog().findByRole('link', { name: '查看原始职位' }))
    window.dispatchEvent(new Event('focus'))
    await user.click(await catalog().findByRole('button', { name: '是，标记为已投递' }))
    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalledWith('501'))
    await user.click(screen.getByRole('tab', { name: '查找职位' }))
    expect(await catalog().findByText('已投递')).toBeInTheDocument()
    expect(catalog().getByText('已收藏')).toBeInTheDocument()
  })

  it('does not write search history when a job detail is opened', async () => {
    await renderAndSettle()
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
  })

  it('toggles the accordion with Enter, Space, aria-expanded and aria-controls', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    const toggle = (await catalog().findByText('Java Developer'))
      .closest('li')!
      .querySelector('button[aria-controls]') as HTMLButtonElement
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const panelId = toggle.getAttribute('aria-controls')
    expect(panelId).toBeTruthy()
    expect(document.getElementById(panelId as string)).not.toBeNull()

    toggle.focus()
    await user.keyboard('{Enter}')
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'))
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()

    await user.keyboard(' ')
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'))
    expect(catalog().queryByText('Java Developer full job description.')).not.toBeInTheDocument()
  })

  it('wraps long job descriptions instead of allowing a horizontal overflow class', async () => {
    vi.mocked(getJobCatalogJob).mockResolvedValue({
      data: catalogDetailFor(activeJob, {
        descriptionText: 'LongJD '.repeat(80),
      }),
    } as never)
    await renderAndSettle()
    await expandCatalogJob('Java Developer')
    const description = await catalog().findByText(/LongJD/)
    expect(description.className).toMatch(/whitespace-pre-wrap/)
    expect(description.className).toMatch(/break-words/)
    expect(description.className).toMatch(/overflow-hidden/)
    expect(description.className).toMatch(/min-w-0/)
    const row = catalog().getByText('Java Developer').closest('li') as HTMLElement
    expect(row.className).toMatch(/min-w-0/)
    expect(row.className).toMatch(/overflow-hidden/)
  })

  it('translates compact and detail catalog copy in English', async () => {
    await i18n.changeLanguage('en')
    await renderAndSettle()
    const panel = catalog('Global public jobs')
    expect(panel.getByRole('button', { name: /View details/ })).toBeInTheDocument()
    await expandCatalogJob('Java Developer', 'Global public jobs')
    expect(await panel.findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(panel.getByRole('button', { name: 'Hide details' })).toBeInTheDocument()
    expect(panel.getByRole('button', { name: 'Add to favorites' })).toBeInTheDocument()
    expect(panel.getByRole('link', { name: 'Open original job' })).toBeInTheDocument()
  })
})

function renderAnonymous() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter>
        <AuthProvider>
          <JobsPage />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('JobsPage for an anonymous visitor (not logged in)', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob]))
    vi.mocked(getJobCatalogJob).mockResolvedValue({ data: catalogDetailFor(activeJob) } as never)
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('can search, expand a detail, and still must log in to favorite', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()

    await expandCatalogJob('Java Developer')
    expect(await catalog().findByText('Java Developer full job description.')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
  })

  it('never fetches the personal jobs list for a visitor who is not logged in', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await screen.findByText('Java Developer')
    expect(listJobs).not.toHaveBeenCalled()
  })

  it('clicking 已收藏/已投递 as an anonymous visitor never fetches personal jobs', async () => {
    // Both tabs redirect toward /login instead of ever switching -- see onTabClick.
    // Because renderAnonymous() mounts no <Routes>, navigate('/login') here is a
    // silent no-op on screen (same precedent as "clicking save redirects toward
    // login" below), so what's actually observable is that the personal-jobs UI
    // (the manual-add toggle, any table row) never appears and listJobs is never
    // called -- not a login page that this harness has no route to render.
    const user = userEvent.setup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())

    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(listJobs).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '手动添加职位' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /已投递/ }))
    expect(listJobs).not.toHaveBeenCalled()
  })

  it('clicking save redirects toward login instead of calling the API', async () => {
    const user = userEvent.setup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await expandCatalogJob('Java Developer')
    await user.click(await screen.findByRole('button', { name: '收藏职位' }))
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
  })

  it('the apply link never sets up an applied-confirmation prompt for an anonymous visitor', async () => {
    const user = userEvent.setup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await expandCatalogJob('Java Developer')
    await user.click(await screen.findByRole('link', { name: '查看原始职位' }))
    window.dispatchEvent(new Event('focus'))
    expect(screen.queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
  })
})

/**
 * A job saved from the catalog is an ordinary personal job with a new `source` value.
 * It is listed here because it is also the compatibility surface for a rollback: the
 * previous release renders an unrecognised source through the same i18n default, so
 * these rows survive going back to it.
 */
describe('personal jobs saved from the catalog', () => {
  const savedFromCatalog = {
    id: 101,
    company: 'Acme',
    title: 'Java Developer',
    location: 'Toronto, ON',
    jobUrl: 'https://boards.greenhouse.io/acme/jobs/501',
    description: 'Build APIs with Java.',
    source: 'JOB_CATALOG',
    status: 'SAVED',
  }

  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    signIn()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([]))
    vi.mocked(listJobs).mockResolvedValue({ data: [savedFromCatalog] } as never)
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('lists a catalog-sourced job among the user’s own jobs', async () => {
    await renderAndSettle()
    await userEvent.click(screen.getByRole('tab', { name: /已收藏/ }))

    const row = (await screen.findByText('Java Developer')).closest('tr')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('Acme')).toBeInTheDocument()
    // Its own snapshot, editable and deletable like any other personal job.
    expect(within(row as HTMLElement).getByRole('button', { name: '编辑职位' })).toBeInTheDocument()
  })

  it('renders an unknown source label instead of failing', async () => {
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, source: 'SOME_FUTURE_SOURCE' }],
    } as never)
    await renderAndSettle()
    await userEvent.click(screen.getByRole('tab', { name: /已收藏/ }))

    // The i18n lookup falls back to the raw value. This is what makes a rollback to a
    // release that has never heard of JOB_CATALOG safe rather than a crash.
    const row = (await screen.findByText('Java Developer')).closest('tr')
    expect(within(row as HTMLElement).getByText('SOME_FUTURE_SOURCE')).toBeInTheDocument()
  })

  it('can filter the personal list down to catalog-sourced jobs', async () => {
    await renderAndSettle()
    await userEvent.click(screen.getByRole('tab', { name: /已收藏/ }))

    await userEvent.selectOptions(
      screen.getByLabelText('来源'),
      'JOB_CATALOG',
    )

    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
  })

  it('a job already applied=true from the backend shows in 已投递, not 已收藏, on a fresh render', async () => {
    // Simulates a page refresh: no client-only memory of what happened in a prior
    // render, only what listJobs (backed by the real jobs.applied/applied_at columns)
    // returns this time.
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, applied: true, appliedAt: '2026-09-06T12:00:00' }],
    } as never)
    await renderAndSettle()

    await userEvent.click(screen.getByRole('tab', { name: /已投递/ }))
    const row = (await screen.findByText('Java Developer')).closest('tr')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('已投递')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText(/2026-09-06/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(screen.queryByText('Java Developer')).not.toBeInTheDocument()
  })

  it('confirming applied from the 已收藏 table moves the row to 已投递', async () => {
    vi.mocked(markJobApplied).mockResolvedValue({
      data: { ...savedFromCatalog, applied: true, appliedAt: '2026-09-06T12:00:00' },
    } as never)
    const user = userEvent.setup()
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已收藏/ }))

    await user.click(await screen.findByRole('button', { name: '确认已投递' }))
    await waitFor(() => expect(markJobApplied).toHaveBeenCalledWith(101))

    // listJobs is refetched with the now-applied row, and the page follows the job
    // to where it moved.
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, applied: true, appliedAt: '2026-09-06T12:00:00' }],
    } as never)
    expect(await screen.findByRole('tab', { name: /已投递/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('revoking applied from the 已投递 table moves the row back to 已收藏', async () => {
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, applied: true, appliedAt: '2026-09-06T12:00:00' }],
    } as never)
    vi.mocked(clearJobApplied).mockResolvedValue({
      data: { ...savedFromCatalog, applied: false, appliedAt: null },
    } as never)
    const user = userEvent.setup()
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已投递/ }))

    await user.click(await screen.findByRole('button', { name: '撤销已投递' }))
    await waitFor(() => expect(clearJobApplied).toHaveBeenCalledWith(101))

    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, applied: false, appliedAt: null }],
    } as never)
    expect(await screen.findByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows a clear empty state for each tab when there is nothing in it', async () => {
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    await renderAndSettle()

    await userEvent.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(await screen.findByText('还没有收藏职位')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: /已投递/ }))
    expect(await screen.findByText('还没有标记为已投递的职位')).toBeInTheDocument()
  })

  it('a failed confirm-applied request never moves the row out of 已收藏', async () => {
    vi.mocked(markJobApplied).mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已收藏/ }))

    await user.click(await screen.findByRole('button', { name: '确认已投递' }))
    await waitFor(() => expect(markJobApplied).toHaveBeenCalledWith(101))

    // Still on 已收藏 -- a failed request must never switch tabs or move the row.
    expect(screen.getByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('a failed revoke request never moves the row out of 已投递', async () => {
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ ...savedFromCatalog, applied: true, appliedAt: '2026-09-06T12:00:00' }],
    } as never)
    vi.mocked(clearJobApplied).mockRejectedValue(new Error('boom'))
    const user = userEvent.setup()
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已投递/ }))

    await user.click(await screen.findByRole('button', { name: '撤销已投递' }))
    await waitFor(() => expect(clearJobApplied).toHaveBeenCalledWith(101))

    expect(screen.getByRole('tab', { name: /已投递/ })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('English tabs are Find Jobs / Favorites / Applied', async () => {
    await i18n.changeLanguage('en')
    await renderAndSettle()
    expect(screen.getByRole('tab', { name: /Find Jobs/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Favorites/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Applied/ })).toBeInTheDocument()
  })

  it('does not record search history on initial load or pagination, only on Search', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([activeJob], { totalPages: 2, hasMore: true, totalCount: 12 }),
    )
    const user = userEvent.setup()
    await renderAndSettle()
    expect(recordJobSearchHistory).not.toHaveBeenCalled()

    await user.type(catalog().getByRole('textbox', { name: '关键词' }), 'java')
    await user.click(catalog().getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(recordJobSearchHistory).toHaveBeenCalledTimes(1))

    await user.click(catalog().getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(recordJobSearchHistory).toHaveBeenCalledTimes(1)
  })

  it('keeps search results when recording history fails', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob]))
    vi.mocked(recordJobSearchHistory).mockRejectedValue(new Error('history down'))
    const user = userEvent.setup()
    await renderAndSettle()
    await user.type(catalog().getByRole('textbox', { name: '关键词' }), 'java')
    await user.click(catalog().getByRole('button', { name: '搜索' }))
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
  })
})
