import type { BuilderDraftResponse, CreateDraftRequest, GenerateDraftRequest, UpdateDraftRequest } from '../builder/types'
import type { ResumeStructure } from '../resumes/structure'
import type {
  AnswerEnhancedQuestionRequest,
  AnswerEnhancedQuestionResponse,
  ConfirmEnhancedRequest,
  ConfirmTailorProposalRequest,
  ResumeEvidenceItem,
  ResumeStructureResponse,
  SaveResumeEvidenceRequest,
  TailorMode,
  TailoredResumeResponse,
  V3OpportunityMap,
} from '../tailor/types'
import { apiDownload, apiRequest, apiUpload, type UploadFile } from './client'
import type {
  CatalogSearchQuery,
  ChangePasswordRequest,
  CitySuggestion,
  FeedbackRequest,
  FeedbackResponse,
  UpdateEmailRequest,
  UpdateEmailResponse,
  UpdateNameRequest,
  JobCatalogDetail,
  JobCatalogSearchResponse,
  JobResponse,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  ResumeBuilderDraftResponse,
  ResumeBuilderDraftSummary,
  ResumeQualityScoreResponse,
  ResumeResponse,
  SaveCatalogJobResponse,
  UserResponse,
} from './types'

export const authApi = {
  login: (body: LoginRequest) => apiRequest<LoginResponse>('/auth/login', { method: 'POST', body }),
  /** Returns the created user without a token -- the caller logs in afterwards. */
  register: (body: RegisterRequest) =>
    apiRequest<UserResponse>('/auth/register', { method: 'POST', body }),
}

/** The signed-in account. Every route acts on whoever the JWT names; there is no user id. */
export const usersApi = {
  me: () => apiRequest<UserResponse>('/users/me'),
  updateName: (body: UpdateNameRequest) => apiRequest<UserResponse>('/users/me/name', { method: 'PUT', body }),
  /** Needs the current password; returns a new token because the email is the JWT subject. */
  updateEmail: (body: UpdateEmailRequest) =>
    apiRequest<UpdateEmailResponse>('/users/me/email', { method: 'PUT', body }),
  /** 204. Every token issued before this, on every device, stops working afterwards. */
  changePassword: (body: ChangePasswordRequest) => apiRequest<void>('/users/me/password', { method: 'PUT', body }),
  /** 204. No password is asked for by the server. */
  deleteMe: () => apiRequest<void>('/users/me', { method: 'DELETE' }),
}

/** One 1-5 star rating with an optional comment per account. GET 404s until one exists. */
export const feedbackApi = {
  me: () => apiRequest<FeedbackResponse>('/feedback/me'),
  save: (body: FeedbackRequest) => apiRequest<FeedbackResponse>('/feedback/me', { method: 'PUT', body }),
  remove: () => apiRequest<void>('/feedback/me', { method: 'DELETE' }),
}

/** Uploaded and generated resume files, addressed by the numeric file id. */
export const resumesApi = {
  /** Upload and generated files; template and derived working copies stay hidden. */
  list: () => apiRequest<ResumeResponse[]>('/resumes'),
  get: (resumeId: number) => apiRequest<ResumeResponse>(`/resumes/${resumeId}`),
  /** multipart/form-data with the file in the `file` part; 201 returns the new row. */
  upload: (file: UploadFile, onProgress?: (fraction: number) => void) =>
    apiUpload<ResumeResponse>('/resumes', 'file', file, onProgress),
  remove: (resumeId: number) => apiRequest<void>(`/resumes/${resumeId}`, { method: 'DELETE' }),
  /** PDF only: also removes the internal Word working copy made for tailoring. */
  removeFamily: (resumeId: number) => apiRequest<void>(`/resumes/${resumeId}/family`, { method: 'DELETE' }),
  qualityScore: (resumeId: number) => apiRequest<ResumeQualityScoreResponse>(`/resumes/${resumeId}/quality-score`),
  download: (resumeId: number) => apiDownload(`/resumes/${resumeId}/download`),
  /**
   * A PDF's recognized content (400 for other types). The first read may extract and
   * classify the PDF on the server, so it gets the long timeout.
   */
  structure: (resumeId: number) => apiRequest<unknown>(`/resumes/${resumeId}/structure`, { timeoutMs: TAILOR_TIMEOUT_MS }),
  /** Replaces the stored structure with this body (no version, no merge); the PDF file is untouched. */
  updateStructure: (resumeId: number, body: ResumeStructure) =>
    apiRequest<unknown>(`/resumes/${resumeId}/structure`, { method: 'PUT', body }),
}

/** Resume-builder drafts, addressed by the numeric draft id -- never a file id. */
export const draftsApi = {
  list: () => apiRequest<ResumeBuilderDraftSummary[]>('/resume-builder/drafts'),
  get: (draftId: number) => apiRequest<ResumeBuilderDraftResponse>(`/resume-builder/drafts/${draftId}`),
  /** 201 with a blank, server-normalized draft. */
  create: (body: CreateDraftRequest) =>
    apiRequest<BuilderDraftResponse>('/resume-builder/drafts', { method: 'POST', body }),
  /** Optimistic locking: 409 when `version` is no longer the saved one. */
  update: (draftId: number, body: UpdateDraftRequest) =>
    apiRequest<BuilderDraftResponse>(`/resume-builder/drafts/${draftId}`, { method: 'PUT', body }),
  /** 204. Resume files this draft already generated are kept. */
  remove: (draftId: number) => apiRequest<void>(`/resume-builder/drafts/${draftId}`, { method: 'DELETE' }),
  /** 201 with the new DOCX row in GET /resumes; the same key returns the same file again. */
  generate: (draftId: number, body: GenerateDraftRequest) =>
    apiRequest<ResumeResponse>(`/resume-builder/drafts/${draftId}/generate`, { method: 'POST', body }),
}

/** AI tailoring runs synchronously on the server and can take minutes. */
export const TAILOR_TIMEOUT_MS = 180_000

/**
 * Tailoring a resume to a personal job (numeric job id from GET /jobs). The resume id
 * must be a DOCX: a PDF is first turned into its internal Word copy with `structure`
 * then `canonicalize`, as the website does.
 */
export const tailorApi = {
  /** Reads (and on first use, recognizes) a PDF's structure. PDF only. */
  structure: (resumeId: number) =>
    apiRequest<ResumeStructureResponse>(`/resumes/${resumeId}/structure`, { timeoutMs: TAILOR_TIMEOUT_MS }),
  /** 201 with the internal DOCX copy (origin TEMPLATE); 400 when recognition is incomplete. */
  canonicalize: (resumeId: number) =>
    apiRequest<ResumeResponse>(`/resumes/${resumeId}/canonicalize`, { method: 'POST', timeoutMs: TAILOR_TIMEOUT_MS }),
  /** CONSERVATIVE writes the file at once; AGGRESSIVE returns a PENDING proposal and no file. */
  run: (jobId: number, resumeId: number, mode: TailorMode) =>
    apiRequest<TailoredResumeResponse>(`/jobs/${jobId}/tailor`, {
      method: 'POST',
      query: { resumeId, mode },
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
  /** 404 once the proposal is confirmed, expired or not this user's. */
  proposal: (jobId: number, proposalId: number) =>
    apiRequest<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals/${proposalId}`),
  /** Only the PENDING -> CONFIRMING claim succeeds; a second confirm gets 409 or 404. */
  confirm: (jobId: number, proposalId: number, body: ConfirmTailorProposalRequest) =>
    apiRequest<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals/${proposalId}/confirm`, {
      method: 'POST',
      body,
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
  /** `path` is a validated /jobs/{jobId}/tailored/{file} path; see tailor/files.ts. */
  download: (path: string) => apiDownload(path),
}

/**
 * ENHANCED ("深度增强"), a separate product mode. `resumeId` is always the prepared DOCX
 * (a PDF's internal copy). Read proposals with tailorApi.proposal.
 */
export const enhancedApi = {
  /** Read-only: no AI call and no write (writerCalls/openaiCalls are 0). */
  scan: (jobId: number, resumeId: number) =>
    apiRequest<V3OpportunityMap>(`/jobs/${jobId}/tailor-enhanced/opportunity`, {
      method: 'POST',
      query: { resumeId },
      body: {},
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
  /** SKIPPED returns 200 and writes nothing; other answers save evidence (201), upserted by topic and entry. */
  answer: (jobId: number, resumeId: number, body: AnswerEnhancedQuestionRequest) =>
    apiRequest<AnswerEnhancedQuestionResponse>(`/jobs/${jobId}/tailor-enhanced/answers`, {
      method: 'POST',
      query: { resumeId },
      body,
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
  /** 201 PENDING proposal with entry/skill reviews and no file. Not idempotent. */
  createProposal: (jobId: number, resumeId: number) =>
    apiRequest<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals`, {
      method: 'POST',
      query: { resumeId, productMode: 'ENHANCED' },
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
  /** Writes the file for the selected entries and skill reviews only. */
  confirm: (jobId: number, proposalId: number, body: ConfirmEnhancedRequest) =>
    apiRequest<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals/${proposalId}/confirm`, {
      method: 'POST',
      body,
      timeoutMs: TAILOR_TIMEOUT_MS,
    }),
}

/** Saved answers ("补充信息"). The server files them under the uploaded root resume. */
export const evidenceApi = {
  list: (resumeId: number) => apiRequest<ResumeEvidenceItem[]>(`/resumes/${resumeId}/evidence`),
  save: (resumeId: number, body: SaveResumeEvidenceRequest) =>
    apiRequest<ResumeEvidenceItem>(`/resumes/${resumeId}/evidence`, { method: 'POST', body }),
  remove: (resumeId: number, evidenceId: number) =>
    apiRequest<void>(`/resumes/${resumeId}/evidence/${evidenceId}`, { method: 'DELETE' }),
}

/** The signed-in user's own job records, addressed by the numeric personal id. */
export const jobsApi = {
  list: () => apiRequest<JobResponse[]>('/jobs'),
  get: (id: number) => apiRequest<JobResponse>(`/jobs/${id}`),
  /** Deletes the record, and with it any applied state. There is no separate "unsave". */
  remove: (id: number) => apiRequest<void>(`/jobs/${id}`, { method: 'DELETE' }),
  markApplied: (id: number) => apiRequest<JobResponse>(`/jobs/${id}/applied`, { method: 'POST' }),
  clearApplied: (id: number) => apiRequest<JobResponse>(`/jobs/${id}/applied`, { method: 'DELETE' }),
}

/** Catalog references look like `freehire:<slug>`; they always travel encoded. */
function catalogPath(catalogJobId: string): string {
  return `/job-catalog/${encodeURIComponent(catalogJobId)}`
}

export const catalogApi = {
  search: (query: CatalogSearchQuery) =>
    apiRequest<JobCatalogSearchResponse>('/job-catalog/search', {
      query: {
        q: query.q,
        city: query.city,
        cityCountry: query.cityCountry,
        page: query.page,
        size: query.size,
      },
    }),
  cities: (q: string) => apiRequest<CitySuggestion[]>('/job-catalog/cities', { query: { q } }),
  detail: (catalogJobId: string) => apiRequest<JobCatalogDetail>(catalogPath(catalogJobId)),
  /** Idempotent: a repeat save returns the existing personal record. */
  save: (catalogJobId: string) =>
    apiRequest<SaveCatalogJobResponse>(`${catalogPath(catalogJobId)}/save`, { method: 'POST' }),
  /** Saves first when needed, then marks applied. */
  markApplied: (catalogJobId: string) =>
    apiRequest<SaveCatalogJobResponse>(`${catalogPath(catalogJobId)}/applied`, { method: 'POST' }),
  clearApplied: (catalogJobId: string) =>
    apiRequest<SaveCatalogJobResponse>(`${catalogPath(catalogJobId)}/applied`, { method: 'DELETE' }),
}
