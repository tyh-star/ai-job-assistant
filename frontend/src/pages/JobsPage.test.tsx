import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes, useLocation, useSearchParams } from 'react-router-dom'
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
import { catalogJobPath } from '../jobCatalog/catalogJobPath'
import { JobCatalogDetailPage } from './JobCatalogDetailPage'
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

const freehireJob: JobCatalogSummary = {
  ...activeJob,
  id: 'freehire:case-manager-3118',
  title: 'Case Manager',
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

function LocationProbe() {
  const location = useLocation()
  return (
    <div data-testid="current-location">
      {`${location.pathname}${location.search}`}
    </div>
  )
}

function LoginStub() {
  const [params] = useSearchParams()
  return (
    <div>
      <h1>登录</h1>
      <span data-testid="returnTo">{params.get('returnTo')}</span>
      <span data-testid="reason">{params.get('reason')}</span>
    </div>
  )
}

function renderPage(initialPath = '/jobs') {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[initialPath]}>
        <LocationProbe />
        <AuthProvider>
          <Routes>
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/jobs/:catalogJobId" element={<JobCatalogDetailPage />} />
            <Route path="/login" element={<LoginStub />} />
          </Routes>
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

async function openCatalogJob(title: string, headingName = '全球公开职位') {
  const user = userEvent.setup()
  const item = (await catalog(headingName).findByText(title)).closest('li')
  if (!item) {
    throw new Error(`job row not found: ${title}`)
  }
  const viewDetails = headingName === 'Global public jobs' ? /View details/ : /查看详情/
  await user.click(within(item).getByRole('link', { name: viewDetails }))
  return { user, item: item as HTMLElement }
}

async function renderAndSettle() {
  renderPage()
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
    sessionStorage.clear()
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

  it('never auto-marks applied from the search list, because the list has no apply action', async () => {
    await renderAndSettle()
    expect(catalog().queryByRole('link', { name: '打开原始职位并申请' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '查看原始职位' })).not.toBeInTheDocument()
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
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

  it('saves filter and page state to the URL so returning from a detail can restore them', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('关键词'), 'java')
    await user.selectOptions(panel.getByLabelText('国家'), 'CA')
    await user.selectOptions(panel.getByLabelText('工作方式'), 'REMOTE')
    await user.click(panel.getByRole('button', { name: '搜索' }))

    await waitFor(() =>
      expect(screen.getByTestId('current-location')).toHaveTextContent('/jobs?q=java&country=CA&workMode=REMOTE'),
    )
    expect(recordJobSearchHistory).toHaveBeenCalledTimes(1)
  })

  it('restores filters, page and results from the URL on a fresh visit', async () => {
    renderPage('/jobs?q=java&country=CA&workMode=REMOTE&page=2')
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(vi.mocked(searchJobCatalog).mock.calls[0][0]).toMatchObject({
      q: 'java',
      country: 'CA',
      remoteType: 'REMOTE',
      page: 2,
      size: 10,
    })
    expect(catalog().getByLabelText('关键词')).toHaveValue('java')
    expect((catalog().getByLabelText('国家') as HTMLSelectElement).value).toBe('CA')
    expect((catalog().getByLabelText('工作方式') as HTMLSelectElement).value).toBe('REMOTE')
  })

  it('restores the saved list scroll after results reload', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    sessionStorage.setItem('aijob.jobsSearch.query', '?q=java')
    sessionStorage.setItem('aijob.jobsSearch.scroll', JSON.stringify({ search: '?q=java', y: 640 }))
    renderPage('/jobs?q=java')
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await waitFor(() => expect(scrollTo).toHaveBeenCalledWith(0, 640))
    expect(sessionStorage.getItem('aijob.jobsSearch.scroll')).toBeNull()
    expect(sessionStorage.getItem('aijob.jobsSearch.query')).toBe('?q=java')
    scrollTo.mockRestore()
  })

  it('marks a job already saved when the catalog says so, without tailor links on the compact list', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([{ ...activeJob, saved: true, personalJobId: 77 }]),
    )
    await renderAndSettle()

    expect(await catalog().findByText('已收藏')).toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '分析职位匹配' })).not.toBeInTheDocument()
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
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
    expect(catalog('Global public jobs').getByRole('link', { name: /View details/ })).toBeInTheDocument()
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

  it('does not fetch personal jobs while the signed-in visitor stays on 查找职位', async () => {
    await renderAndSettle()
    expect(listJobs).not.toHaveBeenCalled()
    expect(getJobApplicationStats).not.toHaveBeenCalled()
    expect(screen.queryByText('没有权限执行该操作。')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /已收藏（0）/ })).not.toBeInTheDocument()
  })

  it('fetches personal jobs only after opening 已收藏 or 已投递', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    expect(listJobs).not.toHaveBeenCalled()
    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    await waitFor(() => expect(listJobs).toHaveBeenCalledTimes(1))
  })

  it('keeps a private-list 403 off the public search tab', async () => {
    vi.mocked(listJobs).mockRejectedValue({ response: { status: 403, data: {} } })
    const user = userEvent.setup()
    await renderAndSettle()
    expect(screen.queryByText('没有权限执行该操作。')).not.toBeInTheDocument()
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(await screen.findByText('没有权限执行该操作。')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '查找职位' }))
    expect(screen.queryByText('没有权限执行该操作。')).not.toBeInTheDocument()
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
  })

  it('shows distinct 401, 403, and network copy on the private tabs', async () => {
    const user = userEvent.setup()
    vi.mocked(listJobs).mockRejectedValue({ response: { status: 401, data: {} } })
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(await screen.findByText('登录已过期，请重新登录。')).toBeInTheDocument()
    expect(screen.queryByText('没有权限执行该操作。')).not.toBeInTheDocument()

    cleanup()
    vi.mocked(listJobs).mockRejectedValue({ response: { status: 403, data: {} } })
    renderPage('/jobs?tab=favorites')
    expect(await screen.findByText('没有权限执行该操作。')).toBeInTheDocument()
    expect(screen.queryByText('登录已过期，请重新登录。')).not.toBeInTheDocument()
    expect(screen.queryByText('网络异常，请检查连接。')).not.toBeInTheDocument()

    cleanup()
    vi.mocked(listJobs).mockRejectedValue({ message: 'Network Error' })
    renderPage('/jobs?tab=applied')
    expect(await screen.findByText('网络异常，请检查连接。')).toBeInTheDocument()
    expect(screen.queryByText('没有权限执行该操作。')).not.toBeInTheDocument()
    expect(screen.queryByText('登录已过期，请重新登录。')).not.toBeInTheDocument()
  })

  it('restores the Applied tab from a refreshable ?tab=applied query', async () => {
    renderPage('/jobs?tab=applied')
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    expect(await screen.findByRole('tab', { name: /已投递/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('current-location')).toHaveTextContent('/jobs?tab=applied')
  })

  it('clicking 已收藏 writes ?tab=favorites so a refresh can restore that tab', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(screen.getByRole('tab', { name: /已收藏/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('current-location')).toHaveTextContent('/jobs?tab=favorites')
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
      expect(tab.className).not.toMatch(/(?:^|\s)w-\d|\bmin-w-\[|\bwhitespace-nowrap\b/)
    }
    expect(screen.getByRole('heading', { name: '职位' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '查找职位' })).not.toBeInTheDocument()
  })

  it('shows 10 compact rows with no JD, no apply link and no favorite actions', async () => {
    const ten = Array.from({ length: 10 }, (_, index) => ({
      ...activeJob,
      id: `job-${index + 1}`,
      title: `Compact Job ${index + 1}`,
    }))
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf(ten, { totalCount: 10 }))
    await renderAndSettle()

    expect(catalog().getAllByRole('link', { name: /查看详情/ })).toHaveLength(10)
    expect(catalog().queryByText(/full job description/)).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '查看原始职位' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('link', { name: '打开原始职位并申请' })).not.toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
    expect(searchJobCatalog).toHaveBeenCalledTimes(1)
  })

  it('never requests a catalog detail from the search page', async () => {
    await renderAndSettle()
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
    expect(catalog().queryByRole('button', { name: /查看详情/ })).not.toBeInTheDocument()
    expect(catalog().queryByText('未说明')).not.toBeInTheDocument()
    expect(catalog().queryByText('Not stated')).not.toBeInTheDocument()
  })

  it('omits unspecified work-mode and employment-type badges instead of showing Not stated', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([{ ...activeJob, remoteType: 'UNSPECIFIED', employmentType: 'UNSPECIFIED' }]),
    )
    await renderAndSettle()
    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
    expect(catalog().queryByText('未说明')).not.toBeInTheDocument()
    const row = catalog().getByText('Java Developer').closest('li') as HTMLElement
    expect(within(row).queryByText('混合办公')).not.toBeInTheDocument()
    expect(within(row).queryByText('全职')).not.toBeInTheDocument()
  })

  it('uses a real encoded Link for the title and view-details control', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([freehireJob]))
    await renderAndSettle()
    const href = catalogJobPath(freehireJob.id)
    expect(catalog().getByRole('link', { name: 'Case Manager' })).toHaveAttribute('href', href)
    expect(catalog().getByRole('link', { name: /查看详情/ })).toHaveAttribute('href', href)
    expect(href).toBe('/jobs/freehire%3Acase-manager-3118')
    expect(getJobCatalogJob).not.toHaveBeenCalled()
  })

  it('opens the encoded detail route when the title is clicked', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([freehireJob]))
    vi.mocked(getJobCatalogJob).mockResolvedValue({ data: catalogDetailFor(freehireJob) } as never)
    const user = userEvent.setup()
    await renderAndSettle()
    expect(getJobCatalogJob).not.toHaveBeenCalled()

    await user.click(catalog().getByRole('link', { name: 'Case Manager' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
    expect(screen.getByTestId('current-location')).toHaveTextContent('/jobs/freehire%3Acase-manager-3118')
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
    expect(getJobCatalogJob).toHaveBeenCalledWith('freehire:case-manager-3118')
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
  })

  it('opens the encoded detail route from 查看详情 and does not write search history', async () => {
    await renderAndSettle()
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
    await openCatalogJob('Java Developer')
    expect(await screen.findByRole('heading', { level: 1, name: 'Java Developer' })).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
    expect(getJobCatalogJob).toHaveBeenCalledWith('501')
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
    expect(screen.getAllByRole('link', { name: '打开原始职位并申请' }).length).toBeGreaterThan(0)
  })

  it('returning from a detail page shows updated saved/applied badges from a fresh summary fetch', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    expect(catalog().queryByText('已收藏')).not.toBeInTheDocument()

    await openCatalogJob('Java Developer')
    await user.click(screen.getAllByRole('button', { name: '收藏职位' })[0])
    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith('501'))

    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([{ ...activeJob, saved: true, personalJobId: 101 }]),
    )
    await user.click(screen.getAllByRole('link', { name: '返回搜索结果' })[0])
    expect(await catalog().findByText('已收藏')).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledTimes(1)
  })

  it('lets a keyboard user open the title link with Enter', async () => {
    const user = userEvent.setup()
    await renderAndSettle()
    const title = catalog().getByRole('link', { name: 'Java Developer' })
    title.focus()
    expect(title).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { level: 1, name: 'Java Developer' })).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledWith('501')
  })
})

function renderAnonymous(initialPath = '/jobs') {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[initialPath]}>
        <LocationProbe />
        <AuthProvider>
          <Routes>
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/jobs/:catalogJobId" element={<JobCatalogDetailPage />} />
            <Route path="/login" element={<LoginStub />} />
          </Routes>
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
    sessionStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('can search a compact list without fetching detail, and still must log in to favorite', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '收藏职位' })).not.toBeInTheDocument()
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
  })

  it('never fetches the personal jobs list for a visitor who is not logged in', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await screen.findByText('Java Developer')
    expect(listJobs).not.toHaveBeenCalled()
  })

  it('clicking 已收藏/已投递 as an anonymous visitor never fetches personal jobs', async () => {
    const user = userEvent.setup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())

    await user.click(screen.getByRole('tab', { name: /已收藏/ }))
    expect(listJobs).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs?tab=favorites')
    expect(screen.getByTestId('reason')).toHaveTextContent('login_required')

    cleanup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await user.click(screen.getByRole('tab', { name: /已投递/ }))
    expect(listJobs).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs?tab=applied')
    expect(screen.getByTestId('reason')).toHaveTextContent('login_required')
  })

  it('clicking save on a detail page redirects toward login with that detail as returnTo', async () => {
    const user = userEvent.setup()
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    await openCatalogJob('Java Developer')
    expect(await screen.findByRole('heading', { level: 1, name: 'Java Developer' })).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: '收藏职位' })[0])
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs/501')
    expect(screen.getByTestId('reason')).toHaveTextContent('login_required')
  })

  it('the search list never offers an apply confirmation for an anonymous visitor', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(screen.queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '打开原始职位并申请' })).not.toBeInTheDocument()
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(getJobCatalogJob).not.toHaveBeenCalled()
  })

  it('never shows a fake Favorites (0) or Applied (0) count while signed out', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(screen.getByRole('tab', { name: /已收藏/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /已投递/ })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /已收藏（0）/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /已投递（0）/ })).not.toBeInTheDocument()
    expect(screen.queryByText('已收藏（0）')).not.toBeInTheDocument()
    expect(screen.queryByText('已投递（0）')).not.toBeInTheDocument()
  })

  it('does not repeat the homepage feature cards on the jobs page', async () => {
    renderAnonymous()
    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
    expect(screen.getByRole('heading', { name: '职位' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '查找职位' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '查找职位' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '创建与检查简历' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '改写简历并管理投递' })).not.toBeInTheDocument()
    expect(screen.queryByText('找职位、做简历、管投递，一站完成')).not.toBeInTheDocument()
  })

  it('opening /jobs?tab=favorites while signed out goes to login and never fetches personal jobs', async () => {
    renderAnonymous('/jobs?tab=favorites')
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs?tab=favorites')
    expect(listJobs).not.toHaveBeenCalled()
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

    const row = (await screen.findByText('Java Developer')).closest('li')
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
    const row = (await screen.findByText('Java Developer')).closest('li')
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
    const row = (await screen.findByText('Java Developer')).closest('li')
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
