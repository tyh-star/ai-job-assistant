import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
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
import { listResumes, listResumeEvidence } from '../api/resumes'
import type {
  JobResponse,
  ResumeResponse,
  TailoredResumeResponse,
  V3OpportunityMap,
} from '../api/types'
import { TailorPage } from './TailorPage'

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

const dumpDir = resolve(__dirname, '../../../docs/v3-3d-live')

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
    {
      id: 'eq-1',
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      topic: 'maintain',
      promptEn: 'After AWS deploy, did you keep maintaining Prime Deals?',
      promptZh: 'Prime Deals 部署到 AWS 后，你是否继续维护过这个应用？如果有，请说明具体做了什么。',
      skipAllowed: true,
    },
    {
      id: 'eq-2',
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      topic: 'testing',
      promptEn: 'Did you write tests for Prime Deals?',
      promptZh: '你是否为 Prime Deals 写过测试？',
      skipAllowed: true,
    },
    {
      id: 'eq-3',
      canonicalEntryKey: 'PROJECT:smartpc-builder',
      entryTitle: 'SmartPC Builder',
      topic: 'metric',
      promptEn: 'Did you quantify SmartPC Builder results?',
      promptZh: '你是否量化过 SmartPC Builder 的结果？',
      skipAllowed: true,
    },
    {
      id: 'eq-4',
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      topic: 'data-store',
      promptEn: 'Did Prime Deals use a database?',
      promptZh: 'Prime Deals 是否使用过数据库？',
      skipAllowed: true,
    },
    {
      id: 'eq-5',
      canonicalEntryKey: 'PROJECT:smartpc-builder',
      entryTitle: 'SmartPC Builder',
      topic: 'testing',
      promptEn: 'Did you write tests for SmartPC Builder?',
      promptZh: '你是否为 SmartPC Builder 写过测试？',
      skipAllowed: true,
    },
  ],
  skippedQuestionIds: [],
  writerCalls: 0,
  openaiCalls: 0,
  wroteJobOrResume: false,
}

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
  changes: [],
  entryReviews: [
    {
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      originalNodes: [
        { nodeId: 'p-14', text: 'Built a Java checkout service.' },
        { nodeId: 'p-18', text: 'Deployed the project on AWS.' },
      ],
      proposedNodes: [
        { nodeId: 'p-14', text: 'Built a Java Spring Boot checkout service.' },
        { nodeId: 'p-18', text: 'Deployed the project on AWS and updated dependencies weekly.' },
      ],
      whyBetter: 'Uses your confirmed Prime Deals detail: weekly dependency updates.',
      suggestionType: 'EVIDENCE_ENHANCED',
      usedEvidenceItemIds: [41],
    },
  ],
}

const forbidden = [
  /\bV2\b/,
  /\bV3\b/,
  /\bBASIC\b/,
  /CONSERVATIVE/,
  /AGGRESSIVE/,
  /Fact Graph/,
  /Evidence Bank/,
  /\bnodeId\b/,
  /canonicalEntryKey/,
  /HardGate/,
  /Validator/,
]

function dump(name: string, extra = '') {
  mkdirSync(dumpDir, { recursive: true })
  const text = `${document.body.textContent ?? ''}\n${extra}`
  writeFileSync(resolve(dumpDir, name), text, 'utf8')
  return text
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

async function goToLastQuestion() {
  for (let i = 0; i < 6; i += 1) {
    const next = screen.queryByRole('button', { name: '下一题' })
    if (!next) {
      return
    }
    await userEvent.click(next)
  }
}

describe('V3-3D product-state acceptance dumps', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    sessionStorage.clear()
    vi.mocked(listResumes).mockResolvedValue({ data: [resume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(scanEnhancedOpportunity).mockResolvedValue({ data: opportunity } as never)
    vi.mocked(answerEnhancedQuestion).mockResolvedValue({ data: { skipped: true } } as never)
    vi.mocked(createTailorProposal).mockResolvedValue({ data: enhancedProposal } as never)
    vi.mocked(getTailorProposal).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(confirmTailorProposal).mockResolvedValue({
      data: {
        ...enhancedProposal,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        pdfExportStatus: 'SUCCESS',
      },
    } as never)
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

  it('dumps Quick Optimize generating-to-complete without questions or review', async () => {
    let resolveTailor: (value: unknown) => void = () => {}
    vi.mocked(tailorResume).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveTailor = resolve as (value: unknown) => void
      }) as never,
    )
    renderPage()
    expect(await screen.findByText('快速优化')).toBeInTheDocument()
    dump('frontend-quick-mode.txt')
    await userEvent.click(screen.getByRole('button', { name: '生成定向建议' }))
    dump('frontend-quick-generating.txt')
    resolveTailor({
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
    })
    await waitFor(() => expect(tailorResume).toHaveBeenCalledWith(7, 9, 'CONSERVATIVE'))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(scanEnhancedOpportunity).not.toHaveBeenCalled()
    expect(continueTailorProposal).not.toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
    const text = dump('frontend-quick-complete.txt')
    for (const pattern of forbidden) {
      expect(text).not.toMatch(pattern)
    }
  })

  it('dumps Deep Enhancement map, questions, review, and confirm', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('radio', { name: /深度增强/ }))
    await userEvent.click(screen.getByRole('button', { name: '检查可增强内容' }))
    expect((await screen.findAllByText('Prime Deals')).length).toBeGreaterThan(0)
    const mapText = dump('frontend-enhanced-map.txt')
    expect(mapText).toContain('部分匹配')
    expect(screen.getByText('没有简历或你确认的信息作为依据时，系统不会自动加入相关经历。')).toBeInTheDocument()
    expect(screen.getByText('问题 1 / 5')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '有，我做过' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '没有做过' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '暂时跳过' })).toBeInTheDocument()
    dump('frontend-enhanced-questions.txt')

    await userEvent.click(screen.getByRole('button', { name: '有，我做过' }))
    await userEvent.type(screen.getByLabelText('请说明你具体做了什么'), 'yes')
    await goToLastQuestion()
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0)

    await userEvent.clear(screen.getByLabelText('请说明你具体做了什么'))
    await userEvent.click(screen.getByRole('button', { name: '没有做过' }))
    await userEvent.click(screen.getByRole('button', { name: '下一题' }))
    await userEvent.click(screen.getByRole('button', { name: '暂时跳过' }))

    let resolveProposal: (value: unknown) => void = () => {}
    vi.mocked(createTailorProposal).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveProposal = resolve as (value: unknown) => void
      }) as never,
    )
    await goToLastQuestion()
    await userEvent.click(screen.getByRole('button', { name: '生成深度增强建议' }))
    expect(await screen.findByText('当前不会生成文件，完成审阅并确认后才会生成 DOCX 和 PDF。')).toBeInTheDocument()
    dump('frontend-enhanced-generating.txt')
    resolveProposal({ data: enhancedProposal })

    expect(await screen.findByText(/已选择 1 \/ 1 段增强内容/)).toBeInTheDocument()
    expect(screen.getByText(/Built a Java checkout service/)).toBeInTheDocument()
    expect(screen.getByText(/Built a Java Spring Boot checkout service/)).toBeInTheDocument()
    expect(screen.getByText(/未确认前不会生成文件/)).toBeInTheDocument()
    dump('frontend-enhanced-review.txt')

    const boxes = screen.getAllByRole('checkbox')
    expect(boxes[0]).toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '编辑建议' }))
    const editor = screen.getByLabelText('编辑建议')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Built a Java Spring Boot checkout service used after deploy.')
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    expect(screen.getByText('已由你编辑')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '恢复 AI 建议' }))
    expect(screen.getByText(/Built a Java Spring Boot checkout service/)).toBeInTheDocument()

    let resolveConfirm: (value: unknown) => void = () => {}
    vi.mocked(confirmTailorProposal).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveConfirm = resolve as (value: unknown) => void
      }) as never,
    )
    const confirm = screen.getByRole('button', { name: '确认并生成简历' })
    await userEvent.click(confirm)
    await userEvent.click(confirm)
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalledTimes(1))
    resolveConfirm({
      data: {
        ...enhancedProposal,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        pdfExportStatus: 'SUCCESS',
      },
    })
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    const complete = dump('frontend-enhanced-complete.txt')
    for (const pattern of forbidden) {
      expect(complete).not.toMatch(pattern)
    }
    expect(screen.queryByText('Kubernetes production operations')).not.toBeInTheDocument()
  })
})
