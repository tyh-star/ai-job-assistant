import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import en from '../locales/en.json'
import zhCN from '../locales/zh-CN.json'
import {
  answerEnhancedQuestion,
  confirmTailorProposal,
  continueTailorProposal,
  createTailorProposal,
  getTailorProposal,
  listJobs,
  scanEnhancedOpportunity,
  tailorResume,
} from '../api/jobs'
import { listResumes, listResumeEvidence, deleteResumeEvidence, saveResumeEvidence } from '../api/resumes'
import type {
  JobResponse,
  ResumeResponse,
  TailoredResumeResponse,
  V3OpportunityMap,
} from '../api/types'
import { TailorPage } from './TailorPage'
import { sessionKey } from '../tailor/enhancedSession'

vi.mock('../api/resumes', () => ({
  listResumes: vi.fn(),
  getResumeStructure: vi.fn(),
  canonicalizeResume: vi.fn(),
  downloadByUrl: vi.fn(),
  listResumeEvidence: vi.fn(),
  saveResumeEvidence: vi.fn(),
  deleteResumeEvidence: vi.fn(),
}))

vi.mock('../api/jobs', () => ({
  listJobs: vi.fn(),
  tailorResume: vi.fn(),
  createTailorProposal: vi.fn(),
  confirmTailorProposal: vi.fn(),
  continueTailorProposal: vi.fn(),
  analyzeTailorFit: vi.fn(),
  refreshJobRequirementProfile: vi.fn(),
  scanEnhancedOpportunity: vi.fn(),
  answerEnhancedQuestion: vi.fn(),
  getTailorProposal: vi.fn(),
}))

const job: JobResponse = {
  id: 7,
  company: 'Acme',
  title: 'Engineer',
  location: 'Remote',
  jobUrl: 'https://example.com',
  description: 'Build things',
  source: 'MANUAL',
  status: 'SAVED',
  externalJobId: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const resume: ResumeResponse = {
  id: 9,
  fileName: 'cv.docx',
  fileType: 'docx',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  origin: 'UPLOAD',
  reviewStatus: 'READY',
  parentResumeId: null,
  canonicalResumeId: null,
  fileAvailable: true,
  downloadUrl: '/resumes/9/download',
}

const opportunity: V3OpportunityMap = {
  entries: [
    {
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      entryType: 'PROJECT',
      priority: 'HIGH',
      coveredRequirements: ['Java and Spring Boot', 'RESTful APIs'],
      partiallyCoveredRequirements: ['Deploy and maintain applications on AWS'],
      canStrengthenWithExistingFacts: ['AWS'],
      missingFactsThatCanBeConfirmed: ['ongoing maintenance after deploy'],
      cannotInvent: ['Kubernetes production operations'],
    },
    {
      canonicalEntryKey: 'PROJECT:smartpc-builder',
      entryTitle: 'SmartPC Builder',
      entryType: 'PROJECT',
      priority: 'MEDIUM',
      coveredRequirements: ['Java'],
      partiallyCoveredRequirements: ['Java and Spring Boot'],
      canStrengthenWithExistingFacts: [],
      missingFactsThatCanBeConfirmed: ['Spring Boot'],
      cannotInvent: [],
    },
  ],
  questions: [
    question(1, 'Prime Deals', 'PROJECT:prime-deals', 'maintain',
      'Prime Deals 部署到 AWS 后，你是否继续维护过这个应用？如果有，请说明具体做了什么，例如修复问题、更新依赖、监控或发布更新。'),
    question(2, 'Prime Deals', 'PROJECT:prime-deals', 'testing', '你是否为 Prime Deals 写过测试？'),
    question(3, 'SmartPC Builder', 'PROJECT:smartpc-builder', 'metric', '你是否量化过 SmartPC Builder 的结果？'),
    question(4, 'Prime Deals', 'PROJECT:prime-deals', 'data-store', 'Prime Deals 是否使用过数据库？'),
    question(5, 'SmartPC Builder', 'PROJECT:smartpc-builder', 'testing', '你是否为 SmartPC Builder 写过测试？'),
    question(6, 'Prime Deals', 'PROJECT:prime-deals', 'build-tool', 'Prime Deals 是否使用过构建工具？'),
  ],
  skippedQuestionIds: [],
  writerCalls: 0,
  openaiCalls: 0,
  wroteJobOrResume: false,
}

const primeOriginal = [
  { nodeId: 'p-14', text: 'Built a Java checkout service.' },
  { nodeId: 'p-15', text: 'Integrated RESTful APIs for cart operations.' },
  { nodeId: 'p-16', text: 'Stored order data in MySQL.' },
  { nodeId: 'p-17', text: 'Wrote unit tests for checkout.' },
  { nodeId: 'p-18', text: 'Deployed the project on AWS.' },
]
const primeProposed = [
  { nodeId: 'p-14', text: 'Built a Java Spring Boot checkout service.' },
  { nodeId: 'p-15', text: 'Integrated RESTful APIs for shopping cart operations.' },
  { nodeId: 'p-16', text: 'Stored checkout orders in MySQL.' },
  { nodeId: 'p-17', text: 'Wrote unit tests covering checkout flows.' },
  { nodeId: 'p-18', text: 'Deployed and maintained the project on AWS with weekly patching.' },
]

const enhancedProposal: TailoredResumeResponse = {
  resumeId: 9,
  jobId: 7,
  mode: 'CONSERVATIVE',
  productMode: 'ENHANCED',
  proposalId: 42,
  status: 'PENDING',
  unchanged: true,
  hasSuggestions: true,
  appliedChangeCount: 0,
  rejectedChangeCount: 0,
  rejectedChanges: [],
  changes: primeProposed.map((node, index) => ({
    nodeId: node.nodeId,
    originalText: primeOriginal[index].text,
    newText: node.text,
    reason: '',
    evidenceType: 'VERIFIED' as const,
    applied: false,
    requiresConfirmation: true,
  })),
  entryReviews: [
    {
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      originalNodes: primeOriginal,
      proposedNodes: primeProposed,
      whyBetter: 'Adds confirmed AWS maintenance that the job requires.',
      suggestionType: 'EVIDENCE_ENHANCED',
      usedEvidenceItemIds: [41],
    },
    {
      canonicalEntryKey: 'PROJECT:smartpc-builder',
      entryTitle: 'SmartPC Builder',
      originalNodes: [{ nodeId: 'p-10', text: 'Built a Java PC builder.' }],
      proposedNodes: [{ nodeId: 'p-10', text: 'Built a Java PC comparison tool.' }],
      whyBetter: 'Makes the Java comparison work easier to find.',
      suggestionType: 'JD_TARGETED',
      usedEvidenceItemIds: [],
    },
  ],
  evidenceUsage: [
    {
      evidenceItemId: 41,
      canonicalEntryKey: 'PROJECT:prime-deals',
      topicKey: 'maintain',
      usageStatus: 'USED_IN_ENTRY',
      usedInEntryKeys: ['PROJECT:prime-deals'],
      usedInSkillKeys: [],
    },
    {
      evidenceItemId: 50,
      canonicalEntryKey: 'PROJECT:prime-deals',
      topicKey: 'data-store',
      usageStatus: 'SAVED_NOT_USED',
      reasonCode: 'NO_ADMITTED_REWRITE',
      usedInEntryKeys: [],
      usedInSkillKeys: [],
    },
  ],
}

function question(
  index: number,
  title: string,
  key: string,
  topic: string,
  promptZh: string,
) {
  return {
    id: `eq-${key}-${topic}-${index}`,
    canonicalEntryKey: key,
    entryTitle: title,
    topic,
    promptEn: promptZh,
    promptZh,
    skipAllowed: true,
  }
}

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
        <TailorPage />
      </MemoryRouter>
    </I18nextProvider>,
  )
}

async function startEnhanced() {
  await userEvent.click(await screen.findByRole('radio', { name: /深度增强/ }))
  await userEvent.click(screen.getByRole('button', { name: '检查可增强内容' }))
  expect((await screen.findAllByText('Prime Deals')).length).toBeGreaterThan(0)
}

async function goToLastQuestion() {
  for (let i = 0; i < 6; i += 1) {
    const next = screen.queryByRole('button', { name: '下一题' })
    if (!next) {
      return
    }
    await userEvent.click(next)
  }
}

async function generateEnhanced() {
  await goToLastQuestion()
  await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
}

describe('TailorPage product modes', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    sessionStorage.clear()
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({ data: opportunity } as never)
    vi.mocked(answerEnhancedQuestion).mockResolvedValue({ data: { skipped: true } } as never)
    vi.mocked(createTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({
      data: [
        {
          id: 41, resumeId: 9, topic: 'maintain', topicKey: 'maintain', category: 'RESPONSIBILITY',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'maintained the AWS application with weekly patching',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
        {
          id: 50, resumeId: 9, topic: 'data-store', topicKey: 'data-store', category: 'HARD_SKILL',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'Used MySQL to store product and cart data in Prime Deals',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
      ],
    } as never)
    vi.mocked(getTailorProposal).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(tailorResume).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        engineVersion: 'V3',
        productMode: 'BASIC',
        status: 'APPLIED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        pdfExportStatus: 'SUCCESS',
        changes: [],
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
      },
    } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows Quick Optimize and Deep Enhancement without internal names', async () => {
    renderPage()
    expect(await screen.findByText('快速优化')).toBeInTheDocument()
    expect(screen.getByText('深度增强')).toBeInTheDocument()
    expect(screen.getByText('最佳效果')).toBeInTheDocument()
    expect(screen.queryByText(/V2/)).not.toBeInTheDocument()
    expect(screen.queryByText(/V3/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Fact Graph/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Evidence Bank/)).not.toBeInTheDocument()
    expect(screen.queryByText(/CONSERVATIVE/)).not.toBeInTheDocument()
    expect(screen.queryByText(/AGGRESSIVE/)).not.toBeInTheDocument()
    expect(screen.queryByText(/canonicalEntryKey/)).not.toBeInTheDocument()
    expect(screen.queryByText(/nodeId/)).not.toBeInTheDocument()
  })

  it('sends Quick Optimize through tailorResume and not the proposal path', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    await waitFor(() => expect(tailorResume).toHaveBeenCalledWith(7, 9, 'CONSERVATIVE'))
    expect(scanEnhancedOpportunity).not.toHaveBeenCalled()
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(continueTailorProposal).not.toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('scans first in Deep Enhancement and does not confirm', async () => {
    renderPage()
    await startEnhanced()
    expect(scanEnhancedOpportunity).toHaveBeenCalledWith(7, 9)
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
    expect(screen.getAllByText('部分匹配').length).toBeGreaterThan(0)
    expect(screen.getAllByText('已匹配').length).toBeGreaterThan(0)
    const smart = screen.getAllByText('SmartPC Builder')[0].closest('li')
    expect(smart).toBeTruthy()
    expect(within(smart as HTMLElement).queryByText(/RESTful APIs/)).not.toBeInTheDocument()
    expect(within(smart as HTMLElement).queryByText(/\bAWS\b/)).not.toBeInTheDocument()
    expect(screen.queryByText('Kubernetes production operations')).not.toBeInTheDocument()
    expect(screen.getByText(/没有简历或你确认的信息作为依据/)).toBeInTheDocument()
  })

  it('groups questions by entry and shows at most 5', async () => {
    renderPage()
    await startEnhanced()
    expect(screen.getByText('问题 1 / 5')).toBeInTheDocument()
    expect(screen.getByText(/Prime Deals 部署到 AWS 后/)).toBeInTheDocument()
    expect(screen.queryByText(/构建工具/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    expect(screen.getByText('问题 2 / 5')).toBeInTheDocument()
  })

  it('blocks CONFIRMED_USED without specific text', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'yes')
    await generateEnhanced()
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0)
  })

  it('allows not-used, skip, and all-skip proposal creation', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '没有做过' }))
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await generateEnhanced()
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledWith(7, 9, 'ENHANCED'))
    expect(answerEnhancedQuestion).toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
  })

  it('prevents double-submit of answers and keeps input when one fails', async () => {
    let resolveFirst: (value: unknown) => void = () => {}
    vi.mocked(answerEnhancedQuestion)
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveFirst = resolve as (value: unknown) => void
      }) as never)
      .mockRejectedValueOnce({ response: { status: 400, data: { message: 'answerText is required' } } })
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await goToLastQuestion()
    const generate = screen.getByRole('button', { name: '生成深度增强建议' })
    await userEvent.click(generate)
    await userEvent.click(generate)
    expect(answerEnhancedQuestion).toHaveBeenCalledTimes(1)
    resolveFirst({ data: { skipped: true } })
    expect((await screen.findAllByText(/这个问题保存失败|请填写真实经历/)).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Prime Deals').length).toBeGreaterThan(0)
    expect(createTailorProposal).not.toHaveBeenCalled()
  })

  it('shows full entry reviews selected by default and confirms selectedEntryKeys', async () => {
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    expect(await screen.findByText(/已选择 2 \/ 2 段增强内容/)).toBeInTheDocument()
    expect(screen.getByText('基于补充信息的增强')).toBeInTheDocument()
    expect(screen.getByText('职位定向优化')).toBeInTheDocument()
    expect(screen.getByText('Adds confirmed AWS maintenance that the job requires.')).toBeInTheDocument()
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes[0]).toBeChecked()
    await userEvent.click(boxes[1])
    vi.mocked(confirmTailorProposal).mockResolvedValue({
      data: {
        ...enhancedProposal,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        pdfExportStatus: 'SUCCESS',
      },
    } as never)
    await userEvent.click(screen.getByRole('button', { name: '确认并生成简历' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalled())
    const confirmArgs = vi.mocked(confirmTailorProposal).mock.calls[0]
    expect(confirmArgs[2]).toBeUndefined()
    expect(confirmArgs[3]).toBeUndefined()
    expect(confirmArgs[4]).toEqual({
      selectedEntryKeys: ['PROJECT:prime-deals'],
      editedEntries: [],
      selectedSkillReviewIds: [],
      editedSkillReviews: [],
    })
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    expect(screen.getByText(/深度增强简历已生成/)).toBeInTheDocument()
  })

  it('sends editedEntries, restores AI text, and keeps edits after confirm failure', async () => {
    vi.mocked(confirmTailorProposal).mockRejectedValueOnce({
      response: { status: 400, data: { message: 'newText is too long' } },
    })
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    await userEvent.click((await screen.findAllByRole('button', { name: '编辑建议' }))[0])
    const editor = screen.getByLabelText('编辑建议')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Built a Java Spring Boot checkout service used in production.')
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    expect(screen.getByText('已由你编辑')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '确认并生成简历' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalled())
    const extra = vi.mocked(confirmTailorProposal).mock.calls[0][4]
    expect(vi.mocked(confirmTailorProposal).mock.calls[0][2]).toBeUndefined()
    expect(extra?.selectedEntryKeys).toEqual(['PROJECT:prime-deals', 'PROJECT:smartpc-builder'])
    expect(extra?.editedEntries?.[0].canonicalEntryKey).toBe('PROJECT:prime-deals')
    expect(extra?.editedEntries?.[0].nodes.map((node) => node.nodeId)).toEqual([
      'p-14', 'p-15', 'p-16', 'p-17', 'p-18',
    ])
    expect(extra?.editedEntries?.[0].nodes).toHaveLength(5)
    expect(extra?.editedEntries?.[0].nodes[0].newText).toContain('used in production')
    expect(screen.getByText(/used in production/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '恢复 AI 建议' }))
    expect(screen.getByText(/Built a Java Spring Boot checkout service/)).toBeInTheDocument()
    expect(screen.queryByText(/used in production/)).not.toBeInTheDocument()
  })

  it('disables confirm when nothing is selected and prevents double confirm', async () => {
    let resolveConfirm: (value: unknown) => void = () => {}
    vi.mocked(confirmTailorProposal).mockImplementation(
      () => new Promise((resolve) => {
        resolveConfirm = resolve as (value: unknown) => void
      }) as never,
    )
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    const confirm = await screen.findByRole('button', { name: '确认并生成简历' })
    const boxes = screen.getAllByRole('checkbox')
    await userEvent.click(boxes[0])
    await userEvent.click(boxes[1])
    expect(confirm).toBeDisabled()
    await userEvent.click(confirm)
    expect(confirmTailorProposal).not.toHaveBeenCalled()
    await userEvent.click(boxes[0])
    await userEvent.click(confirm)
    await userEvent.click(confirm)
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalledTimes(1))
    resolveConfirm({
      data: {
        ...enhancedProposal,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
      },
    })
  })

  it('prompts to regenerate when a pending proposal expired', async () => {
    vi.mocked(confirmTailorProposal).mockRejectedValue({
      response: { status: 404, data: { message: 'Tailor proposal not found' } },
    })
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    await userEvent.click(await screen.findByRole('button', { name: '确认并生成简历' }))
    expect(await screen.findByText('建议已过期，请重新生成建议')).toBeInTheDocument()
  })

  it('restores a PENDING enhanced review after reload', async () => {
    sessionStorage.setItem(sessionKey('7', '9'), JSON.stringify({
      productMode: 'ENHANCED',
      phase: 'REVIEW',
      tailorResumeId: 9,
      opportunity,
      answers: {},
      questionIndex: 0,
      result: enhancedProposal,
      selectedEntryKeys: ['PROJECT:prime-deals'],
      suggestionText: { 'p-14': enhancedProposal.entryReviews![0].proposedNodes[0].text },
      aiOriginal: { 'p-14': enhancedProposal.entryReviews![0].proposedNodes[0].text },
    }))
    vi.mocked(getTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    renderPage()
    expect(await screen.findByText(/已选择 1 \/ 2 段增强内容/)).toBeInTheDocument()
    expect(screen.getAllByText('Prime Deals').length).toBeGreaterThan(0)
    expect(scanEnhancedOpportunity).not.toHaveBeenCalled()
  })

  it('still shows an old V2 proposal', async () => {
    sessionStorage.setItem(sessionKey('7', '9'), JSON.stringify({
      productMode: 'QUICK',
      phase: 'IDLE',
      tailorResumeId: 9,
      opportunity: null,
      answers: {},
      questionIndex: 0,
      result: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 8,
        status: 'PENDING',
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        changes: [{
          nodeId: 'p-23',
          originalText: 'Developed rate limiting.',
          newText: 'Developed IP rate-limiting for traffic.',
          improvementSummaryZh: '把限流写得更具体。',
          evidenceType: 'VERIFIED',
        }],
      },
      selectedEntryKeys: [],
    }))
    vi.mocked(getTailorProposal).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 8,
        status: 'PENDING',
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        changes: [{
          nodeId: 'p-23',
          originalText: 'Developed rate limiting.',
          newText: 'Developed IP rate-limiting for traffic.',
          improvementSummaryZh: '把限流写得更具体。',
          evidenceType: 'VERIFIED',
        }],
      },
    } as never)
    renderPage()
    expect(await screen.findByText('把限流写得更具体。')).toBeInTheDocument()
    expect(screen.getByText('原文')).toBeInTheDocument()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(tailorResume).not.toHaveBeenCalled()
  })
})

describe('TailorPage last-question submit', () => {
  const twoQuestions = { ...opportunity, questions: opportunity.questions.slice(0, 2) }
  const noQuestions = { ...opportunity, questions: [] }
  const usedText = 'patched dependencies and monitored AWS weekly'

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    sessionStorage.clear()
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({ data: twoQuestions } as never)
    vi.mocked(answerEnhancedQuestion).mockResolvedValue({ data: { skipped: true } } as never)
    vi.mocked(createTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({
      data: [
        {
          id: 41, resumeId: 9, topic: 'maintain', topicKey: 'maintain', category: 'RESPONSIBILITY',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'maintained the AWS application with weekly patching',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
        {
          id: 50, resumeId: 9, topic: 'data-store', topicKey: 'data-store', category: 'HARD_SKILL',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'Used MySQL to store product and cart data in Prime Deals',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
      ],
    } as never)
    vi.mocked(getTailorProposal).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(tailorResume).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        engineVersion: 'V3',
        productMode: 'BASIC',
        status: 'APPLIED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        pdfExportStatus: 'SUCCESS',
        changes: [],
        unchanged: true,
        appliedChangeCount: 3,
        rejectedChangeCount: 1,
        rejectedChanges: [],
      },
    } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows next on a non-last question', async () => {
    renderPage()
    await startEnhanced()
    expect(screen.getByText('问题 1 / 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一题' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '生成深度增强建议' })).not.toBeInTheDocument()
    expect(screen.getByText('回答已暂存在当前页面。点击“生成深度增强建议”后才会提交。')).toBeInTheDocument()
  })

  it('does not show a disabled next on the last question', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    expect(screen.getByText('问题 2 / 2')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下一题' })).not.toBeInTheDocument()
  })

  it('shows generate on the last question', async () => {
    renderPage()
    await startEnhanced()
    await goToLastQuestion()
    const generate = screen.getByRole('button', { name: '生成深度增强建议' })
    expect(generate).toBeEnabled()
    expect(screen.getByRole('button', { name: '上一题' })).toBeEnabled()
  })

  it('submits every answer then creates an ENHANCED proposal', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), usedText)
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'wrote unit tests for checkout flows')
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(answerEnhancedQuestion).toHaveBeenCalledTimes(2))
    expect(answerEnhancedQuestion).toHaveBeenNthCalledWith(1, 7, 9, {
      questionId: twoQuestions.questions[0].id,
      answerStatus: 'CONFIRMED_USED',
      answerText: usedText,
      entryKey: 'PROJECT:prime-deals',
    })
    expect(answerEnhancedQuestion).toHaveBeenNthCalledWith(2, 7, 9, {
      questionId: twoQuestions.questions[1].id,
      answerStatus: 'CONFIRMED_USED',
      answerText: 'wrote unit tests for checkout flows',
      entryKey: 'PROJECT:prime-deals',
    })
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledTimes(1))
    expect(createTailorProposal).toHaveBeenCalledWith(7, 9, 'ENHANCED')
    expect(scanEnhancedOpportunity).toHaveBeenCalledTimes(2)
    expect(scanEnhancedOpportunity.mock.invocationCallOrder[1])
      .toBeGreaterThan(answerEnhancedQuestion.mock.invocationCallOrder[1])
    expect(createTailorProposal.mock.invocationCallOrder[0])
      .toBeGreaterThan(scanEnhancedOpportunity.mock.invocationCallOrder[1])
  })

  it('blocks CONFIRMED_USED without specific text', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '生成深度增强建议' })).toBeEnabled()
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0)
  })

  it('submits CONFIRMED_NOT_USED without text', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '没有做过' }))
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '没有做过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(answerEnhancedQuestion).toHaveBeenCalledTimes(2))
    expect(answerEnhancedQuestion).toHaveBeenCalledWith(7, 9, {
      questionId: twoQuestions.questions[0].id,
      answerStatus: 'CONFIRMED_NOT_USED',
      answerText: undefined,
      entryKey: 'PROJECT:prime-deals',
    })
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledWith(7, 9, 'ENHANCED'))
  })

  it('submits SKIPPED without text', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(answerEnhancedQuestion).toHaveBeenCalledTimes(2))
    expect(answerEnhancedQuestion).toHaveBeenCalledWith(7, 9, {
      questionId: twoQuestions.questions[1].id,
      answerStatus: 'SKIPPED',
      answerText: undefined,
      entryKey: 'PROJECT:prime-deals',
    })
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledWith(7, 9, 'ENHANCED'))
  })

  it('submits answers and the proposal only once when the final button is double-clicked', async () => {
    let resolveAnswer: (value: unknown) => void = () => {}
    vi.mocked(answerEnhancedQuestion)
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveAnswer = resolve as (value: unknown) => void
      }) as never)
      .mockResolvedValue({ data: { skipped: true } } as never)
    renderPage()
    await startEnhanced()
    await goToLastQuestion()
    const generate = screen.getByRole('button', { name: '生成深度增强建议' })
    await userEvent.click(generate)
    await userEvent.click(generate)
    await waitFor(() => expect(generate).toBeDisabled())
    await waitFor(() => expect(screen.getByRole('button', { name: '有，我做过' })).toBeDisabled())
    expect(answerEnhancedQuestion).toHaveBeenCalledTimes(1)
    expect(createTailorProposal).not.toHaveBeenCalled()
    resolveAnswer({ data: { skipped: true } })
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledTimes(1))
  })

  it('keeps answers and stays in Deep Enhancement when submitting answers fails', async () => {
    vi.mocked(answerEnhancedQuestion).mockRejectedValue({
      response: { status: 400, data: { message: 'answerText is required' } },
    })
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), usedText)
    await goToLastQuestion()
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByText(/这个问题保存失败/)).toBeInTheDocument()
    expect(screen.getByLabelText('请说明你具体做了什么')).toHaveValue(usedText)
    expect(screen.getByRole('button', { name: '下一题' })).toBeEnabled()
    expect(screen.getByRole('radio', { name: /深度增强/ })).toBeChecked()
    await goToLastQuestion()
    expect(screen.getByRole('button', { name: '生成深度增强建议' })).toBeEnabled()
    expect(createTailorProposal).not.toHaveBeenCalled()
  })

  it('keeps answers when metric evidence save returns EVIDENCE_TOPIC_MISMATCH', async () => {
    vi.mocked(answerEnhancedQuestion).mockRejectedValue({
      response: { status: 400, data: { message: 'EVIDENCE_TOPIC_MISMATCH' } },
    })
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), usedText)
    await goToLastQuestion()
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByText(/这个问题保存失败/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '上一题' }))
    expect(screen.getByLabelText('请说明你具体做了什么')).toHaveValue(usedText)
    expect(screen.getByRole('radio', { name: /深度增强/ })).toBeChecked()
    expect(createTailorProposal).not.toHaveBeenCalled()
  })

  it('creates an ENHANCED proposal when there are no questions', async () => {
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({ data: noQuestions } as never)
    renderPage()
    await startEnhanced()
    expect(screen.queryByText('问题 0 / 0')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下一题' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledTimes(1))
    expect(createTailorProposal).toHaveBeenCalledWith(7, 9, 'ENHANCED')
  })

  it('hides the previous Quick Optimize result after Deep Enhancement starts', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByText('已应用 3 条')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    await startEnhanced()
    expect(screen.queryByRole('button', { name: '下载 DOCX' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载 PDF' })).not.toBeInTheDocument()
    expect(screen.queryByText('已应用 3 条')).not.toBeInTheDocument()
    expect(screen.queryByText('已拒绝 1 条')).not.toBeInTheDocument()
    expect(screen.getByText('问题 1 / 2')).toBeInTheDocument()
  })

  it('clears the previous result when a new Quick Optimize starts', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    let resolveSecond: (value: unknown) => void = () => {}
    vi.mocked(tailorResume).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveSecond = resolve as (value: unknown) => void
      }) as never,
    )
    await userEvent.click(screen.getByRole('button', { name: '生成定向建议' }))
    expect(screen.queryByRole('button', { name: '下载 DOCX' })).not.toBeInTheDocument()
    expect(screen.queryByText('已应用 3 条')).not.toBeInTheDocument()
    resolveSecond({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        engineVersion: 'V3',
        productMode: 'BASIC',
        status: 'APPLIED',
        downloadUrl: '/api/v1/jobs/7/tailored/second.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/second.pdf',
        pdfExportStatus: 'SUCCESS',
        changes: [],
        unchanged: false,
        appliedChangeCount: 1,
        rejectedChangeCount: 0,
        rejectedChanges: [],
      },
    })
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByText('已应用 1 条')).toBeInTheDocument()
  })

  it('shows the current-round review and completed files', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    await startEnhanced()
    await generateEnhanced()
    expect(await screen.findByText(/已选择 2 \/ 2 段增强内容/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载 DOCX' })).not.toBeInTheDocument()
    expect(screen.queryByText('已应用 3 条')).not.toBeInTheDocument()
    vi.mocked(confirmTailorProposal).mockResolvedValue({
      data: {
        ...enhancedProposal,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/enhanced.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/enhanced.pdf',
        pdfExportStatus: 'SUCCESS',
        appliedChangeCount: 1,
        rejectedChangeCount: 0,
      },
    } as never)
    await userEvent.click(screen.getByRole('button', { name: '确认并生成简历' }))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    expect(screen.getByText(/深度增强简历已生成/)).toBeInTheDocument()
  })

  it('splits Viva requirements into matched, partial, and not-yet-confirmed without duplicate bullets', async () => {
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({
      data: {
        ...opportunity,
        entries: [
          {
            canonicalEntryKey: 'PROJECT:prime-deals',
            entryTitle: 'Prime Deals',
            entryType: 'PROJECT',
            priority: 'HIGH',
            coveredRequirements: ['• • already covered', '-', ''],
            partiallyCoveredRequirements: ['JavaScript skills and API/event contract design'],
            canStrengthenWithExistingFacts: [],
            missingFactsThatCanBeConfirmed: ['Nx'],
            cannotInvent: [],
            missingRequirements: ['Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)'],
            requirements: [
              {
                requirementId: 'req-js',
                requirementText: 'JavaScript skills and API/event contract design',
                status: 'PARTIALLY_COVERED',
                coveredAtoms: ['JavaScript'],
                missingAtoms: ['API contract design', 'event contract design'],
                alternativeGroups: [['API contract design', 'event contract design']],
              },
              {
                requirementId: 'req-nx',
                requirementText: 'Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)',
                status: 'MISSING',
                coveredAtoms: [],
                missingAtoms: ['Nx', 'monorepo'],
                alternativeGroups: [],
              },
              {
                requirementId: 'req-kafka',
                requirementText: 'Experience with event-driven systems (Kafka or equivalent), including retries, idempotency, and schema/version evolution',
                status: 'MISSING',
                coveredAtoms: [],
                missingAtoms: ['Kafka'],
                alternativeGroups: [],
              },
            ],
          },
          {
            canonicalEntryKey: 'PROJECT:smartpc-builder',
            entryTitle: 'SmartPC Builder',
            entryType: 'PROJECT',
            priority: 'NO_ACTION',
            coveredRequirements: ['JavaScript skills and API/event contract design'],
            partiallyCoveredRequirements: [],
            canStrengthenWithExistingFacts: [],
            missingFactsThatCanBeConfirmed: [],
            cannotInvent: [],
          },
        ],
        questions: [
          {
            id: 'eq-skill-req-js-api-contract',
            canonicalEntryKey: 'PROJECT:prime-deals',
            entryTitle: 'Prime Deals',
            topic: 'api-contract',
            promptEn: 'Have you actually designed an API or event contract?',
            promptZh: '你是否实际设计过 API 或事件契约？如果有，请说明在哪个项目中，以及是否涉及请求/响应结构、版本管理或 schema；没有可以选择“没有做过”。',
            skipAllowed: true,
            requirementId: 'req-js',
            requirementText: 'JavaScript skills and API/event contract design',
            allowedEntryKeys: ['PROJECT:prime-deals', 'PROJECT:smartpc-builder'],
          },
        ],
      },
    } as never)
    renderPage()
    await startEnhanced()
    const prime = screen.getAllByText('Prime Deals')[0].closest('li') as HTMLElement
    expect(within(prime).getByText('部分匹配')).toBeInTheDocument()
    expect(within(prime).getByText('尚未确认')).toBeInTheDocument()
    expect(within(prime).getByText(/JavaScript skills and API\/event contract design/)).toBeInTheDocument()
    expect(within(prime).getByText(/尚未确认的要点: API contract design/)).toBeInTheDocument()
    expect(within(prime).queryByText('已匹配')).not.toBeInTheDocument()
    expect(screen.queryByText('• • already covered')).not.toBeInTheDocument()
    expect(within(prime).queryByText(/^-$/)).not.toBeInTheDocument()
    expect(screen.queryByText('topicKey')).not.toBeInTheDocument()
    expect(screen.queryByText('reasonCode')).not.toBeInTheDocument()
    expect(screen.getByText(/你是否实际设计过 API 或事件契约/)).toBeInTheDocument()
    expect(screen.getByText('选择对应的项目或经历')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('选择对应的项目或经历'), 'PROJECT:smartpc-builder')
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), '在 SmartPC 设计过请求响应契约并做版本管理')
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(answerEnhancedQuestion).toHaveBeenCalled())
    expect(vi.mocked(answerEnhancedQuestion).mock.calls[0][2]).toMatchObject({
      questionId: 'eq-skill-req-js-api-contract',
      answerStatus: 'CONFIRMED_USED',
      entryKey: 'PROJECT:smartpc-builder',
    })
  })

  it('keeps 有 / 没有 / 跳过 and retains answers after a failed Viva skill-gap save', async () => {
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({
      data: {
        ...opportunity,
        questions: [
          {
            id: 'eq-skill-req-nx-nx-monorepo',
            canonicalEntryKey: 'PROJECT:prime-deals',
            entryTitle: 'Prime Deals',
            topic: 'nx-monorepo',
            promptEn: 'Have you actually used an Nx monorepo?',
            promptZh: '你是否实际使用过 Nx monorepo？如果有，请说明在哪个项目中，以及是否使用过 shared libraries、dependency graph 或 affected builds/tests；没有可以选择“没有做过”。',
            skipAllowed: true,
            requirementId: 'req-nx',
            requirementText: 'Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)',
            allowedEntryKeys: ['PROJECT:prime-deals', 'PROJECT:smartpc-builder'],
          },
        ],
      },
    } as never)
    vi.mocked(answerEnhancedQuestion)
      .mockRejectedValueOnce({ response: { status: 400, data: { message: 'too vague' } } })
      .mockResolvedValueOnce({ data: { skipped: true } } as never)
    renderPage()
    await startEnhanced()
    expect(screen.getByRole('button', { name: '有，我做过' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '没有做过' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '暂时跳过' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), '在 Prime Deals 用过 Nx monorepo 和 shared libraries')
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByText(/这个问题保存失败/)).toBeInTheDocument()
    expect(screen.getByLabelText('请说明你具体做了什么')).toHaveValue('在 Prime Deals 用过 Nx monorepo 和 shared libraries')
    await userEvent.click(screen.getByRole('button', { name: '没有做过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(answerEnhancedQuestion).toHaveBeenCalledTimes(2))
    expect(vi.mocked(answerEnhancedQuestion).mock.calls[1][2]).toMatchObject({
      answerStatus: 'CONFIRMED_NOT_USED',
      questionId: 'eq-skill-req-nx-nx-monorepo',
    })
  })
})

describe('product copy', () => {
  it('includes required Chinese and English strings', () => {
    expect(zhCN.tailor.quickOptimize).toBe('快速优化')
    expect(zhCN.tailor.deepEnhance).toBe('深度增强')
    expect(zhCN.tailor.bestResults).toBe('最佳效果')
    expect(zhCN.tailor.enhanced.stepScan).toBe('检查可增强内容')
    expect(zhCN.tailor.enhanced.stepQuestions).toBe('补充关键信息')
    expect(zhCN.tailor.enhanced.stepReview).toBe('审阅增强建议')
    expect(zhCN.tailor.enhanced.stepGenerate).toBe('生成简历')
    expect(zhCN.tailor.yesIDid).toBe('有，我做过')
    expect(zhCN.tailor.notUsed).toBe('没有做过')
    expect(zhCN.tailor.skipForNow).toBe('暂时跳过')
    expect(zhCN.tailor.restoreAi).toBe('恢复 AI 建议')
    expect(zhCN.tailor.editedByYou).toBe('已由你编辑')
    expect(zhCN.tailor.enhanced.partial).toBe('部分匹配')
    expect(zhCN.tailor.enhanced.covered).toBe('已匹配')
    expect(zhCN.tailor.enhanced.missing).toBe('尚未确认')
    expect(zhCN.tailor.enhanced.confirmGenerate).toBe('确认并生成简历')
    expect(zhCN.tailor.enhanced.generateProposal).toBe('生成深度增强建议')
    expect(zhCN.tailor.enhanced.nextQuestion).toBe('下一题')
    expect(zhCN.tailor.enhanced.previousQuestion).toBe('上一题')
    expect(zhCN.tailor.enhanced.answersStaged).toBe('回答已暂存在当前页面。点击“生成深度增强建议”后才会提交。')
    expect(zhCN.tailor.enhanced.answersSubmitted).toBe('回答已提交，正在生成增强建议。')
    expect(zhCN.tailor.enhanced.noHighValue).toContain('没有发现明显优于原文')
    expect(zhCN.tailor.enhanced.usageSummary).toContain('已保存 {{saved}} 条补充信息')
    expect(zhCN.tailor.enhanced.skillsUpdate).toBe('技能更新')
    expect(zhCN.tailor.enhanced.deleteConfirm).toContain('确定删除这条已保存的补充信息')
    expect(en.tailor.quickOptimize).toBe('Quick Optimize')
    expect(en.tailor.deepEnhance).toBe('Deep Enhancement')
    expect(en.tailor.bestResults).toBe('Best Results')
    expect(en.tailor.enhanced.generateProposal).toBe('Generate deep enhancement suggestions')
    expect(en.tailor.enhanced.nextQuestion).toBe('Next question')
    expect(en.tailor.enhanced.previousQuestion).toBe('Previous question')
    expect(en.tailor.enhanced.answersStaged).toContain('Generate deep enhancement suggestions')
    expect(en.tailor.enhanced.answersSubmitted).toBe('Answers were submitted. Generating enhancement suggestions.')
  })
})

describe('TailorPage V3-3E evidence usage and skills', () => {
  const skillsProposal: TailoredResumeResponse = {
    ...enhancedProposal,
    skillReviews: [
      {
        skillReviewId: 'skills:technical',
        originalSkills: 'Java, Python, SQL',
        proposedSkills: 'Java, Python, SQL, MySQL',
        addedSkills: ['MySQL'],
        evidenceItemIds: [50],
        whyBetter: 'Adds MySQL from your confirmed Prime Deals detail.',
        admitted: true,
        proposedNodes: [{ nodeId: 'p-11', text: 'Java, Python, SQL, MySQL' }],
      },
    ],
    evidenceUsage: [
      {
        evidenceItemId: 41,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'maintain',
        usageStatus: 'USED_IN_ENTRY',
        usedInEntryKeys: ['PROJECT:prime-deals'],
        usedInSkillKeys: [],
      },
      {
        evidenceItemId: 50,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'data-store',
        usageStatus: 'USED_IN_SKILLS',
        usedInEntryKeys: [],
        usedInSkillKeys: ['MySQL'],
      },
    ],
  }

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    sessionStorage.clear()
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({ data: opportunity } as never)
    vi.mocked(answerEnhancedQuestion).mockResolvedValue({ data: { skipped: true } } as never)
    vi.mocked(createTailorProposal).mockResolvedValue({ data: skillsProposal } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({
      data: [
        {
          id: 41, resumeId: 9, topic: 'maintain', topicKey: 'maintain', category: 'RESPONSIBILITY',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'maintained the AWS application with weekly patching',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
        {
          id: 50, resumeId: 9, topic: 'data-store', topicKey: 'data-store', category: 'HARD_SKILL',
          answerStatus: 'CONFIRMED_USED', entryKey: 'PROJECT:prime-deals', entryLabel: 'Prime Deals',
          relatedNodeIds: [], context: '', action: 'Used MySQL to store product and cart data in Prime Deals',
          result: '', metric: '', provenance: 'USER_CONFIRMED', createdAt: '', updatedAt: '',
        },
      ],
    } as never)
    vi.mocked(getTailorProposal).mockRejectedValue({ response: { status: 404 } })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows evidence usage summary and unused reason without topic keys', async () => {
    vi.mocked(createTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    expect(await screen.findByText(/已保存 2 条补充信息：1 条用于增强；1 条暂未生成明显优于原文的版本。/)).toBeInTheDocument()
    expect(screen.getByText('暂未生成明显优于原文的版本。')).toBeInTheDocument()
    expect(screen.queryByText(/metric\./)).not.toBeInTheDocument()
    expect(screen.queryByText('data-store')).not.toBeInTheDocument()
  })

  it('reviews skills, cancels, edits, restores, and omits unselected skills from confirm', async () => {
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    expect(await screen.findByText('技能更新')).toBeInTheDocument()
    expect(screen.getByText('原 Skills')).toBeInTheDocument()
    expect(screen.getByText(/新增技能：MySQL/)).toBeInTheDocument()
    const skillBox = screen.getAllByRole('checkbox').at(-1)!
    expect(skillBox).toBeChecked()
    await userEvent.click(screen.getAllByRole('button', { name: '编辑建议' }).at(-1)!)
    const editor = screen.getByLabelText('编辑建议')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Java, Python, SQL, MySQL, Redis')
    await userEvent.click(screen.getByRole('button', { name: '取消编辑' }))
    expect(screen.queryByText(/Redis/)).not.toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: '编辑建议' }).at(-1)!)
    await userEvent.clear(screen.getByLabelText('编辑建议'))
    await userEvent.type(screen.getByLabelText('编辑建议'), 'Java, Python, SQL, MySQL, Redis')
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    expect(screen.getByText('已由你编辑')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '恢复 AI 建议' }))
    expect(screen.queryByText(/Redis/)).not.toBeInTheDocument()
    await userEvent.click(skillBox)
    vi.mocked(confirmTailorProposal).mockResolvedValue({
      data: { ...skillsProposal, status: 'CONFIRMED', downloadUrl: '/x.docx' },
    } as never)
    await userEvent.click(screen.getByRole('button', { name: '确认并生成简历' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalled())
    const extra = vi.mocked(confirmTailorProposal).mock.calls[0][4]
    expect(extra?.selectedSkillReviewIds).toEqual([])
    expect(extra?.selectedNodeIds).toBeUndefined()
    expect(vi.mocked(confirmTailorProposal).mock.calls[0][2]).toBeUndefined()
  })

  it('asks for delete confirmation and keeps the page when delete fails', async () => {
    vi.mocked(createTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    vi.mocked(deleteResumeEvidence).mockRejectedValue({
      response: { status: 404, data: { message: 'Resume not found' } },
    })
    renderPage()
    await startEnhanced()
    await generateEnhanced()
    expect(await screen.findByText(/已保存 2 条补充信息/)).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: '删除' })[0])
    expect(screen.getByText(/确定删除这条已保存的补充信息/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(deleteResumeEvidence).not.toHaveBeenCalled()
    await userEvent.click(screen.getAllByRole('button', { name: '删除' })[0])
    const confirmBox = screen.getByText(/确定删除这条已保存的补充信息/).closest('div')
    await userEvent.click(within(confirmBox!).getByRole('button', { name: '删除' }))
    await waitFor(() => expect(deleteResumeEvidence).toHaveBeenCalled())
    expect(screen.getByText(/已保存 2 条补充信息/)).toBeInTheDocument()
    expect(screen.getAllByText('Prime Deals').length).toBeGreaterThan(0)
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})

describe('TailorPage V3-3G map refresh and usage', () => {
  const nx = 'Practical experience with NX monorepos (shared libs, dependency graph, affected builds/tests)'
  const contract = 'JavaScript skills and API/event contract design'
  const kafka = 'Experience with event-driven systems (Kafka or equivalent), including retries, idempotency, and schema/version evolution'
  const beforeMap: V3OpportunityMap = {
    ...opportunity,
    questions: opportunity.questions.slice(0, 2),
    entries: opportunity.entries.map((entry) => (
      entry.canonicalEntryKey === 'PROJECT:prime-deals'
        ? {
          ...entry,
          coveredRequirements: [],
          partiallyCoveredRequirements: [nx, contract],
          missingRequirements: [kafka],
          requirements: [
            {
              requirementId: 'nx',
              requirementText: nx,
              status: 'PARTIALLY_COVERED',
              coveredAtoms: ['shared libraries'],
              missingAtoms: ['Nx'],
            },
            {
              requirementId: 'contract',
              requirementText: contract,
              status: 'PARTIALLY_COVERED',
              coveredAtoms: ['JavaScript'],
              missingAtoms: ['API contract'],
            },
            {
              requirementId: 'kafka',
              requirementText: kafka,
              status: 'MISSING',
              coveredAtoms: [],
              missingAtoms: ['Kafka'],
            },
          ],
        }
        : entry
    )),
  }
  const afterMap: V3OpportunityMap = {
    ...beforeMap,
    questions: [],
    entries: beforeMap.entries.map((entry) => (
      entry.canonicalEntryKey === 'PROJECT:prime-deals'
        ? {
          ...entry,
          coveredRequirements: [nx, contract, kafka],
          partiallyCoveredRequirements: [],
          missingRequirements: [],
          missingFactsThatCanBeConfirmed: [],
          requirements: [
            {
              requirementId: 'nx',
              requirementText: nx,
              status: 'COVERED',
              coveredAtoms: ['Nx', 'shared libraries', 'dependency graph'],
              missingAtoms: [],
            },
            {
              requirementId: 'contract',
              requirementText: contract,
              status: 'COVERED',
              coveredAtoms: ['JavaScript', 'API contract'],
              missingAtoms: [],
            },
            {
              requirementId: 'kafka',
              requirementText: kafka,
              status: 'COVERED',
              coveredAtoms: ['Kafka', 'retries', 'idempotency'],
              missingAtoms: [],
            },
          ],
        }
        : entry
    )),
  }
  const skillsOriginal = '● Programming Languages: C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS, IntelliJ, Eclipse, PyCharm, GitHub, DBeaver, VScode, MySQL, Windows, Unix/Linux, Mac OS'
  const skillsProposed = `${skillsOriginal}, Kafka, REST APIs, TypeScript, Nx`
  const gProposal: TailoredResumeResponse = {
    ...enhancedProposal,
    entryReviews: [
      {
        canonicalEntryKey: 'PROJECT:prime-deals',
        entryTitle: 'Prime Deals',
        originalNodes: primeOriginal,
        proposedNodes: primeProposed,
        whyBetter: 'Incorporates your confirmed Nx, API contract, and Kafka experience to better match the role’s key requirements.',
        whyBetterZh: '结合了你确认的 Nx、API 契约和 Kafka 经历，使项目与目标职位的关键要求更加匹配。',
        suggestionType: 'EVIDENCE_ENHANCED',
        usedEvidenceItemIds: [61, 62, 63],
      },
    ],
    skillReviews: [
      {
        skillReviewId: 'skills:technical',
        originalSkills: skillsOriginal,
        proposedSkills: skillsProposed,
        addedSkills: ['Kafka', 'REST APIs', 'TypeScript', 'Nx'],
        evidenceItemIds: [61, 62, 63],
        whyBetter: 'Adds Kafka, REST APIs, TypeScript, Nx from your confirmed Prime Deals detail.',
        admitted: true,
        proposedNodes: [{ nodeId: 'p-11', text: skillsProposed }],
      },
    ],
    evidenceUsage: [
      {
        evidenceItemId: 61,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'nx-monorepo',
        usageStatus: 'USED_IN_ENTRY_AND_SKILLS',
        usedInEntryKeys: ['PROJECT:prime-deals'],
        usedInSkillKeys: ['Nx', 'TypeScript'],
      },
      {
        evidenceItemId: 62,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'api-contract',
        usageStatus: 'USED_IN_ENTRY_AND_SKILLS',
        usedInEntryKeys: ['PROJECT:prime-deals'],
        usedInSkillKeys: ['REST APIs'],
      },
      {
        evidenceItemId: 63,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'event-driven',
        usageStatus: 'USED_IN_ENTRY_AND_SKILLS',
        usedInEntryKeys: ['PROJECT:prime-deals'],
        usedInSkillKeys: ['Kafka'],
      },
      {
        evidenceItemId: 64,
        canonicalEntryKey: 'PROJECT:prime-deals',
        topicKey: 'metric',
        usageStatus: 'SAVED_NOT_USED',
        reasonCode: 'CONFIRMED_FACT_NOT_USED',
        usedInEntryKeys: [],
        usedInSkillKeys: [],
      },
      {
        evidenceItemId: 65,
        canonicalEntryKey: 'PROJECT:smartpc-builder',
        topicKey: 'metric',
        usageStatus: 'USED_IN_SKILLS',
        usedInEntryKeys: [],
        usedInSkillKeys: ['Kafka'],
      },
    ],
  }

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    sessionStorage.clear()
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(scanEnhancedOpportunity)
      .mockResolvedValueOnce({ data: beforeMap } as never)
      .mockResolvedValue({ data: afterMap } as never)
    vi.mocked(answerEnhancedQuestion).mockResolvedValue({ data: { skipped: true } } as never)
    vi.mocked(createTailorProposal).mockResolvedValue({ data: gProposal } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(getTailorProposal).mockRejectedValue({ response: { status: 404 } })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('rescans the Opportunity Map after answers then creates the proposal', async () => {
    renderPage()
    await startEnhanced()
    expect(screen.getAllByText('尚未确认').length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'used Nx monorepo with shared TypeScript libraries')
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'designed REST API contracts with JSON and /api/v1')
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    await waitFor(() => expect(createTailorProposal).toHaveBeenCalledTimes(1))
    expect(scanEnhancedOpportunity).toHaveBeenCalledTimes(2)
    expect(screen.getAllByText('已匹配').length).toBeGreaterThan(0)
    expect(screen.queryByText(kafka)).toBeInTheDocument()
    const prime = screen.getAllByText('Prime Deals')[0].closest('li')
    expect(within(prime as HTMLElement).queryByText('尚未确认')).not.toBeInTheDocument()
  })

  it('counts real usage statuses and does not call skills-only evidence used in a project', async () => {
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByText(/已保存 5 条补充信息：4 条用于增强；1 条暂未生成明显优于原文的版本。/)).toBeInTheDocument()
    expect(screen.getByText('已确认的补充信息未进入最终增强文本。')).toBeInTheDocument()
    expect(screen.getByText('用于技能（Kafka）')).toBeInTheDocument()
    expect(screen.queryByText('event-driven.')).not.toBeInTheDocument()
    expect(screen.getByText('结合了你确认的 Nx、API 契约和 Kafka 经历，使项目与目标职位的关键要求更加匹配。')).toBeInTheDocument()
    expect(screen.getByText(skillsOriginal)).toBeInTheDocument()
    expect(screen.getByText(skillsProposed)).toBeInTheDocument()
    expect(screen.queryByText(/^\s*-\s*$/)).not.toBeInTheDocument()
  })

  it('keeps answers and the old map when rescan fails', async () => {
    vi.mocked(scanEnhancedOpportunity)
      .mockReset()
      .mockResolvedValueOnce({ data: beforeMap } as never)
      .mockRejectedValueOnce({ response: { status: 500, data: { message: 'scan failed' } } })
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'used Nx monorepo with shared TypeScript libraries')
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(createTailorProposal).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '上一题' }))
    expect(screen.getByLabelText('请说明你具体做了什么')).toHaveValue('used Nx monorepo with shared TypeScript libraries')
    expect(screen.getAllByText('尚未确认').length).toBeGreaterThan(0)
  })

  it('keeps answers when creating the proposal fails after a successful rescan', async () => {
    vi.mocked(createTailorProposal).mockRejectedValue({
      response: { status: 500, data: { message: 'proposal failed' } },
    })
    renderPage()
    await startEnhanced()
    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'used Nx monorepo with shared TypeScript libraries')
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成深度增强建议' })).toBeEnabled()
    expect(screen.getByRole('radio', { name: /深度增强/ })).toBeChecked()
  })
})
