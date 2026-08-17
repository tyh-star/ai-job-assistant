import { describe, expect, it, vi } from 'vitest'
import type { ResumeResponse, TailoredResumeResponse } from '../api/types'
import {
  IncompletePdfStructureError,
  ResumeFileUnavailableError,
  runTailorFlow,
} from './runTailorFlow'

const pdfResume = resume({ id: 11, fileType: 'pdf', reviewStatus: 'PENDING_REVIEW' })
const docxResume = resume({ id: 22, fileType: 'docx', reviewStatus: 'READY' })
const childResume = resume({ id: 99, fileType: 'docx', origin: 'TEMPLATE' })
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

describe('runTailorFlow', () => {
  it('DOCX only calls tailor with the selected resume id', async () => {
    const getResumeStructure = vi.fn()
    const canonicalizeResume = vi.fn()
    const tailorResume = vi.fn().mockResolvedValue({ data: tailored })
    const onProgress = vi.fn()

    await runTailorFlow({
      resume: docxResume,
      jobId: 7,
      mode: 'CONSERVATIVE',
      getResumeStructure,
      canonicalizeResume,
      tailorResume,
      onProgress,
    })

    expect(getResumeStructure).not.toHaveBeenCalled()
    expect(canonicalizeResume).not.toHaveBeenCalled()
    expect(tailorResume).toHaveBeenCalledTimes(1)
    expect(tailorResume).toHaveBeenCalledWith(7, 22, 'CONSERVATIVE')
    expect(onProgress.mock.calls.map((call) => call[0])).toEqual(['tailoring'])
  })

  it('PDF calls structure, canonicalize, then tailor with the child id', async () => {
    const getResumeStructure = vi.fn().mockResolvedValue({ data: {} })
    const canonicalizeResume = vi.fn().mockResolvedValue({ data: childResume })
    const tailorResume = vi.fn().mockResolvedValue({ data: tailored })
    const onProgress = vi.fn()

    await runTailorFlow({
      resume: pdfResume,
      jobId: 7,
      mode: 'AGGRESSIVE',
      getResumeStructure,
      canonicalizeResume,
      tailorResume,
      onProgress,
    })

    expect(getResumeStructure).toHaveBeenCalledWith(11)
    expect(canonicalizeResume).toHaveBeenCalledWith(11)
    expect(tailorResume).toHaveBeenCalledWith(7, 99, 'AGGRESSIVE')
    expect(tailorResume).not.toHaveBeenCalledWith(7, 11, 'AGGRESSIVE')
    expect(onProgress.mock.calls.map((call) => call[0])).toEqual([
      'recognizing',
      'preparing',
      'tailoring',
    ])
    expect(callOrder(getResumeStructure, canonicalizeResume, tailorResume)).toEqual([
      'structure',
      'canonicalize',
      'tailor',
    ])
  })

  it('throws IncompletePdfStructureError when canonicalize returns 400', async () => {
    const getResumeStructure = vi.fn().mockResolvedValue({ data: {} })
    const canonicalizeResume = vi.fn().mockRejectedValue({ response: { status: 400 } })
    const tailorResume = vi.fn()

    await expect(
      runTailorFlow({
        resume: pdfResume,
        jobId: 7,
        mode: 'CONSERVATIVE',
        getResumeStructure,
        canonicalizeResume,
        tailorResume,
      }),
    ).rejects.toBeInstanceOf(IncompletePdfStructureError)

    expect(tailorResume).not.toHaveBeenCalled()
  })

  it('rejects unavailable files before calling APIs', async () => {
    const getResumeStructure = vi.fn()
    const canonicalizeResume = vi.fn()
    const tailorResume = vi.fn()

    await expect(
      runTailorFlow({
        resume: resume({ id: 11, fileType: 'pdf', fileAvailable: false }),
        jobId: 7,
        mode: 'CONSERVATIVE',
        getResumeStructure,
        canonicalizeResume,
        tailorResume,
      }),
    ).rejects.toBeInstanceOf(ResumeFileUnavailableError)

    expect(getResumeStructure).not.toHaveBeenCalled()
    expect(canonicalizeResume).not.toHaveBeenCalled()
    expect(tailorResume).not.toHaveBeenCalled()
  })
})

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

function callOrder(
  getResumeStructure: { mock: { invocationCallOrder: number[] } },
  canonicalizeResume: { mock: { invocationCallOrder: number[] } },
  tailorResume: { mock: { invocationCallOrder: number[] } },
): string[] {
  return [
    { name: 'structure', order: getResumeStructure.mock.invocationCallOrder[0] },
    { name: 'canonicalize', order: canonicalizeResume.mock.invocationCallOrder[0] },
    { name: 'tailor', order: tailorResume.mock.invocationCallOrder[0] },
  ]
    .sort((a, b) => a.order - b.order)
    .map((item) => item.name)
}
