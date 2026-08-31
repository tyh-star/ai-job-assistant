import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import {
  analyzeTailorFit,
  createTailorProposal,
  importSearchResults,
  listJobs,
  fetchNextSearchPage,
  saveSearchResult,
  searchJobs,
} from '../api/jobs'
import type { ImportSearchResultsResponse, JobSearchResult } from '../api/types'
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

const snippet = 'Build APIs with Java.'

const searchResult: JobSearchResult = {
  externalJobId: 'raw-1',
  title: 'Java Developer',
  company: 'Acme',
  location: 'Toronto',
  postedAt: '2026-08-01T12:00:00Z',
  descriptionSnippet: snippet,
  source: 'JSEARCH',
  sourceUrl: 'https://jobs.acme.com/java',
  publisher: 'LinkedIn',
  fullDescriptionAvailable: true,
  alreadySaved: false,
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

describe('JobsPage search and import', () => {
  const scrollIntoView = vi.fn()
  const windowScrollTo = vi.fn()
  let originalScrollIntoView: typeof Element.prototype.scrollIntoView

  beforeEach(async () => {
    vi.clearAllMocks()
    scrollIntoView.mockReset()
    windowScrollTo.mockReset()
    originalScrollIntoView = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scrollIntoView
    vi.stubGlobal('scrollTo', windowScrollTo)
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    vi.mocked(searchJobs).mockResolvedValue({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 1,
        pageSize: 10,
        hasMore: true,
        totalCount: null,
        results: [searchResult],
      },
    } as never)
    vi.mocked(fetchNextSearchPage).mockResolvedValue({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 2,
        pageSize: 10,
        hasMore: true,
        totalCount: null,
        results: [],
      },
    } as never)
    vi.mocked(importSearchResults).mockResolvedValue({
      data: {
        imported: [{ externalJobId: 'raw-1', jobId: 101 }],
        existing: [{ externalJobId: 'raw-2', jobId: 88 }],
        failed: [{ externalJobId: 'raw-3', reason: 'DESCRIPTION_MISSING' }],
      } satisfies ImportSearchResultsResponse,
    } as never)
  })

  afterEach(() => {
    Element.prototype.scrollIntoView = originalScrollIntoView
    vi.unstubAllGlobals()
    cleanup()
  })

  async function searchFirstPage(user: ReturnType<typeof userEvent.setup>) {
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    scrollIntoView.mockClear()
    windowScrollTo.mockClear()
  }

  function mockPage2() {
    vi.mocked(fetchNextSearchPage).mockResolvedValue({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 2,
        pageSize: 10,
        hasMore: false,
        totalCount: null,
        limitReached: false,
        loadedResultCount: 2,
        results: [{
          ...searchResult,
          externalJobId: 'raw-2',
          title: 'Platform Engineer',
          sourceUrl: 'https://jobs.acme.com/k8s',
        }],
      },
    } as never)
  }

  it('searches only after submit and does not paste a full JD', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())

    expect(screen.queryByText('从 Greenhouse 导入')).not.toBeInTheDocument()
    expect(screen.queryByText('需要完整职位描述')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('关键词'), 'java')
    expect(searchJobs).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '搜索' }))

    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByText('完整描述可导入')).toBeInTheDocument()
    expect(screen.getByText(/LinkedIn/)).toBeInTheDocument()
    expect(screen.queryByText('Jobs by Adzuna')).not.toBeInTheDocument()
    expect(screen.queryByText('需要完整职位描述')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('完整职位描述')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '打开原始职位' })).toHaveAttribute(
      'href',
      'https://jobs.acme.com/java',
    )
    expect(analyzeTailorFit).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(saveSearchResult).not.toHaveBeenCalled()
    expect(importSearchResults).not.toHaveBeenCalled()
  })

  it('imports selected jobs and only links to tailor', async () => {
    const user = userEvent.setup()
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    await user.click(await screen.findByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '导入所选' }))

    await waitFor(() => expect(importSearchResults).toHaveBeenCalledTimes(1))
    expect(vi.mocked(importSearchResults).mock.calls[0][0]).toEqual({
      searchSessionId: '11111111-1111-1111-1111-111111111111',
      selectedExternalJobIds: ['raw-1'],
    })
    expect(await screen.findByText('成功导入 1 条')).toBeInTheDocument()
    expect(screen.getByText('已存在 1 条')).toBeInTheDocument()
    expect(screen.getByText('失败 1 条')).toBeInTheDocument()
    const analyzeLinks = screen.getAllByRole('link', { name: '分析职位匹配' })
    const tailorLinks = screen.getAllByRole('link', { name: '生成定向建议' })
    expect(analyzeLinks[0]).toHaveAttribute('href', '/tailor?jobId=101')
    expect(tailorLinks[0]).toHaveAttribute('href', '/tailor?jobId=101')
    expect(analyzeLinks[1]).toHaveAttribute('href', '/tailor?jobId=88')
    expect(saveSearchResult).not.toHaveBeenCalled()
    expect(analyzeTailorFit).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
  })

  it('loads the next page, keeps previous local, and imports across pages', async () => {
    const user = userEvent.setup()
    const page2: JobSearchResult = {
      ...searchResult,
      externalJobId: 'raw-2',
      title: 'Platform Engineer',
      sourceUrl: 'https://jobs.acme.com/k8s',
    }
    vi.mocked(fetchNextSearchPage).mockResolvedValue({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 2,
        pageSize: 10,
        hasMore: false,
        totalCount: null,
        limitReached: false,
        loadedResultCount: 2,
        results: [page2],
      },
    } as never)
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByText(/每加载一个新页面约消耗1次搜索额度/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(fetchNextSearchPage).toHaveBeenCalledTimes(1))
    expect(vi.mocked(fetchNextSearchPage).mock.calls[0][0]).toBe('11111111-1111-1111-1111-111111111111')
    expect(vi.mocked(fetchNextSearchPage).mock.calls[0][1]).toBe(1)
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(screen.queryByText('Java Developer')).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '上一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(fetchNextSearchPage).toHaveBeenCalledTimes(1)
    expect(searchJobs).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(fetchNextSearchPage).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '导入所选' }))
    await waitFor(() => expect(importSearchResults).toHaveBeenCalledTimes(1))
    expect(vi.mocked(importSearchResults).mock.calls[0][0].selectedExternalJobIds).toEqual(['raw-1', 'raw-2'])
  })

  it('clears pagination on a new search and keeps state when next fails', async () => {
    const user = userEvent.setup()
    vi.mocked(fetchNextSearchPage)
      .mockRejectedValueOnce({ response: { status: 502, data: { message: 'Job search is temporarily unavailable.' } } })
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    await user.click(await screen.findByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
    await user.type(screen.getByLabelText('关键词'), ' spring')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchJobs).toHaveBeenCalledTimes(2))
    expect(fetchNextSearchPage).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('checkbox')).not.toBeChecked()
  })

  it('disables next when hasMore is false and shows the safety limit', async () => {
    const user = userEvent.setup()
    vi.mocked(searchJobs).mockResolvedValue({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 1,
        pageSize: 10,
        hasMore: false,
        totalCount: null,
        limitReached: true,
        results: [searchResult],
      },
    } as never)
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
    expect(screen.getByText(/100条安全上限/)).toBeInTheDocument()
  })

  it('prevents double-clicking next while loading', async () => {
    const user = userEvent.setup()
    let resolveNext: ((value: unknown) => void) | undefined
    vi.mocked(fetchNextSearchPage).mockImplementation(
      () => new Promise((resolve) => {
        resolveNext = resolve
      }) as never,
    )
    renderPage()
    await waitFor(() => expect(listJobs).toHaveBeenCalled())
    await user.type(screen.getByLabelText('关键词'), 'java')
    await user.click(screen.getByRole('button', { name: '搜索' }))
    await screen.findByText('Java Developer')
    await user.click(screen.getByRole('button', { name: '下一页' }))
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(fetchNextSearchPage).toHaveBeenCalledTimes(1)
    resolveNext?.({
      data: {
        searchSessionId: '11111111-1111-1111-1111-111111111111',
        provider: 'JSEARCH',
        page: 2,
        pageSize: 10,
        hasMore: false,
        totalCount: null,
        results: [{ ...searchResult, externalJobId: 'raw-2', title: 'Next Role' }],
      },
    })
    expect(await screen.findByText('Next Role')).toBeInTheDocument()
  })

  it('scrolls to the results top after a successful next page load', async () => {
    const user = userEvent.setup()
    mockPage2()
    await searchFirstPage(user)
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(windowScrollTo).not.toHaveBeenCalled()
  })

  it('scrolls to the results top after previous', async () => {
    const user = userEvent.setup()
    mockPage2()
    await searchFirstPage(user)
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    scrollIntoView.mockClear()
    windowScrollTo.mockClear()
    await user.click(screen.getByRole('button', { name: '上一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(windowScrollTo).not.toHaveBeenCalled()
  })

  it('scrolls when switching to an already loaded page locally', async () => {
    const user = userEvent.setup()
    mockPage2()
    await searchFirstPage(user)
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '上一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    scrollIntoView.mockClear()
    windowScrollTo.mockClear()
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(fetchNextSearchPage).toHaveBeenCalledTimes(1)
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(windowScrollTo).not.toHaveBeenCalled()
  })

  it('does not scroll when next fails and keeps the page and selection', async () => {
    const user = userEvent.setup()
    vi.mocked(fetchNextSearchPage).mockRejectedValueOnce({
      response: { status: 502, data: { message: 'Job search is temporarily unavailable.' } },
    })
    await searchFirstPage(user)
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('checkbox')).toBeChecked()
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByText('第 1 页')).toBeInTheDocument()
    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(windowScrollTo).not.toHaveBeenCalled()
  })

  it('keeps checkboxes across page changes', async () => {
    const user = userEvent.setup()
    mockPage2()
    await searchFirstPage(user)
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '上一页' }))
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(await screen.findByText('Platform Engineer')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
  })
})
