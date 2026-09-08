import { api } from './client'
import { endpoints } from './endpoints'
import type {
  CareerMaterialsResponse,
  CreateResumeBuilderDraftRequest,
  GenerateResumeBuilderDraftRequest,
  NormalizedResume,
  ResumeBuilderDraftResponse,
  ResumeBuilderDraftSummary,
  UpdateResumeBuilderDraftRequest,
} from './types'
import type { ResumeResponse } from './types'

export function createResumeBuilderDraft(request: CreateResumeBuilderDraftRequest) {
  return api.post<ResumeBuilderDraftResponse>(endpoints.resumeBuilderDrafts, request)
}

export function listResumeBuilderDrafts() {
  return api.get<ResumeBuilderDraftSummary[]>(endpoints.resumeBuilderDrafts)
}

export function getResumeBuilderDraft(id: number) {
  return api.get<ResumeBuilderDraftResponse>(endpoints.resumeBuilderDraft(id))
}

export function updateResumeBuilderDraft(id: number, request: UpdateResumeBuilderDraftRequest) {
  return api.put<ResumeBuilderDraftResponse>(endpoints.resumeBuilderDraft(id), request)
}

export function duplicateResumeBuilderDraft(id: number) {
  return api.post<ResumeBuilderDraftResponse>(endpoints.resumeBuilderDraftDuplicate(id))
}

export function deleteResumeBuilderDraft(id: number) {
  return api.delete(endpoints.resumeBuilderDraft(id))
}

/** Backend-computed, normalized preview -- the SAME model the DOCX writer renders. */
export function previewResumeBuilderDraft(id: number) {
  return api.post<NormalizedResume>(endpoints.resumeBuilderDraftPreview(id))
}

export function generateResumeBuilderDraft(id: number, request: GenerateResumeBuilderDraftRequest) {
  return api.post<ResumeResponse>(endpoints.resumeBuilderDraftGenerate(id), request)
}

/** Deterministic, zero-OpenAI-call optional materials. Never part of the formal resume. */
export function getResumeBuilderCareerMaterials(id: number) {
  return api.get<CareerMaterialsResponse>(endpoints.resumeBuilderDraftCareerMaterials(id))
}

/** One client-generated key per generate ACTION (never per HTTP attempt) -- resend the
 * SAME key on a retry so the backend's idempotency guard can recognize it. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
