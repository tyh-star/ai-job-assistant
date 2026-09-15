import { describe, expect, it } from 'vitest'
import { resolveDashboardDensity } from './dashboardDensity'

describe('resolveDashboardDensity', () => {
  it('stays on loading until both requests finish', () => {
    expect(resolveDashboardDensity({
      loading: true,
      resumesFailed: false,
      jobsFailed: false,
      hasResume: false,
      hasJobs: false,
      hasApplications: false,
    })).toBe('loading')
  })

  it('does not treat a failed request as an empty onboarding state', () => {
    expect(resolveDashboardDensity({
      loading: false,
      resumesFailed: true,
      jobsFailed: true,
      hasResume: false,
      hasJobs: false,
      hasApplications: false,
    })).toBe('error')

    expect(resolveDashboardDensity({
      loading: false,
      resumesFailed: false,
      jobsFailed: true,
      hasResume: true,
      hasJobs: false,
      hasApplications: false,
    })).toBe('sparse')

    expect(resolveDashboardDensity({
      loading: false,
      resumesFailed: false,
      jobsFailed: true,
      hasResume: false,
      hasJobs: false,
      hasApplications: false,
    })).toBe('sparse')
  })

  it('maps occupied modules to empty, sparse, balanced, and active', () => {
    const base = { loading: false, resumesFailed: false, jobsFailed: false }
    expect(resolveDashboardDensity({ ...base, hasResume: false, hasJobs: false, hasApplications: false })).toBe('empty')
    expect(resolveDashboardDensity({ ...base, hasResume: true, hasJobs: false, hasApplications: false })).toBe('sparse')
    expect(resolveDashboardDensity({ ...base, hasResume: true, hasJobs: true, hasApplications: false })).toBe('balanced')
    expect(resolveDashboardDensity({ ...base, hasResume: true, hasJobs: true, hasApplications: true })).toBe('active')
  })
})
