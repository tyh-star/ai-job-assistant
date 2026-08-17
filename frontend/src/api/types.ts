export type ResumeOrigin = 'UPLOAD' | 'TEMPLATE'
export type ResumeReviewStatus = 'READY' | 'PENDING_REVIEW'
export type TailorMode = 'CONSERVATIVE' | 'AGGRESSIVE'
export type PdfExportStatus = 'SUCCESS' | 'FAILED'

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  name: string
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  name: string
  email: string
  role: string
}

export interface UserResponse {
  id: number
  name: string
  email: string
  role: string
}

export interface StoredUser {
  userId: number
  name: string
  email: string
  role: string
}

export interface ResumeResponse {
  id: number
  fileName: string
  fileType: string
  createdAt: string
  updatedAt: string
  origin: ResumeOrigin | string
  reviewStatus: ResumeReviewStatus | string
  parentResumeId: number | null
  canonicalResumeId: number | null
  fileAvailable: boolean
  downloadUrl: string | null
}

export interface ResumeBullet {
  text: string
  sourceBlockIds?: string[]
  sourcePage?: number | null
  sourceText?: string
  confidence?: string
  provenance?: string
}

export interface ResumeContact {
  fullName: string
  email: string
  phone: string
  location: string
  links: string[]
}

export interface ResumeSkill {
  name: string
  sourceBlockIds?: string[]
  sourcePage?: number | null
  sourceText?: string
  confidence?: string
  provenance?: string
}

export interface ResumeExperience {
  company: string
  title: string
  startDate: string
  endDate: string
  location: string
  bullets: string[]
  sourceBlockIds?: string[]
  sourcePage?: number | null
  sourceText?: string
  confidence?: string
  provenance?: string
}

export interface ResumeEducation {
  school: string
  degree: string
  dates: string
  details: string
  sourceBlockIds?: string[]
  sourcePage?: number | null
  sourceText?: string
  confidence?: string
  provenance?: string
}

export interface ResumeProject {
  name: string
  organizationOrType: string
  dates: string
  bullets: string[]
  links: string[]
  sourceBlockIds?: string[]
  sourcePage?: number | null
  sourceText?: string
  confidence?: string
  provenance?: string
}

export interface ResumeTextBlock {
  id: string
  page: number
  text: string
}

export interface ResumeStructure {
  resumeId?: number
  reviewStatus?: string
  extractionStatus?: string
  llmClassified?: boolean
  structureFormatVersion?: number
  contact: ResumeContact
  summary?: string
  summaryBullets: ResumeBullet[]
  skills: ResumeSkill[]
  experience: ResumeExperience[]
  education: ResumeEducation[]
  projects: ResumeProject[]
  unassigned?: ResumeTextBlock[]
  fieldConfidence?: Record<string, string>
  fieldProvenance?: Record<string, string>
  layoutProfile?: unknown
}

export interface JobResponse {
  id: number
  company: string
  title: string
  location: string
  jobUrl: string
  description: string
  source: string
  status: string
  externalJobId: string | null
  createdAt: string
  updatedAt: string
}

export interface JobForm {
  company: string
  title: string
  location: string
  jobUrl: string
  description: string
}

export interface BulkDeleteJobsResponse {
  deletedCount: number
}

export interface ImportJobsResponse {
  importedCount: number
  skippedDuplicateCount: number
  skippedNoTitleCount: number
  skippedNoUrlCount: number
  skippedNoDescriptionCount: number
  skippedOverLimitCount: number
  jobs: JobResponse[]
}

export interface TailorChange {
  nodeId: string
  originalText: string
  newText: string
  reason: string
  evidenceType: string
  inferredSkill?: string
  requiresConfirmation?: boolean
  applied?: boolean
  evidenceNodeIds?: string[]
  evidenceQuote?: string
}

export interface RejectedTailorChange {
  nodeId: string
  reasonCode: string
}

export interface TailoredResumeResponse {
  resumeId: number
  jobId: number
  mode: TailorMode | string
  generatedFileName?: string
  downloadUrl?: string
  pdfDownloadUrl?: string
  pdfExportStatus?: PdfExportStatus | string
  changes: TailorChange[]
  unchanged: boolean
  appliedChangeCount: number
  rejectedChangeCount: number
  rejectedChanges: RejectedTailorChange[]
  proposalId?: number | null
  status?: string
  expiresAt?: string | null
}

export interface ApiErrorBody {
  status?: number
  error?: string
  message?: string
}
