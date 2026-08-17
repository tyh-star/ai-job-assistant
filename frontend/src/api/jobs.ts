import { api } from './client'
import type {
  BulkDeleteJobsResponse,
  ImportJobsResponse,
  JobForm,
  JobResponse,
  TailorMode,
  TailoredResumeResponse,
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

export function tailorResume(jobId: number, resumeId: number, mode: TailorMode) {
  return api.post<TailoredResumeResponse>(`/jobs/${jobId}/tailor`, null, {
    params: { resumeId, mode },
  })
}

export function confirmTailorProposal(
  jobId: number,
  proposalId: number,
  selectedNodeIds: string[],
) {
  return api.post<TailoredResumeResponse>(
    `/jobs/${jobId}/tailor-proposals/${proposalId}/confirm`,
    { selectedNodeIds },
  )
}
