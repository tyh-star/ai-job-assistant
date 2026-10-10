import { api } from './client'
import { endpoints } from './endpoints'
import type { FreeRewriteResponse, ProRewriteAnswer, ProRewriteSession } from './types'

// AI steps can take a while; the backend has its own 60 s provider timeout per call.
const REWRITE_TIMEOUT_MS = 180_000

/** Free: one request with the resume only; the response already has the download. */
export function createFreeRewrite(resumeId: number) {
  return api.post<FreeRewriteResponse>(endpoints.resumeRewriteFree, { resumeId }, { timeout: REWRITE_TIMEOUT_MS })
}

/** Pro step 1-2: analyze the job and check the resume evidence. */
export function startProRewrite(resumeId: number, jobId: number) {
  return api.post<ProRewriteSession>(endpoints.resumeRewriteProSessions, { resumeId, jobId }, { timeout: REWRITE_TIMEOUT_MS })
}

export function getProRewrite(sessionId: number) {
  return api.get<ProRewriteSession>(endpoints.resumeRewriteProSession(sessionId))
}

/** Pro step 3: answers to at most five questions. */
export function answerProRewrite(sessionId: number, answers: ProRewriteAnswer[]) {
  return api.post<ProRewriteSession>(endpoints.resumeRewriteProAnswers(sessionId), { answers })
}

/** Pro step 4: suggestions to review; no file yet. */
export function previewProRewrite(sessionId: number) {
  return api.post<ProRewriteSession>(endpoints.resumeRewriteProPreview(sessionId), null, { timeout: REWRITE_TIMEOUT_MS })
}

/** Pro step 5-6: the accepted suggestions; the file is written only now. */
export function confirmProRewrite(sessionId: number, acceptedSuggestionIds: string[]) {
  return api.post<ProRewriteSession>(
    endpoints.resumeRewriteProConfirm(sessionId),
    { acceptedSuggestionIds },
    { timeout: REWRITE_TIMEOUT_MS },
  )
}
