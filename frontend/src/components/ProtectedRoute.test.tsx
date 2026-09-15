import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider } from '../auth/AuthContext'
import { clearSessionExpiredMark, markSessionExpired, saveSession } from '../auth/session'
import { ProtectedRoute } from './ProtectedRoute'

function LoginProbe() {
  const location = useLocation()
  return <p>{`login:${location.pathname}${location.search}`}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route element={<ProtectedRoute />}>
            <Route path="/resumes" element={<p>resumes page</p>} />
          </Route>
          <Route path="/login" element={<LoginProbe />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

function expiredJwt() {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '')
  return `${encode({ alg: 'HS256' })}.${encode({ exp: 1 })}.signature`
}

describe('ProtectedRoute', () => {
  afterEach(() => {
    cleanup()
    localStorage.clear()
    clearSessionExpiredMark()
  })

  it('sends a first-time visitor to login with the page to return to', () => {
    renderAt('/resumes?tab=files')
    expect(screen.getByText('login:/login?returnTo=%2Fresumes%3Ftab%3Dfiles')).toBeInTheDocument()
  })

  it('says the session expired when the token stopped working', () => {
    markSessionExpired()
    renderAt('/resumes')
    expect(screen.getByText('login:/login?returnTo=%2Fresumes&reason=session_expired')).toBeInTheDocument()
  })

  it('never shows the page with a cached name when the stored token is already expired', () => {
    saveSession(expiredJwt(), { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderAt('/resumes')
    expect(screen.queryByText('resumes page')).not.toBeInTheDocument()
    expect(screen.getByText('login:/login?returnTo=%2Fresumes&reason=session_expired')).toBeInTheDocument()
  })
})
