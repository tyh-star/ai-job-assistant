/**
 * Copies of the backend tailoring DTOs (aijob.dto.TailoredResumeResponse, TailorChange,
 * RejectedTailorChange, ConfirmTailorProposalRequest, V3* review and opportunity types,
 * ResumeEvidenceItemResponse). Only fields the app reads are typed; generatedFilePath is
 * never serialized by the server.
 */

export type TailorMode = 'CONSERVATIVE' | 'AGGRESSIVE'

export type TailorEvidenceType = 'VERIFIED' | 'INFERRED' | 'USER_EDITED'

export type TailorChange = {
  nodeId: string
  originalText: string
  newText: string
  reason?: string | null
  reasonZh?: string | null
  upliftType?: string | null
  improvementSummaryZh?: string | null
  improvementSummaryEn?: string | null
  evidenceType?: TailorEvidenceType | string | null
  inferredSkill?: string | null
  requiresConfirmation?: boolean | null
  applied?: boolean | null
  evidenceNodeIds?: string[] | null
  evidenceQuote?: string | null
}

export type RejectedTailorChange = { nodeId: string | null; reasonCode: string }

/** PENDING / CONFIRMING / CONFIRMED for proposals; APPLIED, NO_SUPPORTED_GAP, ... for direct runs. */
export type TailorStatus = string

export type V3ReviewNode = { nodeId: string; text: string }

export type V3EntryReview = {
  canonicalEntryKey: string
  entryTitle: string
  originalNodes: V3ReviewNode[]
  proposedNodes: V3ReviewNode[]
  whyBetter: string
  whyBetterZh?: string | null
  suggestionType: string
  usedEvidenceItemIds: number[]
}

export type V3SkillReview = {
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

export type V3EvidenceUsage = {
  evidenceItemId: number
  canonicalEntryKey?: string
  topicKey?: string
  usageStatus: string
  reasonCode?: string
  usedInEntryKeys?: string[]
  usedInSkillKeys?: string[]
}

export type TailoredResumeResponse = {
  resumeId: number
  jobId: number
  /** ENHANCED proposals carry mode CONSERVATIVE: productMode is what identifies them. */
  mode: TailorMode
  generatedFileName: string | null
  /** e.g. /api/v1/jobs/{jobId}/tailored/{uuid}.docx -- null until a file exists. */
  downloadUrl: string | null
  pdfDownloadUrl: string | null
  pdfExportStatus: 'SUCCESS' | 'FAILED' | null
  changes: TailorChange[] | null
  unchanged: boolean
  hasSuggestions: boolean
  appliedChangeCount: number
  rejectedChangeCount: number
  rejectedChanges: RejectedTailorChange[] | null
  proposalId: number | null
  status: TailorStatus | null
  /** Server LocalDateTime in UTC, without an offset. */
  expiresAt: string | null
  productMode?: string | null
  engineVersion?: string | null
  hasMoreSuggestions?: boolean | null
  rewrittenEntryCount?: number | null
  unchangedEntryCount?: number | null
  entryReviews?: V3EntryReview[] | null
  skillReviews?: V3SkillReview[] | null
  evidenceUsage?: V3EvidenceUsage[] | null
  evidenceItemIds?: number[] | null
}

export type ConfirmTailorProposalRequest = { selectedNodeIds: string[] }

export type EditedTailorChange = { nodeId: string; newText: string }

/** The ENHANCED confirm body. AGGRESSIVE's selectedNodeIds is never part of it. */
export type ConfirmEnhancedRequest = {
  selectedEntryKeys?: string[]
  editedEntries?: { canonicalEntryKey: string; nodes: EditedTailorChange[] }[]
  selectedSkillReviewIds?: string[]
  editedSkillReviews?: { skillReviewId: string; proposedSkills?: string; nodes?: EditedTailorChange[] }[]
}

export type ResumeStructureResponse = { resumeId: number; reviewStatus?: string | null }

export type V3JdRequirementBreakdown = {
  requirementId?: string
  requirementText: string
  status: string
  coveredAtoms?: string[]
  missingAtoms?: string[]
}

export type V3OpportunityEntry = {
  canonicalEntryKey: string
  entryTitle: string
  entryType: string
  priority: string
  coveredRequirements: string[]
  partiallyCoveredRequirements: string[]
  canStrengthenWithExistingFacts: string[]
  missingFactsThatCanBeConfirmed: string[]
  cannotInvent: string[]
  missingRequirements?: string[]
  requirements?: V3JdRequirementBreakdown[]
}

export type V3EnhancedQuestion = {
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

export type V3OpportunityMap = {
  entries: V3OpportunityEntry[]
  questions: V3EnhancedQuestion[]
  skippedQuestionIds: string[]
  writerCalls: number
  openaiCalls: number
  wroteJobOrResume: boolean
}

export type AnswerStatus = 'CONFIRMED_USED' | 'CONFIRMED_NOT_USED' | 'SKIPPED'

export type AnswerEnhancedQuestionRequest = {
  questionId: string
  answerStatus: AnswerStatus
  answerText?: string
  entryKey?: string
}

export type ResumeEvidenceItem = {
  id: number
  resumeId: number
  topic: string
  topicKey: string
  category: string
  answerStatus: string
  entryKey: string
  entryLabel: string
  relatedNodeIds: string[]
  context: string | null
  action: string | null
  result: string | null
  metric: string | null
  provenance?: string | null
  createdAt?: string
  updatedAt?: string
}

export type AnswerEnhancedQuestionResponse = {
  questionId: string
  answerStatus: string
  canonicalEntryKey: string
  evidence?: ResumeEvidenceItem | null
  skipped: boolean
}

export type SaveResumeEvidenceRequest = {
  topic: string
  category: string
  answerStatus: 'CONFIRMED_USED' | 'CONFIRMED_NOT_USED'
  entryKey: string
  relatedNodeIds: string[]
  context: string
  action: string
  result: string
  metric: string
  confirmed: true
}
