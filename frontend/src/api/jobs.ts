import { api } from './client'
import { endpoints } from './endpoints'
import type {
  BulkDeleteJobsResponse,
  ConfirmTailorProposalRequest,
  EditedTailorChange,
  EditedTailorEntry,
  EditedSkillReview,
  ImportJobsResponse,
  JobForm,
  JobResponse,
  JobSearchResponse,
  ImportSearchResultsRequest,
  ImportSearchResultsResponse,
  SaveJobSearchResultRequest,
  SaveJobSearchResultResponse,
  TailorMode,
  TailoredResumeResponse,
  TailorAnalysisResponse,
  V3OpportunityMap,
  ScanEnhancedOpportunityRequest,
  AnswerEnhancedQuestionRequest,
  AnswerEnhancedQuestionResponse,
} from './types'

export function listJobs() {
  return api.get<JobResponse[]>(endpoints.jobs)
}

export function createJob(body: JobForm) {
  return api.post<JobResponse>(endpoints.jobs, body)
}

export function updateJob(id: number, body: JobForm) {
  return api.put<JobResponse>(endpoints.job(id), body)
}

export function deleteJob(id: number) {
  return api.delete(endpoints.job(id))
}

export function bulkDeleteJobs(jobIds: number[]) {
  return api.delete<BulkDeleteJobsResponse>(endpoints.jobsBulk, { data: { jobIds } })
}

export function importGreenhouse(boardToken: string) {
  return api.post<ImportJobsResponse>(endpoints.jobsGreenhouse, { boardToken })
}

export function searchJobs(params: {
  keywords: string
  location?: string
  postedWithinDays?: number
  page?: number
  pageSize?: number
}) {
  return api.get<JobSearchResponse>(endpoints.jobsSearch, {
    params: {
      keywords: params.keywords,
      location: params.location || undefined,
      postedWithinDays: params.postedWithinDays,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 10,
    },
  })
}

export function fetchNextSearchPage(searchSessionId: string, expectedPage: number) {
  return api.post<JobSearchResponse>(endpoints.jobsSearchNext(searchSessionId), {
    expectedPage,
  })
}

export function saveSearchResult(body: SaveJobSearchResultRequest) {
  return api.post<SaveJobSearchResultResponse>(endpoints.jobsSearchSave, body)
}

export function importSearchResults(body: ImportSearchResultsRequest) {
  return api.post<ImportSearchResultsResponse>(endpoints.jobsSearchImport, body)
}

export function analyzeTailorFit(jobId: number, resumeId: number) {
  return api.post<TailorAnalysisResponse>(endpoints.jobTailorAnalysis(jobId), null, {
    params: { resumeId },
  })
}

export function refreshJobRequirementProfile(jobId: number, resumeId: number) {
  return api.post<TailorAnalysisResponse>(endpoints.jobTailorAnalysisRefresh(jobId), null, {
    params: { resumeId },
  })
}

export function tailorResume(jobId: number, resumeId: number, mode: TailorMode) {
  return api.post<TailoredResumeResponse>(endpoints.jobTailor(jobId), null, {
    params: { resumeId, mode },
  })
}

export function createTailorProposal(jobId: number, resumeId: number, productMode?: string) {
  return api.post<TailoredResumeResponse>(endpoints.jobTailorProposals(jobId), null, {
    params: productMode ? { resumeId, productMode } : { resumeId },
  })
}

export function getTailorProposal(jobId: number, proposalId: number) {
  return api.get<TailoredResumeResponse>(endpoints.jobTailorProposal(jobId, proposalId))
}

export function scanEnhancedOpportunity(
  jobId: number,
  resumeId: number,
  body?: ScanEnhancedOpportunityRequest,
) {
  return api.post<V3OpportunityMap>(endpoints.jobEnhancedOpportunity(jobId), body ?? {}, {
    params: { resumeId },
  })
}

export function answerEnhancedQuestion(
  jobId: number,
  resumeId: number,
  body: AnswerEnhancedQuestionRequest,
) {
  return api.post<AnswerEnhancedQuestionResponse>(
    endpoints.jobEnhancedAnswers(jobId),
    body,
    { params: { resumeId } },
  )
}

export function confirmTailorProposal(
  jobId: number,
  proposalId: number,
  selectedNodeIds?: string[],
  editedChanges?: EditedTailorChange[],
  extra?: {
    selectedEntryKeys?: string[]
    editedEntries?: EditedTailorEntry[]
    selectedSkillReviewIds?: string[]
    editedSkillReviews?: EditedSkillReview[]
  },
) {
  const body: ConfirmTailorProposalRequest = {}
  if (selectedNodeIds && selectedNodeIds.length > 0) {
    body.selectedNodeIds = selectedNodeIds
  }
  if (editedChanges && editedChanges.length > 0) {
    body.editedChanges = editedChanges
  }
  if (extra?.selectedEntryKeys && extra.selectedEntryKeys.length > 0) {
    body.selectedEntryKeys = extra.selectedEntryKeys
  }
  if (extra?.editedEntries && extra.editedEntries.length > 0) {
    body.editedEntries = extra.editedEntries
  }
  if (extra?.selectedSkillReviewIds && extra.selectedSkillReviewIds.length > 0) {
    body.selectedSkillReviewIds = extra.selectedSkillReviewIds
  }
  if (extra?.editedSkillReviews && extra.editedSkillReviews.length > 0) {
    body.editedSkillReviews = extra.editedSkillReviews
  }
  return api.post<TailoredResumeResponse>(
    endpoints.jobTailorConfirm(jobId, proposalId),
    body,
  )
}

export function continueTailorProposal(jobId: number, proposalId: number) {
  return api.post<TailoredResumeResponse>(
    endpoints.jobTailorContinue(jobId, proposalId),
  )
}
