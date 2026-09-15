import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom'
import i18n from '../i18n'
import {
  clearJobCatalogApplied,
  getJobCatalogJob,
  markJobCatalogApplied,
  saveJobCatalogJob,
} from '../api/jobCatalog'
import { deleteJob } from '../api/jobs'
import { recordJobSearchHistory } from '../api/jobSearchHistory'
import type { JobCatalogDetail, JobCatalogSummary } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession } from '../auth/session'
import { JobCatalogDetailPage } from './JobCatalogDetailPage'

vi.mock('../api/jobs', () => ({
  deleteJob: vi.fn(),
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

const SAMPLE_JD = `Acme is hiring.

COMPANY OVERVIEW
We help families in the Bronx.

JOB RESPONSIBILITIES
• Case management
• Community outreach

JOB REQUIREMENTS
* Bachelor's degree
· Two years of experience
`

const summary: JobCatalogSummary = {
  id: 'freehire:case-manager-3118',
  title: 'Case Manager',
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

function detailFor(overrides: Partial<JobCatalogDetail> = {}): JobCatalogDetail {
  const { cityMatch: _cityMatch, ...rest } = summary
  return {
    ...rest,
    descriptionText: SAMPLE_JD,
    descriptionHtml: '<p>ignored html</p>',
    city: null,
    region: null,
    applyUrl: 'https://boards.greenhouse.io/acme/jobs/501',
    ...overrides,
  }
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

function renderDetail(path = '/jobs/freehire%3Acase-manager-3118') {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route path="/jobs/:catalogJobId" element={<JobCatalogDetailPage />} />
            <Route path="/login" element={<LoginStub />} />
            <Route path="/jobs" element={<div>search page</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

function signIn() {
  saveSession('test-token', { userId: 9, name: 'Test User', email: 'test@example.com', role: 'USER' })
}

describe('JobCatalogDetailPage', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('zh-CN')
    signIn()
    vi.mocked(getJobCatalogJob).mockResolvedValue({ data: detailFor() } as never)
    vi.mocked(saveJobCatalogJob).mockResolvedValue({
      data: { jobId: 101, catalogJobId: 'freehire:case-manager-3118', alreadySaved: false, job: null },
    } as never)
    vi.mocked(markJobCatalogApplied).mockResolvedValue({
      data: {
        jobId: 101,
        catalogJobId: 'freehire:case-manager-3118',
        alreadySaved: false,
        job: { id: 101, applied: true, appliedAt: '2026-09-04T12:00:00Z' },
      },
    } as never)
    vi.mocked(clearJobCatalogApplied).mockResolvedValue({
      data: {
        jobId: 101,
        catalogJobId: 'freehire:case-manager-3118',
        alreadySaved: true,
        job: { id: 101, applied: false, appliedAt: null },
      },
    } as never)
    vi.mocked(deleteJob).mockResolvedValue({} as never)
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('loads a long freehire slug from a direct / refresh URL', async () => {
    let resolveDetail: ((value: { data: ReturnType<typeof detailFor> }) => void) | undefined
    vi.mocked(getJobCatalogJob).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveDetail = resolve
        }),
    )
    renderDetail()
    expect(screen.getByText('正在加载职位详情…')).toBeInTheDocument()
    resolveDetail?.({ data: detailFor() })
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledWith('freehire:case-manager-3118')
    expect(recordJobSearchHistory).not.toHaveBeenCalled()
  })

  it('shows the title and primary actions before the job description', async () => {
    renderDetail()
    const title = await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    const apply = screen.getAllByRole('link', { name: '打开原始职位并申请' })[0]
    const save = screen.getAllByRole('button', { name: '收藏职位' })[0]
    const confirm = screen.getAllByRole('button', { name: '确认已投递' })[0]
    const overview = screen.getByRole('heading', { name: 'COMPANY OVERVIEW' })
    expect(title.compareDocumentPosition(apply) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(apply.compareDocumentPosition(save) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(save.compareDocumentPosition(confirm) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(confirm.compareDocumentPosition(overview) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('splits COMPANY OVERVIEW / RESPONSIBILITIES / REQUIREMENTS into headings, never as one blob', async () => {
    renderDetail()
    expect(await screen.findByRole('heading', { name: 'COMPANY OVERVIEW' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'JOB RESPONSIBILITIES' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'JOB REQUIREMENTS' })).toBeInTheDocument()
    expect(screen.getByText('We help families in the Bronx.')).toBeInTheDocument()
    expect(screen.getByText('Case management')).toBeInTheDocument()
    expect(screen.getByText("Bachelor's degree")).toBeInTheDocument()
    expect(document.querySelector('[dangerouslysetinnerhtml]')).toBeNull()
    expect(screen.queryByText('<p>ignored html</p>')).not.toBeInTheDocument()
  })

  it('omits unknown fields instead of showing Not stated / 未说明', async () => {
    vi.mocked(getJobCatalogJob).mockResolvedValue({
      data: detailFor({
        remoteType: 'UNSPECIFIED',
        employmentType: 'UNSPECIFIED',
        locationText: null,
        countryCode: null,
        postedAt: null,
        source: null,
      }),
    } as never)
    renderDetail()
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
    expect(screen.queryByText('未说明')).not.toBeInTheDocument()
    expect(screen.queryByText('Not stated')).not.toBeInTheDocument()
    expect(screen.queryByText('混合办公')).not.toBeInTheDocument()
    expect(screen.queryByText('全职')).not.toBeInTheDocument()
  })

  it('never auto-marks applied on the original-job click -- only after returning and confirming', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('link', { name: '打开原始职位并申请' })[0])
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(screen.queryByText('你是否已经提交申请？')).not.toBeInTheDocument()

    window.dispatchEvent(new Event('focus'))
    expect(await screen.findByText('你是否已经提交申请？')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '是，标记为已投递' }))
    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalledWith('freehire:case-manager-3118'))
    expect(screen.getAllByText('已投递').length).toBeGreaterThan(0)
  })

  it('declining the confirm prompt marks nothing', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('link', { name: '打开原始职位并申请' })[0])
    window.dispatchEvent(new Event('focus'))
    await user.click(await screen.findByRole('button', { name: '还没有' }))
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(screen.queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
  })

  it('saves, unsaves, confirms applied and revokes, updating the page immediately', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('button', { name: '收藏职位' })[0])
    await waitFor(() => expect(saveJobCatalogJob).toHaveBeenCalledWith('freehire:case-manager-3118'))
    expect(screen.getAllByText('已收藏').length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: '分析职位匹配' })[0]).toHaveAttribute('href', '/tailor?jobId=101')

    await user.click(screen.getAllByRole('button', { name: '取消收藏' })[0])
    await waitFor(() => expect(deleteJob).toHaveBeenCalledWith(101))
    expect(screen.queryByText('已收藏')).not.toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '确认已投递' })[0])
    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalledWith('freehire:case-manager-3118'))
    expect(screen.getAllByText('已投递').length).toBeGreaterThan(0)

    await user.click(screen.getAllByRole('button', { name: '撤销已投递标记' })[0])
    await waitFor(() => expect(clearJobCatalogApplied).toHaveBeenCalledWith('freehire:case-manager-3118'))
    expect(screen.queryByText('已投递')).not.toBeInTheDocument()
  })

  it('keeps the original appliedAt when a later confirmation returns the same timestamp', async () => {
    vi.mocked(getJobCatalogJob).mockResolvedValue({
      data: detailFor({ saved: true, personalJobId: 101, applied: true, appliedAt: '2026-09-04T12:00:00Z' }),
    } as never)
    vi.mocked(markJobCatalogApplied).mockResolvedValue({
      data: {
        jobId: 101,
        catalogJobId: 'freehire:case-manager-3118',
        alreadySaved: true,
        job: { id: 101, applied: true, appliedAt: '2026-09-04T12:00:00Z' },
      },
    } as never)
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('link', { name: '打开原始职位并申请' })[0])
    window.dispatchEvent(new Event('focus'))
    await user.click(await screen.findByRole('button', { name: '是，标记为已投递' }))
    await waitFor(() => expect(markJobCatalogApplied).toHaveBeenCalled())
    expect(screen.getAllByText('已投递').length).toBeGreaterThan(0)
  })

  it('a save whose upstream detail 404s never shows the generic save-failed message', async () => {
    vi.mocked(saveJobCatalogJob).mockRejectedValue({
      response: { status: 404, data: { message: 'JOB_DETAIL_NO_LONGER_AVAILABLE' } },
    })
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('button', { name: '收藏职位' })[0])
    expect(await screen.findByText('详情不可用')).toBeInTheDocument()
    expect(screen.queryByText('收藏职位失败，请重试。')).not.toBeInTheDocument()
    expect(screen.getByText(/该职位的详情已经失效/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '收藏职位' })[0]).toBeDisabled()
  })

  it('maps 404 / 429 Retry-After / 502 / timeout without a blank page or raw 500 copy', async () => {
    const user = userEvent.setup()
    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      response: { status: 404, data: { message: 'not found' } },
    })
    renderDetail()
    expect(await screen.findByText('该职位详情已经失效，可能已经下架')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: '返回搜索结果' }).length).toBeGreaterThan(0)
    expect(screen.queryByText(/500/)).not.toBeInTheDocument()
    cleanup()

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      response: { status: 429, headers: { 'retry-after': '30' }, data: {} },
    })
    renderDetail()
    const limited = await screen.findByRole('alert')
    expect(limited).toHaveTextContent(/过于频繁/)
    expect(limited).toHaveTextContent('30')

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      response: { status: 502, data: { message: 'bad gateway' } },
    })
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('职位详情暂时不可用，请稍后再试。')).toBeInTheDocument()
    expect(screen.queryByText('bad gateway')).not.toBeInTheDocument()
    expect(screen.queryByText(/500/)).not.toBeInTheDocument()

    vi.mocked(getJobCatalogJob).mockRejectedValueOnce({
      code: 'ECONNABORTED',
      message: 'timeout of 10000ms exceeded',
    })
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('职位详情暂时不可用，请稍后再试。')).toBeInTheDocument()

    vi.mocked(getJobCatalogJob).mockResolvedValueOnce({ data: detailFor() } as never)
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
  })

  it('translates the detail chrome in English', async () => {
    await i18n.changeLanguage('en')
    renderDetail()
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Open original posting and apply' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', { name: 'Add to favorites' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: 'Back to search results' }).length).toBeGreaterThan(0)
  })
})

describe('JobCatalogDetailPage for an anonymous visitor', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(getJobCatalogJob).mockResolvedValue({ data: detailFor() } as never)
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  it('lets an anonymous visitor read the detail without logging in', async () => {
    renderDetail()
    expect(await screen.findByRole('heading', { level: 1, name: 'Case Manager' })).toBeInTheDocument()
    expect(getJobCatalogJob).toHaveBeenCalledWith('freehire:case-manager-3118')
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
  })

  it('sends an anonymous favorite click to login with the encoded detail as returnTo', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('button', { name: '收藏职位' })[0])
    expect(saveJobCatalogJob).not.toHaveBeenCalled()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs/freehire%3Acase-manager-3118')
    expect(screen.getByTestId('reason')).toHaveTextContent('login_required')
  })

  it('sends an anonymous confirm-applied click to the same detail returnTo', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('button', { name: '确认已投递' })[0])
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
    expect(screen.getByTestId('returnTo')).toHaveTextContent('/jobs/freehire%3Acase-manager-3118')
    expect(screen.getByTestId('reason')).toHaveTextContent('login_required')
  })

  it('does not arm an applied confirmation when an anonymous visitor opens the original posting', async () => {
    const user = userEvent.setup()
    renderDetail()
    await screen.findByRole('heading', { level: 1, name: 'Case Manager' })
    await user.click(screen.getAllByRole('link', { name: '打开原始职位并申请' })[0])
    window.dispatchEvent(new Event('focus'))
    expect(screen.queryByText('你是否已经提交申请？')).not.toBeInTheDocument()
    expect(markJobCatalogApplied).not.toHaveBeenCalled()
  })
})
