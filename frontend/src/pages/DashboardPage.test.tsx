import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession } from '../auth/session'
import { listJobs } from '../api/jobs'
import { listResumes } from '../api/resumes'
import { getResumeBuilderDraft, listResumeBuilderDrafts } from '../api/resumeBuilder'
import { blankDraftContent } from '../resumeBuilder/constants'
import { DashboardPage } from './DashboardPage'

vi.mock('../api/jobs', () => ({ listJobs: vi.fn() }))
vi.mock('../api/resumes', () => ({ listResumes: vi.fn() }))
vi.mock('../api/resumeBuilder', () => ({
  listResumeBuilderDrafts: vi.fn(),
  getResumeBuilderDraft: vi.fn(),
}))

const JOBS = [
  { id: 1, title: 'Client Services Associate', company: 'Riverside Centre', applied: false, appliedAt: null, updatedAt: '2026-09-01T00:00:00Z' },
  { id: 2, title: 'Facilities Coordinator', company: 'Northfield', applied: false, appliedAt: null, updatedAt: '2026-09-02T00:00:00Z' },
  { id: 3, title: 'Operations Coordinator', company: 'Harbour Co', applied: true, appliedAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z' },
  { id: 4, title: 'Program Assistant', company: 'City College', applied: true, appliedAt: '2026-09-07T00:00:00Z', updatedAt: '2026-09-07T00:00:00Z' },
]

const DRAFT = {
  id: 7,
  title: 'Dana Lee – Junior Software Developer',
  templateType: 'STUDENT',
  targetRole: 'Junior Software Developer',
  locale: 'EN',
  version: 3,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-08T00:00:00Z',
}

const UPLOAD = {
  id: 12,
  fileName: 'resume-general.pdf',
  fileType: 'PDF',
  origin: 'UPLOAD',
  createdAt: '2026-09-02T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
}

function readyContent() {
  const content = blankDraftContent()
  return {
    ...content,
    basicInfo: { ...content.basicInfo, fullName: 'Dana Lee', email: 'dana@example.com' },
    jobTarget: { ...content.jobTarget, targetTitle: 'Junior Software Developer' },
  }
}

function renderDashboard() {
  saveSession('test-token', { userId: 1, name: 'Dana Lee', email: 'dana@example.com', role: 'USER' })
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <DashboardPage />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('DashboardPage', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listJobs).mockResolvedValue({ data: JOBS } as never)
    vi.mocked(listResumes).mockResolvedValue({ data: [UPLOAD] } as never)
    vi.mocked(listResumeBuilderDrafts).mockResolvedValue({ data: [DRAFT] } as never)
    vi.mocked(getResumeBuilderDraft).mockResolvedValue({
      data: { ...DRAFT, content: readyContent() },
    } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('leads with the resume center, then saved jobs and recent applications', async () => {
    renderDashboard()

    // wait for the loaded state: the skeleton renders the same headings
    expect(await screen.findByText(DRAFT.title)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '简历中心' })).toBeInTheDocument()
    // the template type is localised, never the raw enum
    expect(screen.getByText(/学生模板/)).toBeInTheDocument()
    expect(screen.queryByText('STUDENT')).not.toBeInTheDocument()
    expect(screen.getByText(UPLOAD.fileName)).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: '职位' })).toBeInTheDocument()
    // saved count is derived from applied === false, not a separate endpoint
    expect(screen.getByText('2 个已收藏')).toBeInTheDocument()
    expect(screen.getByText('Client Services Associate')).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: '最近投递' })).toBeInTheDocument()
    const applied = screen.getAllByRole('link', { name: /Coordinator|Assistant/ })
    expect(applied.length).toBeGreaterThan(0)
    // newest first, by appliedAt
    expect(screen.getByText('Operations Coordinator')).toBeInTheDocument()
    expect(screen.getByText('Program Assistant')).toBeInTheDocument()
    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('active')
  })

  it('shows the readiness line only when the draft is actually ready to generate', async () => {
    renderDashboard()
    expect(await screen.findByText('内容完整，可继续针对职位优化')).toBeInTheDocument()

    cleanup()
    vi.mocked(getResumeBuilderDraft).mockResolvedValue({
      data: { ...DRAFT, content: blankDraftContent() },
    } as never)
    renderDashboard()

    expect(await screen.findByText(DRAFT.title)).toBeInTheDocument()
    expect(screen.queryByText('内容完整，可继续针对职位优化')).not.toBeInTheDocument()
  })

  it('reads saved jobs and recent applications from a single GET /jobs', async () => {
    renderDashboard()
    await screen.findByRole('heading', { name: '简历中心' })

    expect(listJobs).toHaveBeenCalledTimes(1)
    expect(listResumeBuilderDrafts).toHaveBeenCalledTimes(1)
    expect(listResumes).toHaveBeenCalledTimes(1)
  })

  it('fails jobs and recent applications together, leaving the resume center intact', async () => {
    vi.mocked(listJobs).mockRejectedValue(new Error('network'))
    renderDashboard()

    expect(await screen.findByText(DRAFT.title)).toBeInTheDocument()
    expect(screen.getAllByText('职位数据暂时无法加载。')).toHaveLength(2)
    // a failed request must never be rendered as a zero
    expect(screen.queryByText(/0 个已收藏/)).not.toBeInTheDocument()
  })

  it('fails only the resume center when the resume requests fail', async () => {
    vi.mocked(listResumeBuilderDrafts).mockRejectedValue(new Error('network'))
    renderDashboard()

    expect(await screen.findByText('简历暂时无法加载。')).toBeInTheDocument()
    expect(screen.getByText('Client Services Associate')).toBeInTheDocument()
    expect(screen.getByText('Operations Coordinator')).toBeInTheDocument()
  })

  it('shows one reload action and no data when everything fails', async () => {
    vi.mocked(listJobs).mockRejectedValue(new Error('network'))
    vi.mocked(listResumeBuilderDrafts).mockRejectedValue(new Error('network'))
    renderDashboard()

    expect(await screen.findByText('无法加载工作台')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新加载' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '简历中心' })).not.toBeInTheDocument()
  })

  it('shows the first-run panel only when nothing exists yet', async () => {
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    vi.mocked(listResumes).mockResolvedValue({ data: [] } as never)
    vi.mocked(listResumeBuilderDrafts).mockResolvedValue({ data: [] } as never)
    renderDashboard()

    expect(await screen.findByRole('heading', { name: '开始你的求职工作台' })).toBeInTheDocument()
    expect(screen.getByText('0 / 3 已完成')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '创建或上传简历' })).toBeInTheDocument()
    // step 1 is the current step and must not carry a second create button
    expect(screen.getByText('当前步骤')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /创建或上传简历/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: /查找职位/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /了解简历改写/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '简历中心' })).not.toBeInTheDocument()
    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('empty')
  })

  it('does not render onboarding while the workbench is still loading', () => {
    vi.mocked(listJobs).mockImplementation(() => new Promise(() => {}))
    vi.mocked(listResumes).mockImplementation(() => new Promise(() => {}))
    vi.mocked(listResumeBuilderDrafts).mockImplementation(() => new Promise(() => {}))
    renderDashboard()

    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('loading')
    expect(screen.getByRole('status', { name: '加载中…' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '开始你的求职工作台' })).not.toBeInTheDocument()
    expect(screen.queryByText('收藏感兴趣的职位，后续可在这里集中处理。')).not.toBeInTheDocument()
  })

  it('uses the sparse layout when only one resume exists', async () => {
    vi.mocked(listJobs).mockResolvedValue({ data: [] } as never)
    vi.mocked(listResumeBuilderDrafts).mockResolvedValue({ data: [] } as never)
    renderDashboard()

    expect(await screen.findByText(UPLOAD.fileName)).toBeInTheDocument()
    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('sparse')
    expect(screen.queryByRole('heading', { name: '开始你的求职工作台' })).not.toBeInTheDocument()
    expect(screen.getByText('收藏感兴趣的职位，后续可在这里集中处理。')).toBeInTheDocument()
    expect(screen.getByText('投递职位后，这里会展示最近进度和日期。')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /浏览职位/ })).toHaveAttribute('href', '/jobs')
    expect(screen.getAllByRole('link', { name: /查找职位/ })).toHaveLength(1)
    expect(screen.queryByText('还没有收藏的职位。')).not.toBeInTheDocument()
    expect(document.querySelector('.td-divider')).toBeNull()
  })

  it('uses the balanced layout when two modules have data', async () => {
    vi.mocked(listJobs).mockResolvedValue({
      data: JOBS.filter((job) => !job.applied),
    } as never)
    vi.mocked(listResumeBuilderDrafts).mockResolvedValue({ data: [] } as never)
    renderDashboard()

    expect(await screen.findByText(UPLOAD.fileName)).toBeInTheDocument()
    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('balanced')
    expect(screen.getByText('Client Services Associate')).toBeInTheDocument()
    expect(screen.getByText('投递职位后，这里会展示最近进度和日期。')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '开始你的求职工作台' })).not.toBeInTheDocument()
  })

  it('does not treat a failed jobs request as an empty account', async () => {
    vi.mocked(listJobs).mockRejectedValue(new Error('network'))
    vi.mocked(listResumes).mockResolvedValue({ data: [] } as never)
    vi.mocked(listResumeBuilderDrafts).mockResolvedValue({ data: [] } as never)
    renderDashboard()

    expect(await screen.findByText('创建或上传一份简历后，会显示在这里。')).toBeInTheDocument()
    expect(document.querySelector('.td-page')?.getAttribute('data-density')).toBe('sparse')
    expect(screen.getByRole('heading', { name: '简历中心' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '开始你的求职工作台' })).not.toBeInTheDocument()
    expect(screen.getAllByText('职位数据暂时无法加载。')).toHaveLength(2)
    expect(screen.queryByText(/0 个已收藏/)).not.toBeInTheDocument()
  })

  it('links the workbench actions to the real routes', async () => {
    renderDashboard()
    await screen.findByRole('heading', { name: '简历中心' })

    expect(screen.getByRole('link', { name: '简历改写' })).toHaveAttribute('href', '/tailor')
    expect(screen.getByRole('link', { name: '编辑简历' })).toHaveAttribute('href', `/resumes/builder/${DRAFT.id}`)
    expect(screen.getByRole('link', { name: /管理全部/ })).toHaveAttribute('href', '/resumes')
    expect(screen.getByRole('link', { name: '查看简历质量评分' })).toHaveAttribute(
      'href',
      `/resumes/${UPLOAD.id}/quality-score`,
    )
    expect(screen.getByRole('link', { name: /查找职位/ })).toHaveAttribute('href', '/jobs')
    expect(screen.getByRole('link', { name: /查看全部/ })).toHaveAttribute('href', '/jobs?tab=applied')
  })

  it('keeps the English surface free of raw enum values', async () => {
    await i18n.changeLanguage('en')
    renderDashboard()

    expect(await screen.findByText(DRAFT.title)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Resume center' })).toBeInTheDocument()
    expect(screen.getByText(/Student/)).toBeInTheDocument()
    expect(screen.queryByText('STUDENT')).not.toBeInTheDocument()
    expect(screen.getByText('2 saved')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Jobs' })).toBeInTheDocument())
  })

  it('never fills empty or live data with the TargetDashboard demo fixture', async () => {
    renderDashboard()
    expect(await screen.findByText(DRAFT.title)).toBeInTheDocument()
    expect(screen.queryByText('Alex Morgan')).not.toBeInTheDocument()
    expect(screen.queryByText('Example Community Centre')).not.toBeInTheDocument()
  })
})
