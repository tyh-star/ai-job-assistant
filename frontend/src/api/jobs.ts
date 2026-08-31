import { api } from './client'
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
  return api.get<JobResponse[]>('/jobs')
}

export function createJob(body: JobForm) {
  return api.post<JobResponse>('/jobs', body)
}

export function updateJob(id: number, body: JobForm) {
  return api.put<JobResponse>(`/jobs/${id}`, body)
}

export function deleteJob(id: number) {
  return api.delete(`/jobs/${id}`)
}

export function bulkDeleteJobs(jobIds: number[]) {
  return api.delete<BulkDeleteJobsResponse>('/jobs/bulk', { data: { jobIds } })
}

export function importGreenhouse(boardToken: string) {
  return api.post<ImportJobsResponse>('/jobs/imports/greenhouse', { boardToken })
}

export function searchJobs(params: {
  keywords: string
  location?: string
  postedWithinDays?: number
  page?: number
  pageSize?: number
}) {
  return api.get<JobSearchResponse>('/jobs/search', {
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
  return api.post<JobSearchResponse>(`/jobs/search-sessions/${searchSessionId}/next`, {
    expectedPage,
  })
}

export function saveSearchResult(body: SaveJobSearchResultRequest) {
  return api.post<SaveJobSearchResultResponse>('/jobs/search-results/save', body)
}

export function importSearchResults(body: ImportSearchResultsRequest) {
  return api.post<ImportSearchResultsResponse>('/jobs/search-results/import', body)
}

export function analyzeTailorFit(jobId: number, resumeId: number) {
  return api.post<TailorAnalysisResponse>(`/jobs/${jobId}/tailor-analysis`, null, {
    params: { resumeId },
  })
}

export function refreshJobRequirementProfile(jobId: number, resumeId: number) {
  return api.post<TailorAnalysisResponse>(`/jobs/${jobId}/tailor-analysis/profile/refresh`, null, {
    params: { resumeId },
  })
}

export function tailorResume(jobId: number, resumeId: number, mode: TailorMode) {
  return api.post<TailoredResumeResponse>(`/jobs/${jobId}/tailor`, null, {
    params: { resumeId, mode },
  })
}

export function createTailorProposal(jobId: number, resumeId: number, productMode?: string) {
  return api.post<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals`, null, {
    params: productMode ? { resumeId, productMode } : { resumeId },
  })
}

export function getTailorProposal(jobId: number, proposalId: number) {
  return api.get<TailoredResumeResponse>(`/jobs/${jobId}/tailor-proposals/${proposalId}`)
}

export function scanEnhancedOpportunity(
  jobId: number,
  resumeId: number,
  body?: ScanEnhancedOpportunityRequest,
) {
  return api.post<V3OpportunityMap>(`/jobs/${jobId}/tailor-enhanced/opportunity`, body ?? {}, {
    params: { resumeId },
  })
}

export function answerEnhancedQuestion(
  jobId: number,
  resumeId: number,
  body: AnswerEnhancedQuestionRequest,
) {
  return api.post<AnswerEnhancedQuestionResponse>(
    `/jobs/${jobId}/tailor-enhanced/answers`,
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
    `/jobs/${jobId}/tailor-proposals/${proposalId}/confirm`,
    body,
  )
}

export function continueTailorProposal(jobId: number, proposalId: number) {
  return api.post<TailoredResumeResponse>(
    `/jobs/${jobId}/tailor-proposals/${proposalId}/continue`,
  )
}
