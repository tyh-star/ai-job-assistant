import { describe, expect, it } from 'vitest'
import { loginPathAfterSessionExpiry, sanitizeReturnTo } from './returnTo'

describe('sanitizeReturnTo', () => {
  it('never returns to the login or register page, which would loop after logging in', () => {
    expect(sanitizeReturnTo('/login')).toBe('/jobs')
    expect(sanitizeReturnTo('/login?returnTo=%2Fresumes&reason=session_expired')).toBe('/jobs')
    expect(sanitizeReturnTo('/register/')).toBe('/jobs')
    expect(sanitizeReturnTo('%2Flogin')).toBe('/jobs')
    expect(sanitizeReturnTo('/resumes?from=login')).toBe('/resumes?from=login')
  })

  it('keeps a same-site page and marks a stale-token redirect as session_expired', () => {
    expect(sanitizeReturnTo('/dashboard')).toBe('/dashboard')
    expect(loginPathAfterSessionExpiry('/dashboard')).toBe('/login?returnTo=%2Fdashboard&reason=session_expired')
  })
})
