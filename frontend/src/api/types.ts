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

export type CatalogJobStatus = 'ACTIVE' | 'EXPIRED' | 'REMOVED'

export type CatalogRemoteType = 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNSPECIFIED'

export type CatalogEmploymentType =
  | 'FULL_TIME'
  | 'PART_TIME'
  | 'CONTRACT'
  | 'INTERNSHIP'
  | 'TEMPORARY'
  | 'OTHER'
  | 'UNSPECIFIED'

export interface JobCatalogSearchParams {
  q?: string
  location?: string
  country?: string
  remoteType?: string
  employmentType?: string
  postedWithinDays?: number
  page?: number
  size?: number
  sort?: string
}

/**
 * A row in the shared catalog. Never carries a raw provider payload.
 *
 * `id` is an opaque per-provider reference -- a plain numeric string under
 * local_catalog, or `freehire:<public_slug>` under the FreeHire provider. Never parse
 * it as a number; pass it back verbatim to the detail/save endpoints.
 */
export interface JobCatalogSummary {
  id: string
  title: string
  companyName: string
  locationText: string | null
  countryCode: string | null
  remoteType: CatalogRemoteType
  employmentType: CatalogEmploymentType
  salaryMin: number | null
  salaryMax: number | null
  currency: string | null
  applyUrl: string | null
  canonicalUrl: string | null
  source: string | null
  postedAt: string | null
  firstSeenAt: string | null
  lastSeenAt: string | null
  status: CatalogJobStatus
  descriptionSnippet: string | null
  saved: boolean
  personalJobId: number | null
}

export interface JobCatalogSearchResponse {
  page: number
  size: number
  totalCount: number
  totalPages: number
  hasMore: boolean
  results: JobCatalogSummary[]
}

export interface JobCatalogDetail extends Omit<JobCatalogSummary, 'descriptionSnippet'> {
  descriptionText: string | null
  /** Server-sanitized HTML. */
  descriptionHtml: string | null
  city: string | null
  region: string | null
}

/** `jobId` is an id in the user's own jobs, usable by Match and Tailor as-is. */
export interface SaveCatalogJobResponse {
  jobId: number
  /** The same opaque per-provider reference as `JobCatalogSummary.id`. */
  catalogJobId: string
  alreadySaved: boolean
  job: JobResponse
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

export interface JobSearchResult {
  externalJobId: string | null
  title: string
  company: string
  location: string | null
  postedAt: string | null
  descriptionSnippet: string
  source: string
  sourceUrl: string
  publisher?: string | null
  fullDescriptionAvailable?: boolean
  alreadySaved: boolean
}

export interface JobSearchResponse {
  searchSessionId: string
  provider: string
  page: number
  pageSize: number
  hasMore: boolean | null
  totalCount: number | null
  results: JobSearchResult[]
  limitReached?: boolean | null
  loadedResultCount?: number | null
}

export interface ImportSearchResultsRequest {
  searchSessionId: string
  selectedExternalJobIds: string[]
}

export interface ImportedSearchJob {
  externalJobId: string
  jobId: number
}

export interface FailedSearchImport {
  externalJobId: string
  reason: string
}

export interface ImportSearchResultsResponse {
  imported: ImportedSearchJob[]
  existing: ImportedSearchJob[]
  failed: FailedSearchImport[]
}

export interface SaveJobSearchResultRequest {
  externalJobId: string | null
  title: string
  company: string
  location: string | null
  postedAt: string | null
  descriptionSnippet: string
  source: string
  sourceUrl: string
  fullDescription: string
}

export interface SaveJobSearchResultResponse {
  created: boolean
  job: JobResponse
}

export interface TailorChange {
  nodeId: string
  originalText: string
  newText: string
  reason: string
  reasonZh?: string
  upliftType?: string
  improvementSummaryZh?: string
  improvementSummaryEn?: string
  evidenceType: string
  inferredSkill?: string
  requiresConfirmation?: boolean
  applied?: boolean
  evidenceNodeIds?: string[]
  evidenceQuote?: string
  rewriteIntent?: string
}

export interface RejectedTailorChange {
  nodeId: string
  reasonCode: string
}

export interface TailorAnalysisRequirement {
  id: string
  category: string
  priority: string
  requirement: string
  exactTerms: string[]
  status: string
  evidenceKind?: string | null
  evidenceAvailability?: EvidenceAvailability | string
  evidenceItemIds?: number[]
  evidenceNodeIds: string[]
  evidenceQuotes: string[]
  coverageReason?: string | null
}

export interface TailorAnalysisDimensions {
  hardSkills: number | null
  responsibilities: number | null
  qualifications: number | null
  evidenceStrength: number | null
  atsSearchability: number | null
}

export interface TailorAnalysisSummary {
  supportedMustHaveCount: number
  partiallySupportedMustHaveCount?: number
  missingMustHaveCount?: number
  totalMustHaveCount: number
  supportedPreferredCount: number
  partiallySupportedPreferredCount?: number
  missingPreferredCount?: number
  totalPreferredCount: number
  missingEvidenceCount: number
}

export interface TailorAnalysisExplanation {
  strengths: string[]
  gaps: string[]
  nextActions: string[]
}

export interface TailorAnalysisResponse {
  resumeId: number
  jobId: number
  overallScore: number
  scoreBand?: string
  dimensions: TailorAnalysisDimensions
  scoreExplanation?: TailorAnalysisExplanation
  requirements: TailorAnalysisRequirement[]
  summary: TailorAnalysisSummary
  evidenceQuestions?: EvidenceQuestion[]
  requirementsFixed?: boolean
  profileUpdatedAt?: string
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
  hasSuggestions?: boolean
  addedSuggestionCount?: number
  hasMoreSuggestions?: boolean
  exploredNodeIds?: string[]
  eligibleNodeIds?: string[]
  remainingNodeIds?: string[]
  noChangeNodeIds?: string[]
  coverageStatus?: 'RUNNING' | 'COMPLETE' | 'PARTIAL' | 'FAILED' | string
  engineVersion?: string | null
  productMode?: string | null
  rewrittenEntryCount?: number | null
  unchangedEntryCount?: number | null
  entryReviews?: V3EntryReview[]
  evidenceItemIds?: number[]
  evidenceUsage?: V3EvidenceUsage[]
  skillReviews?: V3SkillReview[]
}

export interface EditedTailorChange {
  nodeId: string
  newText: string
}

export interface EditedTailorEntry {
  canonicalEntryKey: string
  nodes: EditedTailorChange[]
}

export interface EditedSkillReview {
  skillReviewId: string
  proposedSkills?: string
  nodes?: EditedTailorChange[]
}

export interface ConfirmTailorProposalRequest {
  selectedNodeIds?: string[]
  editedChanges?: EditedTailorChange[]
  selectedEntryKeys?: string[]
  editedEntries?: EditedTailorEntry[]
  selectedSkillReviewIds?: string[]
  editedSkillReviews?: EditedSkillReview[]
}

export interface V3EvidenceUsage {
  evidenceItemId: number
  canonicalEntryKey?: string
  topicKey?: string
  usageStatus: string
  reasonCode?: string
  usedInEntryKeys?: string[]
  usedInSkillKeys?: string[]
}

export interface V3SkillReview {
  skillReviewId: string
  originalSkills: string
  proposedSkills: string
  addedSkills?: string[]
  evidenceItemIds?: number[]
  whyBetter?: string
  admitted?: boolean
  originalNodes?: V3ReviewNode[]
  proposedNodes?: V3ReviewNode[]
}

export type OpportunityPriority = 'HIGH' | 'MEDIUM' | 'NO_ACTION' | string

export type JdRequirementStatus = 'COVERED' | 'PARTIALLY_COVERED' | 'MISSING' | string

export interface V3JdRequirementBreakdown {
  requirementId?: string
  requirementText: string
  status: JdRequirementStatus
  coveredAtoms?: string[]
  missingAtoms?: string[]
  alternativeGroups?: string[][]
}

export interface V3OpportunityEntry {
  canonicalEntryKey: string
  entryTitle: string
  entryType: string
  priority: OpportunityPriority
  coveredRequirements: string[]
  partiallyCoveredRequirements: string[]
  canStrengthenWithExistingFacts: string[]
  missingFactsThatCanBeConfirmed: string[]
  cannotInvent: string[]
  missingRequirements?: string[]
  requirements?: V3JdRequirementBreakdown[]
}

export interface V3EnhancedQuestion {
  id: string
  canonicalEntryKey: string
  entryTitle: string
  topic: string
  promptEn: string
  promptZh: string
  skipAllowed: boolean
  requirementId?: string
  requirementText?: string
  allowedEntryKeys?: string[]
}

export interface V3OpportunityMap {
  entries: V3OpportunityEntry[]
  questions: V3EnhancedQuestion[]
  skippedQuestionIds: string[]
  writerCalls: number
  openaiCalls: number
  wroteJobOrResume: boolean
}

export interface ScanEnhancedOpportunityRequest {
  skippedQuestionIds?: string[]
}

export interface AnswerEnhancedQuestionRequest {
  questionId: string
  answerStatus: AnswerStatus | string
  answerText?: string
  entryKey?: string
}

export interface AnswerEnhancedQuestionResponse {
  questionId: string
  answerStatus: string
  canonicalEntryKey: string
  evidence?: ResumeEvidenceItem | null
  skipped: boolean
}

export interface V3ReviewNode {
  nodeId: string
  text: string
}

export interface V3EntryReview {
  canonicalEntryKey: string
  entryTitle: string
  originalNodes: V3ReviewNode[]
  proposedNodes: V3ReviewNode[]
  whyBetter: string
  whyBetterZh?: string
  suggestionType: string
  usedEvidenceItemIds: number[]
}

export interface ApiErrorBody {
  status?: number
  error?: string
  message?: string
}

export type AnswerStatus = 'CONFIRMED_USED' | 'CONFIRMED_NOT_USED' | 'SKIPPED'

export type EvidenceAvailability = 'IN_RESUME' | 'USER_CONFIRMED' | 'USER_REJECTED' | 'NONE'

export type QuestionType =
  | 'SKILL_USAGE'
  | 'RESPONSIBILITY_EXAMPLE'
  | 'SKILL_APPLICATION'
  | 'IMPACT_OR_SCALE'
  | 'EDUCATION_CONFIRMATION'

export interface EntryOption {
  entryKey: string
  entryLabel: string
  entryType: string
  nodeIds: string[]
}

export interface EvidenceQuestion {
  id: string
  requirementId: string
  questionType: QuestionType | string
  topic: string
  priority: string
  promptZh: string
  promptEn: string
  entryOptions: EntryOption[]
}

export interface ResumeEvidenceItem {
  id: number
  resumeId: number
  topic: string
  topicKey: string
  category: string
  answerStatus: AnswerStatus | string
  entryKey: string
  entryLabel: string
  relatedNodeIds: string[]
  context: string
  action: string
  result: string
  metric: string
  provenance: string
  createdAt: string
  updatedAt: string
}

export interface SaveResumeEvidenceRequest {
  topic: string
  category: string
  answerStatus: AnswerStatus
  entryKey: string
  relatedNodeIds: string[]
  context: string
  action: string
  result: string
  metric: string
  confirmed: boolean
}
