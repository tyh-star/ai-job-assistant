import { describe, expect, it } from 'vitest'
import {
  loginPathAfterSessionExpiry,
  loginPathForAnonymousFeature,
  loginPathWithReturnTo,
  sanitizeReturnTo,
} from './returnTo'

describe('sanitizeReturnTo', () => {
  it('never returns to the login or register page, which would loop after logging in', () => {
    expect(sanitizeReturnTo('/login')).toBe('/dashboard')
    expect(sanitizeReturnTo('/login?returnTo=%2Fresumes&reason=session_expired')).toBe('/dashboard')
    expect(sanitizeReturnTo('/register/')).toBe('/dashboard')
    expect(sanitizeReturnTo('%2Flogin')).toBe('/dashboard')
    expect(sanitizeReturnTo('/resumes?from=login')).toBe('/resumes?from=login')
  })

  it('defaults a missing returnTo to the signed-in dashboard', () => {
    expect(sanitizeReturnTo(null)).toBe('/dashboard')
    expect(sanitizeReturnTo('')).toBe('/dashboard')
  })

  it('keeps same-site paths including a jobs tab query', () => {
    expect(sanitizeReturnTo('/jobs?tab=favorites')).toBe('/jobs?tab=favorites')
    expect(sanitizeReturnTo('/resumes/builder')).toBe('/resumes/builder')
  })

  it('rejects off-site and encoded off-site values', () => {
    expect(sanitizeReturnTo('https://evil.com')).toBe('/dashboard')
    expect(sanitizeReturnTo('//evil.com')).toBe('/dashboard')
    expect(sanitizeReturnTo('/\\evil.com')).toBe('/dashboard')
    expect(sanitizeReturnTo('/http://evil.com')).toBe('/dashboard')
    expect(sanitizeReturnTo('/javascript:alert(1)')).toBe('/dashboard')
    expect(sanitizeReturnTo('%2F%2Fevil.com')).toBe('/dashboard')
  })
})

describe('login path reasons', () => {
  it('marks a first-time anonymous feature click as login_required', () => {
    expect(loginPathForAnonymousFeature('/resumes/builder')).toBe(
      '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required',
    )
    expect(loginPathForAnonymousFeature('/jobs?tab=favorites')).toBe(
      '/login?returnTo=%2Fjobs%3Ftab%3Dfavorites&reason=login_required',
    )
  })

  it('marks a stale-token redirect as session_expired', () => {
    expect(loginPathAfterSessionExpiry('/profile')).toBe(
      '/login?returnTo=%2Fprofile&reason=session_expired',
    )
  })

  it('omits a reason unless one is supplied', () => {
    expect(loginPathWithReturnTo('/jobs')).toBe('/login?returnTo=%2Fjobs')
  })
})
