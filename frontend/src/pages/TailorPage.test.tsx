import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { listJobs, createTailorProposal, confirmTailorProposal, continueTailorProposal, analyzeTailorFit, refreshJobRequirementProfile, tailorResume, getTailorProposal, scanEnhancedOpportunity, answerEnhancedQuestion } from '../api/jobs'
import {
  canonicalizeResume,
  getResumeStructure,
  listResumes,
  listResumeEvidence,
} from '../api/resumes'
import type {
  JobResponse,
  ResumeResponse,
  TailorAnalysisResponse,
  TailoredResumeResponse,
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

const pdfResume = resume({
  id: 11,
  fileName: 'cv.pdf',
  fileType: 'pdf',
  reviewStatus: 'PENDING_REVIEW',
})
const childResume = resume({
  id: 99,
  fileName: 'cv.canonical.docx',
  fileType: 'docx',
  origin: 'TEMPLATE',
})
const job = {
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
} satisfies JobResponse
const appliedBasic = {
  resumeId: 99,
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
} satisfies TailoredResumeResponse

describe('TailorPage PDF flow', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({ data: [pdfResume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(getResumeStructure).mockResolvedValue({ data: {} } as never)
    vi.mocked(canonicalizeResume).mockResolvedValue({ data: childResume } as never)
    vi.mocked(tailorResume).mockResolvedValue({ data: appliedBasic } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('lets a fileAvailable PDF start tailoring without a content-review blocker', async () => {
    renderPage()
    const run = await screen.findByRole('button', { name: '生成定向建议' })
    expect(run).toBeEnabled()
    expect(screen.queryByText('请先确认这份 PDF 的识别内容，然后再改写。')).not.toBeInTheDocument()
  })

  it('canonicalizes a PDF then calls tailorResume with the child resume id', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    await waitFor(() => expect(tailorResume).toHaveBeenCalledWith(7, 99, 'CONSERVATIVE'))
    expect(getResumeStructure).toHaveBeenCalledWith(11)
    expect(canonicalizeResume).toHaveBeenCalledWith(11)
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(scanEnhancedOpportunity).not.toHaveBeenCalled()
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect(continueTailorProposal).not.toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
  })

  it('shows confirm-content when canonicalize returns 400', async () => {
    vi.mocked(canonicalizeResume).mockRejectedValue({ response: { status: 400 } })
    renderPage()
    await screen.findByRole('button', { name: '生成定向建议' })
    await userEvent.click(screen.getByRole('button', { name: '生成定向建议' }))

    expect(
      await screen.findByText('PDF 自动识别的信息不完整，请先确认内容后再改写'),
    ).toBeInTheDocument()
    expect(screen.queryByText('请先确认这份 PDF 的识别内容，然后再改写。')).not.toBeInTheDocument()
    const confirm = screen.getAllByRole('link', { name: '确认内容' })
    expect(confirm.some((link) => link.getAttribute('href') === '/resumes/11/structure?next=tailor')).toBe(true)
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(tailorResume).not.toHaveBeenCalled()
  })

    it('disables start tailoring only when the file is unavailable', async () => {
    vi.mocked(listResumes).mockResolvedValue({
      data: [resume({ ...pdfResume, fileAvailable: false, downloadUrl: null })],
    } as never)
    renderPage()
    const run = await screen.findByRole('button', { name: '生成定向建议' })
    expect(run).toBeDisabled()
    expect(screen.getByText('该简历文件不可用，请重新上传')).toBeInTheDocument()
  })
})

describe('TailorPage conservative result', () => {
  const docxResume = resume({
    id: 9,
    fileName: 'cv.docx',
    fileType: 'docx',
    origin: 'UPLOAD',
    reviewStatus: 'READY',
  })

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({ data: [docxResume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(tailorResume).mockResolvedValue({
      data: { ...appliedBasic, resumeId: 9 },
    } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('calls tailorResume for Quick Optimize and skips proposal and enhanced APIs', async () => {
    renderDocxPage()
    expect(screen.queryByText('基础润色（直接生成）')).not.toBeInTheDocument()
    expect(screen.queryByText('增强建议（确认后生成）')).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    await waitFor(() => expect(tailorResume).toHaveBeenCalledWith(7, 9, 'CONSERVATIVE'))
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(scanEnhancedOpportunity).not.toHaveBeenCalled()
    expect(answerEnhancedQuestion).not.toHaveBeenCalled()
    expect(continueTailorProposal).not.toHaveBeenCalled()
    expect(confirmTailorProposal).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByText(/已选择 \d/)).not.toBeInTheDocument()
    expect(screen.queryByText(/未确认前不会生成文件/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认所选改写' })).not.toBeInTheDocument()
    expect(screen.queryByText('V3')).not.toBeInTheDocument()
    expect(screen.queryByText('BASIC')).not.toBeInTheDocument()
    expect(screen.queryByText(/CONSERVATIVE/)).not.toBeInTheDocument()
  })

  it('keeps the resume and job selected when Quick Optimize fails', async () => {
    vi.mocked(tailorResume).mockRejectedValue({
      response: { status: 500, data: { message: 'temporary failure' } },
    })
    renderDocxPage()
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0)
    const selects = screen.getAllByRole('combobox')
    expect(selects[0]).toHaveValue('9')
    expect(selects[1]).toHaveValue('7')
    expect(screen.getByRole('button', { name: '生成定向建议' })).toBeEnabled()
    vi.mocked(tailorResume).mockResolvedValue({
      data: { ...appliedBasic, resumeId: 9 },
    } as never)
    await userEvent.click(screen.getByRole('button', { name: '生成定向建议' }))
    await waitFor(() => expect(tailorResume).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
  })

  it('still loads and confirms an old pending proposal with selectedNodeIds', async () => {
    seedPendingProposal({
      resumeId: 9,
      jobId: 7,
      mode: 'CONSERVATIVE',
      proposalId: 42,
      status: 'PENDING',
      unchanged: true,
      appliedChangeCount: 0,
      rejectedChangeCount: 0,
      rejectedChanges: [],
      changes: [
        {
          nodeId: 'p-3',
          originalText: 'Integrated RESTful APIs to handle shopping cart managements',
          newText: 'Integrated RESTful APIs for shopping cart operations.',
          reason: 'Fixed grammar',
          improvementSummaryZh: '修复了语法、冠词或标点，并保留了原有事实。',
          improvementSummaryEn: 'Fixed grammar, articles, or punctuation while keeping every original fact.',
          upliftType: 'GRAMMAR_FIX',
          evidenceType: 'VERIFIED',
          evidenceNodeIds: ['p-3'],
          evidenceQuote: 'Integrated RESTful APIs to handle shopping cart managements',
          applied: false,
          requiresConfirmation: true,
        },
      ],
    })

    renderDocxPage()
    expect(await screen.findByText('仅发现语法或清晰度问题，当前简历缺少可支持更强改写的成果信息。')).toBeInTheDocument()
    expect(screen.getByText(/语法修复/)).toBeInTheDocument()
    expect(screen.getByText(/为什么更好/)).toBeInTheDocument()
    expect(screen.getByText('原文')).toBeInTheDocument()
    expect(screen.getByText('AI 建议')).toBeInTheDocument()
    expect(screen.getByText(/已选择 1 \/ 1/)).toBeInTheDocument()
    expect(screen.getByText(/未确认前不会生成文件/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
    await userEvent.click(screen.getByText('技术详情'))
    expect(screen.getAllByText(/Integrated RESTful APIs to handle shopping cart managements/).length).toBeGreaterThan(0)
    expect(screen.queryByText('节点')).not.toBeInTheDocument()
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(getTailorProposal).toHaveBeenCalledWith(7, 42)
  })

  it('confirms only the selected suggestion and does not call the old tailor API', async () => {
    seedPendingProposal({
      resumeId: 9,
      jobId: 7,
      mode: 'CONSERVATIVE',
      proposalId: 42,
      status: 'PENDING',
      unchanged: true,
      appliedChangeCount: 0,
      rejectedChangeCount: 0,
      rejectedChanges: [],
      changes: [
        {
          nodeId: 'p-23',
          originalText: 'Developed rate limiting.',
          newText: 'Developed IP rate-limiting for traffic.',
          applied: false,
          requiresConfirmation: true,
        },
        {
          nodeId: 'p-24',
          originalText: 'Wrote integration tests.',
          newText: 'Wrote integration tests for payment APIs.',
          applied: false,
          requiresConfirmation: true,
        },
      ],
    })
    vi.mocked(confirmTailorProposal).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 42,
        status: 'CONFIRMED',
        downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
        pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
        unchanged: false,
        appliedChangeCount: 1,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        changes: [
          {
            nodeId: 'p-23',
            originalText: 'Developed rate limiting.',
            newText: 'Developed IP rate-limiting for traffic.',
            applied: true,
          },
          {
            nodeId: 'p-24',
            originalText: 'Wrote integration tests.',
            newText: 'Wrote integration tests for payment APIs.',
            applied: false,
          },
        ],
      },
    } as never)

    renderDocxPage()

    const checkboxes = await screen.findAllByRole('checkbox')
    expect(checkboxes).toHaveLength(2)
    await userEvent.click(checkboxes[1])
    expect(checkboxes[1]).not.toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '确认所选改写' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalledWith(7, 42, ['p-23'], []))
    expect(tailorResume).not.toHaveBeenCalled()
  })

  it('does not confirm when no suggestion is selected', async () => {
    seedPendingProposal({
      resumeId: 9,
      jobId: 7,
      mode: 'CONSERVATIVE',
      proposalId: 42,
      status: 'PENDING',
      unchanged: true,
      appliedChangeCount: 0,
      rejectedChangeCount: 0,
      rejectedChanges: [],
      changes: [
        {
          nodeId: 'p-23',
          originalText: 'Developed rate limiting.',
          newText: 'Developed IP rate-limiting for traffic.',
          applied: false,
          requiresConfirmation: true,
        },
      ],
    })

    renderDocxPage()

    await userEvent.click(await screen.findByRole('checkbox'))
    expect(screen.getByRole('button', { name: '确认所选改写' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '确认所选改写' }))
    expect(confirmTailorProposal).not.toHaveBeenCalled()
  })

  it('edits a suggestion, restores the AI text, and keeps edits when confirm fails', async () => {
    seedPendingProposal({
      resumeId: 9,
      jobId: 7,
      mode: 'CONSERVATIVE',
      proposalId: 42,
      status: 'PENDING',
      unchanged: true,
      appliedChangeCount: 0,
      rejectedChangeCount: 0,
      rejectedChanges: [],
      changes: [
        {
          nodeId: 'p-16',
          originalText: 'Design and implement the application using Java Spring Boot with an MVC pattern.',
          newText: 'Designed and implemented a Java Spring Boot application using the MVC architecture.',
          upliftType: 'CLARITY_IMPROVEMENT',
          applied: false,
          requiresConfirmation: true,
        },
      ],
    })
    vi.mocked(confirmTailorProposal)
      .mockRejectedValueOnce({ response: { status: 400, data: { message: 'newText is too long' } } })
      .mockResolvedValueOnce({
        data: {
          resumeId: 9,
          jobId: 7,
          mode: 'CONSERVATIVE',
          proposalId: 42,
          status: 'CONFIRMED',
          downloadUrl: '/api/v1/jobs/7/tailored/out.docx',
          pdfDownloadUrl: '/api/v1/jobs/7/tailored/out.pdf',
          pdfExportStatus: 'SUCCESS',
          unchanged: false,
          appliedChangeCount: 1,
          rejectedChangeCount: 0,
          rejectedChanges: [],
          changes: [
            {
              nodeId: 'p-16',
              originalText: 'Design and implement the application using Java Spring Boot with an MVC pattern.',
              newText: 'Developed a Java Spring Boot MVC service for checkout.',
              evidenceType: 'USER_EDITED',
              applied: true,
            },
          ],
        },
      } as never)

    renderDocxPage()

    await userEvent.click(await screen.findByRole('button', { name: '编辑建议' }))
    const editor = await screen.findByRole('textbox')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Developed a Java Spring Boot MVC service for checkout.')
    expect(screen.getByText('有未保存的编辑，请先保存或取消后再确认。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认所选改写' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    await userEvent.click(screen.getByRole('button', { name: '确认所选改写' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalledTimes(1))
    expect(screen.getByText('Developed a Java Spring Boot MVC service for checkout.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认所选改写' })).toBeEnabled()

    await userEvent.click(screen.getByRole('button', { name: '恢复 AI 建议' }))
    expect(screen.getByText('Designed and implemented a Java Spring Boot application using the MVC architecture.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '编辑建议' }))
    const restoredEditor = await screen.findByRole('textbox')
    await userEvent.clear(restoredEditor)
    await userEvent.type(restoredEditor, 'Developed a Java Spring Boot MVC service for checkout.')
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    await userEvent.click(screen.getByRole('button', { name: '确认所选改写' }))
    await waitFor(() => expect(confirmTailorProposal).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 PDF' })).toBeInTheDocument()
  })
})

describe('TailorPage analysis', () => {
  const docxResume = resume({
    id: 9,
    fileName: 'cv.docx',
    fileType: 'docx',
    origin: 'UPLOAD',
    reviewStatus: 'READY',
  })
  const analysis: TailorAnalysisResponse = {
    resumeId: 9,
    jobId: 7,
    overallScore: 68,
    scoreBand: 'WEAK',
    requirementsFixed: true,
    profileUpdatedAt: '2026-08-18T23:00:00',
    dimensions: {
      hardSkills: 80,
      responsibilities: 65,
      qualifications: null,
      evidenceStrength: 45,
      atsSearchability: 72,
    },
    scoreExplanation: {
      strengths: ['Java and Spring Boot have project evidence'],
      gaps: ['No evidence for Docker'],
      nextActions: ['Confirm whether Docker was actually used in a project'],
    },
    summary: {
      supportedMustHaveCount: 3,
      partiallySupportedMustHaveCount: 0,
      missingMustHaveCount: 2,
      totalMustHaveCount: 5,
      supportedPreferredCount: 2,
      partiallySupportedPreferredCount: 0,
      missingPreferredCount: 2,
      totalPreferredCount: 4,
      missingEvidenceCount: 2,
    },
    requirements: [
      {
        id: 'req-1',
        category: 'HARD_SKILL',
        priority: 'MUST_HAVE',
        requirement: 'Java',
        exactTerms: ['Java'],
        status: 'SUPPORTED',
        evidenceKind: 'PROJECT_BULLET',
        evidenceNodeIds: ['p-11', 'p-16'],
        evidenceQuotes: [
          'Java',
          'Design and implement the application using Java Spring Boot with an MVC pattern.',
        ],
      },
      {
        id: 'req-2',
        category: 'HARD_SKILL',
        priority: 'MUST_HAVE',
        requirement: 'Docker',
        exactTerms: ['Docker'],
        status: 'MISSING_EVIDENCE',
        evidenceKind: null,
        evidenceNodeIds: [],
        evidenceQuotes: [],
      },
    ],
  }

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({ data: [docxResume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(analyzeTailorFit).mockResolvedValue({ data: analysis } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows Chinese analysis without starting tailor', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: '分析职位匹配' }))
    expect(analyzeTailorFit).toHaveBeenCalledWith(7, 9)
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(refreshJobRequirementProfile).not.toHaveBeenCalled()
    expect(await screen.findByText(/总分/)).toBeInTheDocument()
    expect(screen.getByText('职位要求已固定')).toBeInTheDocument()
    expect(screen.getByText('要求快照更新于 2026-08-18T23:00:00')).toBeInTheDocument()
    expect(screen.getByText('再次分析只会用当前简历重新匹配，不会重新解析职位描述。')).toBeInTheDocument()
    expect(screen.getByText('必备要求覆盖：3 / 5')).toBeInTheDocument()
    expect(screen.getByText('已覆盖要求 (1)')).toBeInTheDocument()
    expect(screen.getByText('缺失证据要求 (1)')).toBeInTheDocument()
    expect(screen.getAllByText('Java').length).toBeGreaterThan(0)
    expect(screen.getByText('硬技能 · 已覆盖 · 项目证据')).toBeInTheDocument()
    expect(screen.getByText(/任职资格: 该岗位未提出/)).toBeInTheDocument()
    expect(screen.queryByText(/任职资格: 100/)).not.toBeInTheDocument()
    expect(
      screen.getByText('Design and implement the application using Java Spring Boot with an MVC pattern.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('补充真实经历')).not.toBeInTheDocument()
  })

  it('shows English analysis labels', async () => {
    await i18n.changeLanguage('en')
    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )
    expect(await screen.findByRole('button', { name: 'Analyze job match' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Analyze job match' }))
    expect(await screen.findByText('Must-have coverage: 3 / 5')).toBeInTheDocument()
    expect(screen.getByText('Hard skill · Supported · Project evidence')).toBeInTheDocument()
    expect(screen.getByText('Missing evidence (1)')).toBeInTheDocument()
    expect(screen.getByText(/Qualifications: Not specified/)).toBeInTheDocument()
    expect(screen.queryByText(/Qualifications: 100/)).not.toBeInTheDocument()
  })

  it('re-parses job requirements from a secondary confirm action', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(refreshJobRequirementProfile).mockResolvedValue({
      data: {
        ...analysis,
        overallScore: 54,
        profileUpdatedAt: '2026-08-18T23:10:00',
      },
    } as never)

    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: '重新解析职位要求' }))
    expect(window.confirm).toHaveBeenCalled()
    expect(refreshJobRequirementProfile).toHaveBeenCalledWith(7, 9)
    expect(analyzeTailorFit).not.toHaveBeenCalled()
    expect(await screen.findByText(/总分: 54/)).toBeInTheDocument()
  })

  it('does not re-parse when the refresh confirm is cancelled', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)

    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: '重新解析职位要求' }))
    expect(refreshJobRequirementProfile).not.toHaveBeenCalled()
    expect(analyzeTailorFit).not.toHaveBeenCalled()
  })
})

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/tailor?resumeId=11&jobId=7']}>
        <TailorPage />
      </MemoryRouter>
    </I18nextProvider>,
  )
}

function renderDocxPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
        <TailorPage />
      </MemoryRouter>
    </I18nextProvider>,
  )
}

function seedPendingProposal(data: object, resumeId = '9', jobId = '7') {
  sessionStorage.setItem(sessionKey(jobId, resumeId), JSON.stringify({
    productMode: 'QUICK',
    phase: 'IDLE',
    tailorResumeId: Number(resumeId),
    opportunity: null,
    answers: {},
    questionIndex: 0,
    result: data,
    selectedEntryKeys: [],
  }))
  vi.mocked(getTailorProposal).mockResolvedValue({ data } as never)
}

function resume(overrides: Partial<ResumeResponse>): ResumeResponse {
  return {
    id: 1,
    fileName: 'resume.pdf',
    fileType: 'pdf',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    origin: 'UPLOAD',
    reviewStatus: 'PENDING_REVIEW',
    parentResumeId: null,
    canonicalResumeId: null,
    fileAvailable: true,
    downloadUrl: '/resumes/1/download',
    ...overrides,
  }
}

describe('TailorPage hides Evidence Bank', () => {
  const docxResume = resume({
    id: 9,
    fileName: 'cv.docx',
    fileType: 'docx',
    origin: 'UPLOAD',
    reviewStatus: 'READY',
  })

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({ data: [docxResume] } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    vi.mocked(listResumeEvidence).mockResolvedValue({ data: [] } as never)
    vi.mocked(analyzeTailorFit).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        overallScore: 68,
        scoreBand: 'WEAK',
        dimensions: {
          hardSkills: 80,
          responsibilities: 65,
          qualifications: null,
          evidenceStrength: 45,
          atsSearchability: 72,
        },
        scoreExplanation: {
          strengths: ['Java'],
          gaps: ['Docker'],
          nextActions: ['Confirm Docker'],
        },
        summary: {
          supportedMustHaveCount: 3,
          totalMustHaveCount: 5,
          supportedPreferredCount: 2,
          totalPreferredCount: 4,
          missingEvidenceCount: 2,
        },
        requirements: [],
        evidenceQuestions: [{
          id: 'eq-req-2',
          requirementId: 'req-2',
          questionType: 'SKILL_USAGE',
          topic: 'Docker',
          priority: 'MUST_HAVE',
          promptZh: '你是否在某个项目中实际使用过「Docker」？',
          promptEn: 'Did you actually use Docker in a project?',
          entryOptions: [],
        }],
      },
    } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('does not render evidence questions or saved evidence after analysis', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: '分析职位匹配' }))
    expect(await screen.findByText(/总分/)).toBeInTheDocument()
    expect(screen.queryByText('补充真实经历')).not.toBeInTheDocument()
    expect(screen.queryByText('已保存的经历证据')).not.toBeInTheDocument()
    expect(screen.queryByText('你是否在某个项目中实际使用过「Docker」？')).not.toBeInTheDocument()
    expect(screen.queryByText('Add supporting evidence')).not.toBeInTheDocument()
  })
})

describe('TailorPage progressive continue', () => {
  const firstChanges = [
    {
      nodeId: 'p-16',
      originalText: 'Design and implement the application using Java Spring Boot with an MVC pattern.',
      newText: 'Designed and implemented a Java Spring Boot application using the MVC architecture.',
      applied: false,
      requiresConfirmation: true,
    },
    {
      nodeId: 'p-17',
      originalText: 'Integrated RESTful APIs to handle user authentication.',
      newText: 'Integrated RESTful APIs for user authentication.',
      applied: false,
      requiresConfirmation: true,
    },
  ]

  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(continueTailorProposal).mockReset()
    vi.mocked(listResumes).mockResolvedValue({
      data: [resume({ id: 9, fileName: 'cv.docx', fileType: 'docx' })],
    } as never)
    vi.mocked(listJobs).mockResolvedValue({ data: [job] } as never)
    seedPendingProposal({
      resumeId: 9,
      jobId: 7,
      mode: 'CONSERVATIVE',
      proposalId: 42,
      status: 'PENDING',
      unchanged: true,
      appliedChangeCount: 0,
      rejectedChangeCount: 0,
      rejectedChanges: [],
      hasMoreSuggestions: true,
      changes: firstChanges,
    })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('shows the continue button only while pending and more suggestions remain', async () => {
    renderDocxPage()
    expect(await screen.findByRole('button', { name: '继续优化其他内容' })).toBeInTheDocument()
  })

  it('appends new suggestions and keeps edits and selection', async () => {
    vi.mocked(continueTailorProposal).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 42,
        status: 'PENDING',
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        addedSuggestionCount: 1,
        hasMoreSuggestions: true,
        changes: [
          ...firstChanges,
          {
            nodeId: 'p-30',
            originalText: 'Tuned MySQL indexes.',
            newText: 'Tuned MySQL indexes for order search.',
            applied: false,
            requiresConfirmation: true,
          },
        ],
      },
    } as never)

    renderDocxPage()
    await userEvent.click((await screen.findAllByRole('button', { name: '编辑建议' }))[0])
    await userEvent.clear(screen.getByRole('textbox'))
    await userEvent.type(screen.getByRole('textbox'), '用户改过的 Spring Boot 句子')
    await userEvent.click(screen.getByRole('button', { name: '保存编辑' }))
    const checkboxes = screen.getAllByRole('checkbox')
    await userEvent.click(checkboxes[1])
    expect(checkboxes[1]).not.toBeChecked()

    await userEvent.click(screen.getByRole('button', { name: '继续优化其他内容' }))
    expect(await screen.findByText('Tuned MySQL indexes for order search.')).toBeInTheDocument()
    expect(screen.getByText('用户改过的 Spring Boot 句子')).toBeInTheDocument()
    const after = screen.getAllByRole('checkbox')
    expect(after).toHaveLength(3)
    expect(after[0]).toBeChecked()
    expect(after[1]).not.toBeChecked()
    expect(after[2]).toBeChecked()
    expect(continueTailorProposal).toHaveBeenCalledWith(7, 42)
  })

  it('shows a completion notice when continue adds nothing', async () => {
    vi.mocked(continueTailorProposal).mockResolvedValue({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 42,
        status: 'PENDING',
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        addedSuggestionCount: 0,
        hasMoreSuggestions: false,
        changes: firstChanges,
      },
    } as never)

    renderDocxPage()
    await userEvent.click(await screen.findByRole('button', { name: '继续优化其他内容' }))
    expect(await screen.findByText('已完成高价值内容优化')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '继续优化其他内容' })).not.toBeInTheDocument()
  })

  it('does not send a second continue request while the first is in flight', async () => {
    let resolveContinue: ((value: unknown) => void) | undefined
    vi.mocked(continueTailorProposal).mockImplementation(
      () => new Promise((resolve) => {
        resolveContinue = resolve
      }) as never,
    )

    renderDocxPage()
    const button = await screen.findByRole('button', { name: '继续优化其他内容' })
    await userEvent.click(button)
    await userEvent.click(button)
    expect(continueTailorProposal).toHaveBeenCalledTimes(1)
    resolveContinue?.({
      data: {
        resumeId: 9,
        jobId: 7,
        mode: 'CONSERVATIVE',
        proposalId: 42,
        status: 'PENDING',
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        addedSuggestionCount: 0,
        hasMoreSuggestions: false,
        changes: firstChanges,
      },
    })
  })
})
