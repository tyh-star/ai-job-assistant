/**
 * Copies of the Spring Boot DTOs this app reads. Kept here rather than imported from
 * frontend/ so the mobile project stays independent; when a backend DTO changes,
 * update both.
 */

export interface ApiErrorBody {
  status?: number
  error?: string
  message?: string
  code?: string
}

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
  createdAt: string
}

export interface StoredUser {
  userId: number
  name: string
  email: string
  role: string
}

export type ResumeOrigin = 'UPLOAD' | 'TEMPLATE' | 'GENERATED'

export interface ResumeResponse {
  id: number
  fileName: string
  fileType: string
  createdAt: string
  updatedAt: string
  origin: ResumeOrigin | string
  reviewStatus: string
  parentResumeId: number | null
  canonicalResumeId: number | null
  fileAvailable: boolean
  downloadUrl: string | null
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
  applied: boolean
  appliedAt: string | null
  createdAt: string
  updatedAt: string
}

export type ResumeBuilderTemplateType =
  | 'STUDENT'
  | 'INTERNSHIP'
  | 'EXPERIENCED'
  | 'CAREER_CHANGE'
  | 'TECHNICAL'
  | 'GENERIC_ATS'

export interface ResumeBuilderDraftSummary {
  id: number
  title: string
  templateType: ResumeBuilderTemplateType | string
  targetRole: string | null
  locale: string
  version: number
  createdAt: string
  updatedAt: string
}

/** The draft fields the app reads; section arrays are only counted, never edited here. */
export interface ResumeBuilderDraftContent {
  basicInfo: {
    fullName?: string | null
    email?: string | null
    phone?: string | null
    city?: string | null
    country?: string | null
  }
  jobTarget: {
    targetTitle?: string | null
  }
  education?: unknown[]
  experience?: unknown[]
  projects?: unknown[]
  skillCategories?: unknown[]
  certifications?: unknown[]
  languages?: unknown[]
  volunteer?: unknown[]
  leadership?: unknown[]
  publications?: unknown[]
  customSections?: unknown[]
}

export interface ResumeBuilderDraftResponse extends ResumeBuilderDraftSummary {
  content: ResumeBuilderDraftContent
}

/* ------------------------------------------------------------ job catalog */

export type CatalogRemoteType = 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNSPECIFIED'

export type CatalogEmploymentType =
  | 'FULL_TIME'
  | 'PART_TIME'
  | 'CONTRACT'
  | 'INTERNSHIP'
  | 'TEMPORARY'
  | 'OTHER'
  | 'UNSPECIFIED'

/** `value` is opaque: send it back verbatim as the search's `city`. */
export interface CitySuggestion {
  value: string
  countryCode: string | null
}

/**
 * One posting in the shared catalog. `id` is the catalog reference (for example
 * `freehire:<slug>`), a string that must never be confused with a personal
 * `JobResponse.id`. `personalJobId` is that personal id once this user saved it.
 */
export interface JobCatalogSummary {
  id: string
  title: string
  companyName: string
  locationText: string | null
  countryCode: string | null
  remoteType: CatalogRemoteType | string
  employmentType: CatalogEmploymentType | string
  salaryMin: number | null
  salaryMax: number | null
  currency: string | null
  canonicalUrl: string | null
  source: string | null
  postedAt: string | null
  firstSeenAt: string | null
  lastSeenAt: string | null
  status: string
  saved: boolean
  personalJobId: number | null
  applied: boolean
  appliedAt: string | null
  cityMatch: { city: string; countryCode: string | null; countryConfirmed: boolean } | null
}

/** Pages are 1-based; `hasMore` is the backend's own "page * size < total". */
export interface JobCatalogSearchResponse {
  page: number
  size: number
  totalCount: number
  totalPages: number
  hasMore: boolean
  results: JobCatalogSummary[]
}

export interface JobCatalogDetail extends Omit<JobCatalogSummary, 'cityMatch'> {
  descriptionText: string | null
  descriptionHtml: string | null
  city: string | null
  region: string | null
  applyUrl: string | null
}

/** `jobId` is the personal job id the save created (or found). */
export interface SaveCatalogJobResponse {
  jobId: number
  catalogJobId: string
  alreadySaved: boolean
  job: JobResponse
}

export interface CatalogSearchQuery {
  q?: string
  city?: string
  cityCountry?: string
  page: number
  size: number
}

/* ------------------------------------------------------------ resume quality */

/** `code` is one of STRUCTURE, EXPERIENCE, ACHIEVEMENTS, SKILLS, READABILITY. */
export interface ResumeQualityCategoryScore {
  code: string
  score: number
  maxScore: number
}

/**
 * When `scorable` is false, `score` and `level` are null and `reasonCode` says why.
 * `strengths` and `suggestions` are codes, translated on the client.
 */
export interface ResumeQualityScoreResponse {
  scorable: boolean
  reasonCode: string | null
  score: number | null
  level: string | null
  rubricVersion: string | null
  categories: ResumeQualityCategoryScore[] | null
  strengths: string[] | null
  suggestions: string[] | null
}

/* ------------------------------------------------------------ account */

export interface UpdateNameRequest {
  name: string
}

export interface UpdateEmailRequest {
  currentPassword: string
  newEmail: string
}

/** The email is the JWT subject, so a changed email always comes with a new token. */
export interface UpdateEmailResponse {
  token: string
  user: UserResponse
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/* ------------------------------------------------------------ site feedback */

export interface FeedbackRequest {
  /** 1 to 5. */
  rating: number
  /** Optional, at most 1000 characters; blank is stored as null. */
  comment: string | null
}

export interface FeedbackResponse {
  rating: number
  comment: string | null
  createdAt: string
  updatedAt: string
}
