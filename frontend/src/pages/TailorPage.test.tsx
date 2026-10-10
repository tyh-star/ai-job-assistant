import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import * as jobsApi from '../api/jobs'
import { canonicalizeResume, downloadByUrl, getResumeStructure, listResumes } from '../api/resumes'
import {
  answerProRewrite,
  confirmProRewrite,
  createFreeRewrite,
  previewProRewrite,
  startProRewrite,
} from '../api/resumeRewrite'
import type { FreeRewriteResponse, ProRewriteSession, ResumeResponse } from '../api/types'
import { proStepsDone } from '../rewrite/proSteps'
import { TailorPage } from './TailorPage'

vi.mock('../api/jobs', () => ({
  listJobs: vi.fn(),
  analyzeTailorFit: vi.fn(),
  tailorResume: vi.fn(),
  createTailorProposal: vi.fn(),
  scanEnhancedOpportunity: vi.fn(),
}))
vi.mock('../api/resumes', () => ({
  listResumes: vi.fn(),
  canonicalizeResume: vi.fn(),
  getResumeStructure: vi.fn(),
  downloadByUrl: vi.fn(),
}))
vi.mock('../api/resumeRewrite', () => ({
  createFreeRewrite: vi.fn(),
  startProRewrite: vi.fn(),
  answerProRewrite: vi.fn(),
  previewProRewrite: vi.fn(),
  confirmProRewrite: vi.fn(),
}))

const resume = {
  id: 12,
  fileName: '%E7%AE%80%E5%8E%86.docx',
  fileType: 'docx',
  fileAvailable: true,
  origin: 'UPLOAD',
} as ResumeResponse

const freeResult: FreeRewriteResponse = {
  resumeId: 12,
  model: 'openai/gpt-oss-20b',
  changes: [{ nodeId: 'p-4', originalText: 'worked on dashboards', revisedText: 'Built dashboards', reason: 'clearer' }],
  changeCount: 1,
  rejectedCount: 0,
  unchanged: false,
  downloadUrl: '/api/v1/resume-rewrites/free/files/r12-x.docx',
  downloadFileName: '简历_rewrite.docx',
}

function session(overrides: Partial<ProRewriteSession>): ProRewriteSession {
  return {
    sessionId: 100,
    status: 'ANALYZED',
    resumeId: 12,
    jobId: 30,
    model: 'openai/gpt-oss-120b',
    expiresAt: null,
    requirements: { requiredSkills: ['React'], preferredSkills: ['GraphQL'], responsibilities: [], keywords: [], experienceLevel: 'Mid' },
    items: [
      { id: 'R1', category: 'REQUIRED_SKILL', text: 'React', status: 'VERIFIED', evidenceNodeIds: ['p-4'], note: '' },
      { id: 'R2', category: 'PREFERRED_SKILL', text: 'GraphQL', status: 'INFERRED', evidenceNodeIds: [], note: '' },
      { id: 'R3', category: 'KEYWORD', text: 'Kubernetes', status: 'UNSUPPORTED', evidenceNodeIds: [], note: '' },
    ],
    questions: [{ id: 'Q1', requirementId: 'R2', question: 'Did you build a GraphQL API?', reason: '', hasExperience: null, detail: null }],
    suggestions: [],
    rejectedSuggestionCount: 0,
    finalChanges: [],
    downloadUrl: null,
    downloadFileName: null,
    ...overrides,
  }
}

function renderPage(path = '/tailor') {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <TailorPage />
      </MemoryRouter>
    </I18nextProvider>,
  )
}

async function chooseResume() {
  const user = userEvent.setup()
  await user.selectOptions(await screen.findByLabelText('选择简历'), '12')
  return user
}

describe('TailorPage (简历改写)', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(jobsApi.listJobs).mockResolvedValue({
      data: [{ id: 30, title: 'Frontend Engineer', company: 'Example Co' }],
    } as never)
    vi.mocked(createFreeRewrite).mockResolvedValue({ data: freeResult } as never)
  })

  afterEach(async () => {
    cleanup()
    await i18n.changeLanguage('zh-CN')
  })

  it('defaults to Free: only a resume selector and one primary action, no job selector', async () => {
    renderPage()
    expect(await screen.findByRole('radio', { name: /免费版/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('radio', { name: /专业版/ })).toHaveTextContent('付费')
    expect(screen.getByLabelText('选择简历')).toBeInTheDocument()
    expect(screen.queryByLabelText('选择目标职位')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成免费优化版' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /分析职位/ })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: '简历.docx' })).toBeInTheDocument()
  })

  it('Free calls only the free rewrite endpoint and never job matching, then offers the download', async () => {
    renderPage()
    const user = await chooseResume()
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))

    await waitFor(() => expect(createFreeRewrite).toHaveBeenCalledWith(12))
    expect(startProRewrite).not.toHaveBeenCalled()
    expect(jobsApi.analyzeTailorFit).not.toHaveBeenCalled()
    expect(jobsApi.tailorResume).not.toHaveBeenCalled()
    expect(jobsApi.createTailorProposal).not.toHaveBeenCalled()
    expect(await screen.findByText('Built dashboards')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '下载优化版简历' }))
    expect(downloadByUrl).toHaveBeenCalledWith('/api/v1/resume-rewrites/free/files/r12-x.docx', '简历_rewrite.docx')
  })

  it('prepares a PDF through its confirmed copy before rewriting', async () => {
    vi.mocked(listResumes).mockResolvedValue({ data: [{ ...resume, fileType: 'pdf', fileName: 'cv.pdf' }] } as never)
    vi.mocked(canonicalizeResume).mockResolvedValue({ data: { id: 41 } } as never)
    renderPage()
    const user = await chooseResume()
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))
    await waitFor(() => expect(createFreeRewrite).toHaveBeenCalledWith(12))
    expect(getResumeStructure).toHaveBeenCalledWith(12)
    expect(canonicalizeResume).toHaveBeenCalledWith(12)
  })

  it('Pro requires a job and uses the separate Pro endpoint', async () => {
    vi.mocked(startProRewrite).mockResolvedValue({ data: session({}) } as never)
    renderPage()
    const user = await chooseResume()
    await user.click(screen.getByRole('radio', { name: /专业版/ }))

    const analyze = screen.getByRole('button', { name: '分析职位要求' })
    expect(analyze).toBeDisabled()
    expect(screen.queryByRole('button', { name: '生成免费优化版' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('选择目标职位'), '30')
    await user.click(analyze)

    await waitFor(() => expect(startProRewrite).toHaveBeenCalledWith(12, 30))
    expect(createFreeRewrite).not.toHaveBeenCalled()
    expect(await screen.findByText('没有证据，不会写入')).toBeInTheDocument()
  })

  it('switching mode keeps the resume but clears the previous workflow', async () => {
    renderPage()
    const user = await chooseResume()
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))
    expect(await screen.findByText('Built dashboards')).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /专业版/ }))
    expect(screen.queryByText('Built dashboards')).not.toBeInTheDocument()
    expect((screen.getByLabelText('选择简历') as HTMLSelectElement).value).toBe('12')

    await user.click(screen.getByRole('radio', { name: /免费版/ }))
    expect(screen.queryByText('Built dashboards')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成免费优化版' })).toBeEnabled()
  })

  it('walks the Pro steps with exactly one primary action at a time; nothing is generated before confirming', async () => {
    const suggestion = {
      id: 'S1', nodeId: 'p-4', originalText: 'worked on dashboards', revisedText: 'Built React dashboards with a GraphQL API',
      reason: 'matches the role', requirementIds: ['R1', 'R2'], evidenceStatus: 'USER_CONFIRMED' as const,
    }
    vi.mocked(startProRewrite).mockResolvedValue({ data: session({}) } as never)
    vi.mocked(answerProRewrite).mockResolvedValue({ data: session({ status: 'ANSWERED' }) } as never)
    vi.mocked(previewProRewrite).mockResolvedValue({ data: session({ status: 'PREVIEWED', suggestions: [suggestion] }) } as never)
    vi.mocked(confirmProRewrite).mockResolvedValue({
      data: session({
        status: 'COMPLETED', suggestions: [suggestion],
        finalChanges: [{ suggestionId: 'S1', nodeId: 'p-4', originalText: 'worked on dashboards', finalText: 'Built React dashboards with a GraphQL API' }],
        downloadUrl: '/api/v1/resume-rewrites/pro/sessions/100/file', downloadFileName: '简历_pro-rewrite.docx',
      }),
    } as never)

    renderPage('/tailor?mode=pro&jobId=30')
    const user = await chooseResume()
    await user.click(screen.getByRole('button', { name: '分析职位要求' }))

    // Step 3: questions. Only "提交回答" is offered.
    const question = (await screen.findByText('Did you build a GraphQL API?')).closest('li')!
    expect(screen.queryByRole('button', { name: '生成修改建议' })).not.toBeInTheDocument()
    await user.click(within(question).getByLabelText('我有这方面经验'))
    const submit = screen.getByRole('button', { name: '提交回答' })
    expect(submit).toBeDisabled()
    await user.type(within(question).getByLabelText('简要说明你的实际经历'), 'I built the GraphQL API')
    await user.click(submit)
    await waitFor(() => expect(answerProRewrite).toHaveBeenCalledWith(100, [
      { questionId: 'Q1', hasExperience: true, detail: 'I built the GraphQL API' },
    ]))

    // Step 4: suggestions, still no file.
    await user.click(await screen.findByRole('button', { name: '生成修改建议' }))
    expect(await screen.findByText('Built React dashboards with a GraphQL API')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载定向简历' })).not.toBeInTheDocument()
    expect(confirmProRewrite).not.toHaveBeenCalled()

    // Step 5-6: confirm, then download.
    await user.click(screen.getByRole('button', { name: '确认并生成简历' }))
    await waitFor(() => expect(confirmProRewrite).toHaveBeenCalledWith(100, ['S1']))
    await user.click(await screen.findByRole('button', { name: '下载定向简历' }))
    expect(downloadByUrl).toHaveBeenCalledWith('/api/v1/resume-rewrites/pro/sessions/100/file', '简历_pro-rewrite.docx')
  })

  it('shows clear messages for rate limits, timeouts and provider failures', async () => {
    const failure = (code: string, status: number) => ({ response: { status, data: { code } } })
    renderPage()
    const user = await chooseResume()

    vi.mocked(createFreeRewrite).mockRejectedValueOnce(failure('AI_RATE_LIMITED', 429))
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))
    expect(await screen.findByText('AI 服务调用次数已达上限，请稍后再试。')).toBeInTheDocument()

    vi.mocked(createFreeRewrite).mockRejectedValueOnce(failure('AI_TIMEOUT', 504))
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))
    expect(await screen.findByText('AI 服务响应超时，请稍后重试。')).toBeInTheDocument()

    vi.mocked(createFreeRewrite).mockRejectedValueOnce(failure('AI_UPSTREAM_ERROR', 502))
    await user.click(screen.getByRole('button', { name: '生成免费优化版' }))
    expect(await screen.findByText('AI 服务暂时不可用，请稍后再试。')).toBeInTheDocument()
  })

  it('uses the same names and buttons in English', async () => {
    await i18n.changeLanguage('en')
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Resume Rewrite' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Free/ })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Pro/ })).toHaveTextContent('Paid')
    expect(screen.getByRole('button', { name: 'Generate free rewrite' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('radio', { name: /Pro/ }))
    expect(screen.getByRole('button', { name: 'Analyze job requirements' })).toBeInTheDocument()
  })
})

describe('proStepsDone', () => {
  it('maps session states to completed steps', () => {
    expect(proStepsDone(null)).toBe(0)
    expect(proStepsDone(session({}))).toBe(2)
    expect(proStepsDone(session({ questions: [] }))).toBe(3)
    expect(proStepsDone(session({ status: 'PREVIEWED' }))).toBe(4)
    expect(proStepsDone(session({ status: 'COMPLETED' }))).toBe(6)
  })
})
