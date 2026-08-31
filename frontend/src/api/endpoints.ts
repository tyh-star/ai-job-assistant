/**
 * Scheme A relative endpoints. Join with base `/api/v1` via joinApiUrl.
 * Do not prefix these with `/api/v1`.
 */
export const endpoints = {
  login: '/auth/login',
  register: '/auth/register',
  me: '/users/me',
  resumes: '/resumes',
  resume: (id: number | string) => `/resumes/${id}`,
  resumeFamily: (id: number | string) => `/resumes/${id}/family`,
  resumeStructure: (id: number | string) => `/resumes/${id}/structure`,
  resumeCanonicalize: (id: number | string) => `/resumes/${id}/canonicalize`,
  resumeEvidence: (resumeId: number | string) => `/resumes/${resumeId}/evidence`,
  resumeEvidenceItem: (resumeId: number | string, evidenceId: number | string) =>
    `/resumes/${resumeId}/evidence/${evidenceId}`,
  jobs: '/jobs',
  job: (id: number | string) => `/jobs/${id}`,
  jobsBulk: '/jobs/bulk',
  jobsSearch: '/jobs/search',
  jobsSearchNext: (sessionId: string) => `/jobs/search-sessions/${sessionId}/next`,
  jobsSearchSave: '/jobs/search-results/save',
  jobsSearchImport: '/jobs/search-results/import',
  jobsGreenhouse: '/jobs/imports/greenhouse',
  jobTailorAnalysis: (jobId: number | string) => `/jobs/${jobId}/tailor-analysis`,
  jobTailorAnalysisRefresh: (jobId: number | string) =>
    `/jobs/${jobId}/tailor-analysis/profile/refresh`,
  jobTailor: (jobId: number | string) => `/jobs/${jobId}/tailor`,
  jobTailorProposals: (jobId: number | string) => `/jobs/${jobId}/tailor-proposals`,
  jobTailorProposal: (jobId: number | string, proposalId: number | string) =>
    `/jobs/${jobId}/tailor-proposals/${proposalId}`,
  jobEnhancedOpportunity: (jobId: number | string) => `/jobs/${jobId}/tailor-enhanced/opportunity`,
  jobEnhancedAnswers: (jobId: number | string) => `/jobs/${jobId}/tailor-enhanced/answers`,
  jobTailorConfirm: (jobId: number | string, proposalId: number | string) =>
    `/jobs/${jobId}/tailor-proposals/${proposalId}/confirm`,
  jobTailorContinue: (jobId: number | string, proposalId: number | string) =>
    `/jobs/${jobId}/tailor-proposals/${proposalId}/continue`,
} as const

/** Backend routes the UI does not wrap, still joined with the same Scheme A rule. */
export const backendOnlyRelativePaths = [
  '/resumes/9/analyze',
  '/resumes/9/document-nodes',
  '/resumes/9/download',
  '/jobs/7/match',
  '/jobs/7/tailored/out.docx',
  '/jobs/7/tailored/out.pdf',
] as const

export function frontendRelativeEndpoints(): string[] {
  return [
    endpoints.login,
    endpoints.register,
    endpoints.me,
    endpoints.resumes,
    endpoints.resume(9),
    endpoints.resumeFamily(9),
    endpoints.resumeStructure(9),
    endpoints.resumeCanonicalize(9),
    endpoints.resumeEvidence(9),
    endpoints.resumeEvidenceItem(9, 3),
    endpoints.jobs,
    endpoints.job(7),
    endpoints.jobsBulk,
    endpoints.jobsSearch,
    endpoints.jobsSearchNext('session-1'),
    endpoints.jobsSearchSave,
    endpoints.jobsSearchImport,
    endpoints.jobsGreenhouse,
    endpoints.jobTailorAnalysis(7),
    endpoints.jobTailorAnalysisRefresh(7),
    endpoints.jobTailor(7),
    endpoints.jobTailorProposals(7),
    endpoints.jobTailorProposal(7, 42),
    endpoints.jobEnhancedOpportunity(7),
    endpoints.jobEnhancedAnswers(7),
    endpoints.jobTailorConfirm(7, 42),
    endpoints.jobTailorContinue(7, 42),
  ]
}
