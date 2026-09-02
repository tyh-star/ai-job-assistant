import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { endpoints } from '../api/endpoints'
import {
  analyzeTailorFit,
  createTailorProposal,
  importSearchResults,
  listJobs,
  fetchNextSearchPage,
  saveSearchResult,
  searchJobs,
} from '../api/jobs'
import { saveJobCatalogJob, searchJobCatalog } from '../api/jobCatalog'
import type { JobCatalogSummary } from '../api/types'
import { JobsPage } from './JobsPage'

vi.mock('../api/jobs', () => ({
  listJobs: vi.fn(),
  createJob: vi.fn(),
  updateJob: vi.fn(),
  deleteJob: vi.fn(),
  bulkDeleteJobs: vi.fn(),
  importGreenhouse: vi.fn(),
  searchJobs: vi.fn(),
  fetchNextSearchPage: vi.fn(),
  saveSearchResult: vi.fn(),
  importSearchResults: vi.fn(),
  analyzeTailorFit: vi.fn(),
  createTailorProposal: vi.fn(),
  refreshJobRequirementProfile: vi.fn(),
}))

vi.mock('../api/jobCatalog', () => ({
  searchJobCatalog: vi.fn(),
  getJobCatalogJob: vi.fn(),
  saveJobCatalogJob: vi.fn(),
}))

const activeJob: JobCatalogSummary = {
  id: 501,
  title: 'Java Developer',
  companyName: 'Acme',
  locationText: 'Toronto, ON',
  countryCode: 'CA',
  remoteType: 'HYBRID',
  employmentType: 'FULL_TIME',
  salaryMin: null,
  salaryMax: null,
  currency: null,
  applyUrl: 'https://boards.greenhouse.io/acme/jobs/501',
  canonicalUrl: 'https://boards.greenhouse.io/acme/jobs/501',
  source: 'GREENHOUSE',
  postedAt: '2026-08-01T12:00:00Z',
  firstSeenAt: '2026-08-02T00:00:00Z',
  lastSeenAt: '2026-08-20T00:00:00Z',
  status: 'ACTIVE',
  descriptionSnippet: 'Build APIs with Java.',
  saved: false,
  personalJobId: null,
}

const expiredJob: JobCatalogSummary = {
  ...activeJob,
  id: 502,
  title: 'Platform Engineer',
  status: 'EXPIRED',
  descriptionSnippet: 'Run the platform.',
}

function pageOf(results: JobCatalogSummary[], overrides: Record<string, unknown> = {}) {
  return {
    data: {
      page: 1,
      size: 20,
      totalCount: results.length,
      totalPages: results.length === 0 ? 0 : 1,
      hasMore: false,
      results,
      ...overrides,
    },
  } as never
}

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter>
        <JobsPage />
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

async function renderAndSettle() {
  renderPage()
  await waitFor(() => expect(listJobs).toHaveBeenCalled())
  await waitFor(() => expect(searchJobCatalog).toHaveBeenCalled())
}

describe('JobsPage catalog search', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([activeJob]))
    vi.mocked(saveJobCatalogJob).mockResolvedValue({
      data: { jobId: 101, catalogJobId: 501, alreadySaved: false, job: null },
    } as never)
  })

  afterEach(async () => {
    cleanup()
    await i18n.changeLanguage('zh-CN')
  })

  it('reads the local catalog and never calls the external job search', async () => {
    await renderAndSettle()

    expect(await catalog().findByText('Java Developer')).toBeInTheDocument()
    expect(catalog().getByText('Acme')).toBeInTheDocument()
    expect(catalog().getByText(/GREENHOUSE/)).toBeInTheDocument()
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

  it('sends keyword and filter parameters to the catalog', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    const panel = catalog()
    await user.type(panel.getByLabelText('关键词'), 'java')
    await user.type(panel.getByLabelText('地点'), 'Toronto')
    await user.selectOptions(panel.getByLabelText('国家'), 'CA')
    await user.selectOptions(panel.getByLabelText('工作方式'), 'REMOTE')
    await user.selectOptions(panel.getByLabelText('职位类型'), 'FULL_TIME')
    await user.selectOptions(panel.getByLabelText('发布时间'), '7')
    await user.click(panel.getByRole('button', { name: '搜索' }))

    await waitFor(() => expect(searchJobCatalog).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchJobCatalog).mock.calls[1][0]).toMatchObject({
      q: 'java',
      location: 'Toronto',
      country: 'CA',
      remoteType: 'REMOTE',
      employmentType: 'FULL_TIME',
      postedWithinDays: 7,
      page: 1,
      size: 20,
    })
    expect(searchJobs).not.toHaveBeenCalled()
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
    expect(alert).toHaveTextContent('搜索已收录职位失败')
    expect(searchJobs).not.toHaveBeenCalled()
  })

  it('saves a posting, exposes the personal job id, and blocks a second save', async () => {
    const user = userEvent.setup()
    await renderAndSettle()

    await user.click(await catalog().findByRole('button', { name: '保存职位' }))

    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith(501))
    expect(await catalog().findByText('已保存')).toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '保存职位' })).not.toBeInTheDocument()
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

  it('marks a job already saved when the catalog says so', async () => {
    vi.mocked(searchJobCatalog).mockResolvedValue(
      pageOf([{ ...activeJob, saved: true, personalJobId: 77 }]),
    )
    await renderAndSettle()

    expect(await catalog().findByText('已保存')).toBeInTheDocument()
    expect(catalog().queryByRole('button', { name: '保存职位' })).not.toBeInTheDocument()
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
    await i18n.changeLanguage('zh-CN')
    vi.mocked(searchJobCatalog).mockResolvedValue(pageOf([]))
    vi.mocked(listJobs).mockResolvedValue({ data: [savedFromCatalog] } as never)
  })

  afterEach(async () => {
    cleanup()
    await i18n.changeLanguage('zh-CN')
  })

  it('lists a catalog-sourced job among the user’s own jobs', async () => {
    await renderAndSettle()

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

    // The i18n lookup falls back to the raw value. This is what makes a rollback to a
    // release that has never heard of JOB_CATALOG safe rather than a crash.
    const row = (await screen.findByText('Java Developer')).closest('tr')
    expect(within(row as HTMLElement).getByText('SOME_FUTURE_SOURCE')).toBeInTheDocument()
  })

  it('can filter the personal list down to catalog-sourced jobs', async () => {
    await renderAndSettle()

    await userEvent.selectOptions(
      screen.getByLabelText('来源'),
      'JOB_CATALOG',
    )

    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
  })
})
