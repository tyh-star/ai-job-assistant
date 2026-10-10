export type DashboardDensity = 'loading' | 'error' | 'empty' | 'sparse' | 'balanced' | 'active'

export function resolveDashboardDensity({
  loading,
  resumesFailed,
  jobsFailed,
  hasResume,
  hasJobs,
  hasApplications,
}: {
  loading: boolean
  resumesFailed: boolean
  jobsFailed: boolean
  hasResume: boolean
  hasJobs: boolean
  hasApplications: boolean
}): DashboardDensity {
  if (loading) {
    return 'loading'
  }
  if (resumesFailed && jobsFailed) {
    return 'error'
  }

  const occupiedCount = [hasResume, hasJobs, hasApplications].filter(Boolean).length
  if (resumesFailed || jobsFailed) {
    return occupiedCount <= 1 ? 'sparse' : occupiedCount === 2 ? 'balanced' : 'active'
  }
  if (occupiedCount === 0) {
    return 'empty'
  }
  if (occupiedCount === 1) {
    return 'sparse'
  }
  if (occupiedCount === 2) {
    return 'balanced'
  }
  return 'active'
}
