import { api } from './client'
import { endpoints } from './endpoints'
import type { JobSearchHistoryItem, RecordJobSearchHistoryRequest } from './types'

export function listJobSearchHistory() {
  return api.get<JobSearchHistoryItem[]>(endpoints.jobSearchHistory)
}

export function recordJobSearchHistory(body: RecordJobSearchHistoryRequest) {
  return api.post(endpoints.jobSearchHistory, body)
}

export function deleteJobSearchHistory(id: number) {
  return api.delete(endpoints.jobSearchHistoryItem(id))
}

export function clearJobSearchHistory() {
  return api.delete(endpoints.jobSearchHistory)
}
