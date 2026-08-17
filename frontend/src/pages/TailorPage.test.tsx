import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { listJobs, tailorResume } from '../api/jobs'
import { canonicalizeResume, getResumeStructure, listResumes } from '../api/resumes'
import type { JobResponse, ResumeResponse, TailoredResumeResponse } from '../api/types'
import { TailorPage } from './TailorPage'

vi.mock('../api/resumes', () => ({
  listResumes: vi.fn(),
  getResumeStructure: vi.fn(),
  canonicalizeResume: vi.fn(),
  downloadByUrl: vi.fn(),
}))

vi.mock('../api/jobs', () => ({
  listJobs: vi.fn(),
  tailorResume: vi.fn(),
  confirmTailorProposal: vi.fn(),
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
const tailored = {
  resumeId: 99,
  jobId: 7,
  mode: 'CONSERVATIVE',
  downloadUrl: '/jobs/7/tailored/1',
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
    vi.mocked(getResumeStructure).mockResolvedValue({ data: {} } as never)
    vi.mocked(canonicalizeResume).mockResolvedValue({ data: childResume } as never)
    vi.mocked(tailorResume).mockResolvedValue({ data: tailored } as never)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('lets a fileAvailable PDF start tailoring without a content-review blocker', async () => {
    renderPage()
    const run = await screen.findByRole('button', { name: '开始改写' })
    expect(run).toBeEnabled()
    expect(screen.queryByText('请先确认这份 PDF 的识别内容，然后再改写。')).not.toBeInTheDocument()
  })

  it('shows confirm-content when canonicalize returns 400', async () => {
    vi.mocked(canonicalizeResume).mockRejectedValue({ response: { status: 400 } })
    renderPage()
    await screen.findByRole('button', { name: '开始改写' })
    await userEvent.click(screen.getByRole('button', { name: '开始改写' }))

    expect(
      await screen.findByText('PDF 自动识别的信息不完整，请先确认内容后再改写'),
    ).toBeInTheDocument()
    expect(screen.queryByText('请先确认这份 PDF 的识别内容，然后再改写。')).not.toBeInTheDocument()
    const confirm = screen.getAllByRole('link', { name: '确认内容' })
    expect(confirm.some((link) => link.getAttribute('href') === '/resumes/11/structure?next=tailor')).toBe(true)
    expect(tailorResume).not.toHaveBeenCalled()
  })

  it('disables start tailoring only when the file is unavailable', async () => {
    vi.mocked(listResumes).mockResolvedValue({
      data: [resume({ ...pdfResume, fileAvailable: false, downloadUrl: null })],
    } as never)
    renderPage()
    const run = await screen.findByRole('button', { name: '开始改写' })
    expect(run).toBeDisabled()
    expect(screen.getByText('该简历文件不可用，请重新上传')).toBeInTheDocument()
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
