import type { AxiosError } from 'axios'
import type { ResumeResponse, TailorMode, TailoredResumeResponse } from '../api/types'

export type TailorProgress = 'recognizing' | 'preparing' | 'tailoring'

export class IncompletePdfStructureError extends Error {
  readonly resumeId: number

  constructor(resumeId: number) {
    super('INCOMPLETE_PDF_STRUCTURE')
    this.name = 'IncompletePdfStructureError'
    this.resumeId = resumeId
  }
}

export class ResumeFileUnavailableError extends Error {
  constructor() {
    super('RESUME_FILE_UNAVAILABLE')
    this.name = 'ResumeFileUnavailableError'
  }
}

type StructureClient = (id: number) => Promise<unknown>
type CanonicalizeClient = (id: number) => Promise<{ data: ResumeResponse }>
type TailorClient = (
  jobId: number,
  resumeId: number,
  mode: TailorMode,
) => Promise<{ data: TailoredResumeResponse }>

export async function runTailorFlow(options: {
  resume: ResumeResponse
  jobId: number
  mode: TailorMode
  getResumeStructure: StructureClient
  canonicalizeResume: CanonicalizeClient
  tailorResume: TailorClient
  onProgress?: (step: TailorProgress) => void
}): Promise<TailoredResumeResponse> {
  const {
    resume,
    jobId,
    mode,
    getResumeStructure,
    canonicalizeResume,
    tailorResume,
    onProgress,
  } = options

  if (!resume.fileAvailable) {
    throw new ResumeFileUnavailableError()
  }

  let tailorResumeId = resume.id
  if (resume.fileType.toLowerCase() === 'pdf') {
    onProgress?.('recognizing')
    await getResumeStructure(resume.id)
    onProgress?.('preparing')
    try {
      const { data } = await canonicalizeResume(resume.id)
      tailorResumeId = data.id
    } catch (error) {
      if (isHttpStatus(error, 400)) {
        throw new IncompletePdfStructureError(resume.id)
      }
      throw error
    }
  }

  onProgress?.('tailoring')
  const { data } = await tailorResume(jobId, tailorResumeId, mode)
  return data
}

function isHttpStatus(error: unknown, status: number): boolean {
  const axiosError = error as AxiosError
  return axiosError.response?.status === status
}
