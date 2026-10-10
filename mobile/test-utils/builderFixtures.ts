import type { ResumeResponse } from '../src/api/types'
import { normalizeContent } from '../src/builder/model'
import type { BuilderDraftResponse, DraftContent } from '../src/builder/types'

type ContentPatch = {
  basicInfo?: Partial<DraftContent['basicInfo']>
  jobTarget?: Partial<DraftContent['jobTarget']>
} & Partial<Omit<DraftContent, 'basicInfo' | 'jobTarget'>>

/** A server-shaped (normalized) draft. Test-only placeholder facts, never product data. */
export function builderDraft(
  id: number,
  patch: Partial<Omit<BuilderDraftResponse, 'content'>> = {},
  content: ContentPatch = {},
): BuilderDraftResponse {
  const blank = normalizeContent({})
  return {
    id,
    title: 'My resume',
    templateType: 'GENERIC_ATS',
    targetRole: null,
    locale: 'zh-CN',
    schemaVersion: 1,
    version: 3,
    createdAt: '2026-09-01T00:00:00',
    updatedAt: '2026-09-10T00:00:00',
    ...patch,
    content: {
      ...blank,
      ...content,
      basicInfo: { ...blank.basicInfo, ...content.basicInfo },
      jobTarget: { ...blank.jobTarget, ...content.jobTarget },
    },
  }
}

/** A draft with the website's generate prerequisites: name, a contact and a target title. */
export function readyDraft(id: number, patch: Partial<Omit<BuilderDraftResponse, 'content'>> = {}): BuilderDraftResponse {
  return builderDraft(
    id,
    { targetRole: 'Junior Analyst', ...patch },
    { basicInfo: { fullName: 'Test Person', email: 'person@example.test' }, jobTarget: { targetTitle: 'Junior Analyst' } },
  )
}

export function generatedResume(id: number, fileName: string): ResumeResponse {
  return {
    id,
    fileName,
    fileType: 'docx',
    createdAt: '2026-09-13T00:00:00',
    updatedAt: '2026-09-13T00:00:00',
    origin: 'GENERATED',
    reviewStatus: 'READY',
    parentResumeId: null,
    canonicalResumeId: null,
    fileAvailable: true,
    downloadUrl: `/api/v1/resumes/${id}/download`,
  }
}
