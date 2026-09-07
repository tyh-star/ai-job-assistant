import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosError } from 'axios'
import {
  SESSION_EXPIRED_EVENT,
  handleResponseError,
  isLoginRequiredJobsAction,
  isProtectedPath,
} from './client'
import { getToken, saveSession } from '../auth/session'

function axios401(requestUrl: string) {
  return {
    config: { url: requestUrl },
    response: { status: 401, data: { message: 'This token has been revoked. Please log in again.' } },
  } as AxiosError
}

function axios500(requestUrl = '/jobs') {
  return { config: { url: requestUrl }, response: { status: 500, data: { message: 'Internal server error' } } } as AxiosError
}

function networkError(requestUrl = '/jobs') {
  return { config: { url: requestUrl }, response: undefined } as AxiosError
}

function setLocation(pathname: string, search = '') {
  Object.defineProperty(window, 'location', {
    value: { pathname, search, assign: vi.fn() },
    writable: true,
    configurable: true,
  })
}

describe('isProtectedPath', () => {
  it('matches an account page and its sub-paths', () => {
    expect(isProtectedPath('/dashboard')).toBe(true)
    expect(isProtectedPath('/resumes')).toBe(true)
    expect(isProtectedPath('/resumes/9/structure')).toBe(true)
    expect(isProtectedPath('/profile')).toBe(true)
    expect(isProtectedPath('/tailor')).toBe(true)
    expect(isProtectedPath('/tailor?jobId=7')).toBe(false) // query string is not part of pathname
  })

  it('does not match the public pages, or a path that merely starts similarly', () => {
    expect(isProtectedPath('/jobs')).toBe(false)
    expect(isProtectedPath('/')).toBe(false)
    expect(isProtectedPath('/login')).toBe(false)
    expect(isProtectedPath('/register')).toBe(false)
    // Never a prefix-string coincidence -- "/resumesomething" is not "/resumes".
    expect(isProtectedPath('/resumesomething')).toBe(false)
  })
})

describe('isLoginRequiredJobsAction', () => {
  it('matches save, mark/clear applied, and the personal jobs list', () => {
    expect(isLoginRequiredJobsAction('/jobs')).toBe(true)
    expect(isLoginRequiredJobsAction('/job-catalog/freehire:abc/save')).toBe(true)
    expect(isLoginRequiredJobsAction('/job-catalog/123/applied')).toBe(true)
  })

  it('does not match public search/detail/cities', () => {
    expect(isLoginRequiredJobsAction('/job-catalog/search')).toBe(false)
    expect(isLoginRequiredJobsAction('/job-catalog/cities')).toBe(false)
    expect(isLoginRequiredJobsAction('/job-catalog/freehire:abc')).toBe(false)
  })
})

describe('handleResponseError (the 401 side of the axios interceptor)', () => {
  beforeEach(() => {
    saveSession('stale-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('rule 1: browsing /jobs (search), clears the session but stays put -- no navigation at all', async () => {
    setLocation('/jobs')
    await expect(handleResponseError(axios401('/job-catalog/search'))).rejects.toBeTruthy()
    expect(getToken()).toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('rule 1: browsing "/" (which is never rendered itself), clears the session but does not navigate', async () => {
    setLocation('/')
    await expect(handleResponseError(axios401('/job-catalog/search'))).rejects.toBeTruthy()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('rule 1: a job detail lookup on /jobs never redirects either', async () => {
    setLocation('/jobs')
    await expect(handleResponseError(axios401('/job-catalog/freehire:abc'))).rejects.toBeTruthy()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('rule 2: a protected page (/profile) redirects to /login with returnTo to that exact page', async () => {
    setLocation('/profile')
    await expect(handleResponseError(axios401('/users/me'))).rejects.toBeTruthy()
    expect(window.location.assign).toHaveBeenCalledWith('/login?returnTo=%2Fprofile&reason=session_expired')
  })

  it('rule 2: /resumes/9/structure redirects with returnTo to that exact sub-path, query string included', async () => {
    setLocation('/resumes/9/structure', '?tab=summary')
    await expect(handleResponseError(axios401('/resumes/9/structure'))).rejects.toBeTruthy()
    expect(window.location.assign).toHaveBeenCalledWith(
      '/login?returnTo=%2Fresumes%2F9%2Fstructure%3Ftab%3Dsummary&reason=session_expired',
    )
  })

  it('rule 3: saving a job from /jobs redirects to /login?returnTo=/jobs with the session-expired reason', async () => {
    setLocation('/jobs')
    await expect(handleResponseError(axios401('/job-catalog/freehire:abc/save'))).rejects.toBeTruthy()
    expect(window.location.assign).toHaveBeenCalledWith('/login?returnTo=%2Fjobs&reason=session_expired')
  })

  it('rule 3: marking applied from /jobs also redirects with the session-expired reason', async () => {
    setLocation('/jobs')
    await expect(handleResponseError(axios401('/job-catalog/freehire:abc/applied'))).rejects.toBeTruthy()
    expect(window.location.assign).toHaveBeenCalledWith('/login?returnTo=%2Fjobs&reason=session_expired')
  })

  it('rule 3: viewing 已保存/已投递 (GET /jobs) from /jobs also redirects', async () => {
    setLocation('/jobs')
    await expect(handleResponseError(axios401('/jobs'))).rejects.toBeTruthy()
    expect(window.location.assign).toHaveBeenCalledWith('/login?returnTo=%2Fjobs&reason=session_expired')
  })

  it('rule 4: /auth/login\'s own 401 (wrong credentials) never clears the session or fires the event', async () => {
    setLocation('/login')
    const listener = vi.fn()
    window.addEventListener(SESSION_EXPIRED_EVENT, listener)

    await expect(handleResponseError(axios401('/auth/login'))).rejects.toBeTruthy()

    expect(getToken()).toBe('stale-token')
    expect(listener).not.toHaveBeenCalled()
    expect(window.location.assign).not.toHaveBeenCalled()
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener)
  })

  it('rule 4: /auth/register\'s own 401 is likewise left alone', async () => {
    setLocation('/register')
    await expect(handleResponseError(axios401('/auth/register'))).rejects.toBeTruthy()
    expect(getToken()).toBe('stale-token')
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('fires the session-expired event whenever it does act (so AuthContext flips live, even with no navigation)', async () => {
    setLocation('/jobs')
    const listener = vi.fn()
    window.addEventListener(SESSION_EXPIRED_EVENT, listener)
    await expect(handleResponseError(axios401('/job-catalog/search'))).rejects.toBeTruthy()
    expect(listener).toHaveBeenCalledTimes(1)
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener)
  })

  it('a non-401 error never clears the session, never fires the event, never navigates', async () => {
    setLocation('/profile')
    const listener = vi.fn()
    window.addEventListener(SESSION_EXPIRED_EVENT, listener)

    await expect(handleResponseError(axios500())).rejects.toBeTruthy()

    expect(getToken()).toBe('stale-token')
    expect(listener).not.toHaveBeenCalled()
    expect(window.location.assign).not.toHaveBeenCalled()
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener)
  })

  it('a network error (no response at all) never clears the session or navigates', async () => {
    setLocation('/profile')
    await expect(handleResponseError(networkError())).rejects.toBeTruthy()
    expect(getToken()).toBe('stale-token')
    expect(window.location.assign).not.toHaveBeenCalled()
  })
})
