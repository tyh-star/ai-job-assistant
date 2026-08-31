import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import { TailorPage } from '../pages/TailorPage'
import i18n from '../i18n'
import { continueTailorProposal, createTailorProposal, listJobs, tailorResume } from '../api/jobs'
import { listResumes } from '../api/resumes'

vi.mock('../api/jobs', async () => {
  const actual = await vi.importActual<typeof import('../api/jobs')>('../api/jobs')
  return {
    ...actual,
    listJobs: vi.fn(),
    tailorResume: vi.fn(),
    createTailorProposal: vi.fn(),
    continueTailorProposal: vi.fn(),
    confirmTailorProposal: vi.fn(),
    analyzeTailorFit: vi.fn(),
    refreshJobRequirementProfile: vi.fn(),
    scanEnhancedOpportunity: vi.fn(),
    answerEnhancedQuestion: vi.fn(),
    getTailorProposal: vi.fn(),
  }
})

vi.mock('../api/resumes', async () => {
  const actual = await vi.importActual<typeof import('../api/resumes')>('../api/resumes')
  return {
    ...actual,
    listResumes: vi.fn(),
    getResumeStructure: vi.fn(),
    canonicalizeResume: vi.fn(),
  }
})

describe('TailorPage auto coverage scan', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  it('does not auto-continue when Quick Optimize calls tailorResume', async () => {
    await i18n.changeLanguage('zh-CN')
    vi.mocked(listResumes).mockResolvedValue({
      data: [{ id: 9, fileName: 'cv.docx', fileType: 'docx', fileAvailable: true, origin: 'UPLOAD' }],
    } as never)
    vi.mocked(listJobs).mockResolvedValue({
      data: [{ id: 7, title: 'Java Backend', company: 'Acme', description: 'Java' }],
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
        unchanged: true,
        appliedChangeCount: 0,
        rejectedChangeCount: 0,
        rejectedChanges: [],
        changes: [],
      },
    } as never)

    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/tailor?resumeId=9&jobId=7']}>
          <TailorPage />
        </MemoryRouter>
      </I18nextProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: '生成定向建议' }))
    await waitFor(() => expect(tailorResume).toHaveBeenCalledWith(7, 9, 'CONSERVATIVE'))
    expect(createTailorProposal).not.toHaveBeenCalled()
    expect(continueTailorProposal).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: '下载 DOCX' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '继续优化其他内容' })).not.toBeInTheDocument()
  })
})
