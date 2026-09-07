import { api } from './client'
import { endpoints } from './endpoints'
import type { FeedbackRequest, FeedbackResponse, FeedbackSummaryResponse } from './types'

export function getMyFeedback() {
  return api.get<FeedbackResponse>(endpoints.feedbackMe)
}

export function saveMyFeedback(body: FeedbackRequest) {
  return api.put<FeedbackResponse>(endpoints.feedbackMe, body)
}

export function deleteMyFeedback() {
  return api.delete(endpoints.feedbackMe)
}

/** Public -- no account needed. Never a comment or identity, only the two aggregates. */
export function getFeedbackSummary() {
  return api.get<FeedbackSummaryResponse>(endpoints.feedbackSummary)
}
