import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from './i18n'
import App from './App'
import { AuthProvider } from './auth/AuthContext'
import { saveSession } from './auth/session'

const login = vi.fn()
const getMe = vi.fn()

vi.mock('./api/auth', () => ({
  login: (...args: unknown[]) => login(...args),
  register: vi.fn(),
  getMe: (...args: unknown[]) => getMe(...args),
  updateName: vi.fn(),
  updateEmail: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('./api/jobs', () => ({
  listJobs: vi.fn().mockResolvedValue({ data: [] }),
  createJob: vi.fn(),
  updateJob: vi.fn(),
  deleteJob: vi.fn(),
  markJobApplied: vi.fn(),
  clearJobApplied: vi.fn(),
  bulkDeleteJobs: vi.fn(),
  importGreenhouse: vi.fn(),
  searchJobs: vi.fn(),
  fetchNextSearchPage: vi.fn(),
  saveSearchResult: vi.fn(),
  importSearchResults: vi.fn(),
  analyzeTailorFit: vi.fn(),
  createTailorProposal: vi.fn(),
  refreshJobRequirementProfile: vi.fn(),
}))

vi.mock('./api/jobCatalog', () => ({
  searchJobCatalog: vi.fn().mockResolvedValue({
    data: { page: 1, size: 10, totalCount: 0, totalPages: 0, hasMore: false, results: [] },
  }),
  getJobCatalogJob: vi.fn(),
  saveJobCatalogJob: vi.fn(),
  markJobCatalogApplied: vi.fn(),
  clearJobCatalogApplied: vi.fn(),
  searchCatalogCities: vi.fn(),
}))

vi.mock('./api/resumes', () => ({
  listResumes: vi.fn().mockResolvedValue({ data: [] }),
}))

const EXISTING_USER = { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }

function renderAppAt(path: string) {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('public landing and returnTo routing', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
    getMe.mockResolvedValue({
      data: { id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER', createdAt: '2026-01-01T00:00:00' },
    })
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('root path lands an anonymous visitor on the public jobs page, never on /login', async () => {
    renderAppAt('/')
    expect(await screen.findByText('全球公开职位')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '登录' })).not.toBeInTheDocument()
    // The nav shows only what an anonymous visitor can actually use.
    expect(screen.getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '注册' })).toBeInTheDocument()
  })

  it('root path ALSO lands a signed-in visitor on /jobs -- "/" never varies by auth state', async () => {
    saveSession('test-token', EXISTING_USER)
    renderAppAt('/')
    expect(await screen.findByText('全球公开职位')).toBeInTheDocument()
    // Never auto-landed on the Dashboard -- that is reached through the nav bar now,
    // not by visiting "/".
    expect(screen.queryByText('你好，Alice')).not.toBeInTheDocument()
  })

  it('a signed-in visitor reaches the Dashboard through the nav bar, at /dashboard', async () => {
    saveSession('test-token', EXISTING_USER)
    renderAppAt('/jobs')
    await screen.findByText('全球公开职位')

    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: '工作台' }))

    expect(await screen.findByText('你好，Alice')).toBeInTheDocument()
  })

  it('an anonymous visitor to /dashboard is redirected to login, not shown the Dashboard', async () => {
    renderAppAt('/dashboard')
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
  })

  it('a wrong password shows the real "invalid credentials" message, never the generic session-expired text', async () => {
    renderAppAt('/login')
    await screen.findByRole('heading', { name: '登录' })

    login.mockRejectedValue({ response: { status: 401, data: { message: 'Invalid email or password' } } })
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('邮箱'), 'alice@example.com')
    await user.type(screen.getByLabelText('密码'), 'WrongPassword')
    await user.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByText('邮箱或密码不正确。')).toBeInTheDocument()
    expect(screen.queryByText('登录状态已过期，请重新登录。')).not.toBeInTheDocument()
    // Still on the login form itself -- a wrong password never navigates anywhere.
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument()
  })

  it('visiting a protected page while signed out redirects to /login carrying returnTo, and login returns there', async () => {
    renderAppAt('/profile')

    // Redirected to /login rather than shown the protected page.
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()

    login.mockImplementation(async () => ({
      data: { token: 'fresh-token', userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' },
    }))
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('邮箱'), 'alice@example.com')
    await user.type(screen.getByLabelText('密码'), 'Password123')
    await user.click(screen.getByRole('button', { name: '登录' }))

    // Lands back on the ORIGINAL protected page -- not the Dashboard, not /jobs.
    expect(await screen.findByRole('heading', { name: '个人中心' })).toBeInTheDocument()
  }, 10000)

  it('logging out from a protected page clears the session and lands on /jobs, never /login', async () => {
    saveSession('test-token', EXISTING_USER)
    renderAppAt('/profile')
    await screen.findByRole('heading', { name: '个人中心' })

    // A real browser fully reloads at /jobs on logout (see AppLayout's handleLogout
    // javadoc for why a plain react-router navigate() cannot be trusted to win its
    // race with ProtectedRoute's own redirect-to-login) -- jsdom cannot perform that
    // reload, and its window.location is non-configurable, so the whole object is
    // replaced with a stub to prove the RIGHT destination was requested and the
    // session was actually cleared, rather than asserting on DOM a real reload would
    // blow away anyway.
    const originalLocation = window.location
    const assignSpy = vi.fn()
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, assign: assignSpy },
      writable: true,
      configurable: true,
    })

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '退出登录' }))

    expect(assignSpy).toHaveBeenCalledWith('/jobs')
    expect(assignSpy).not.toHaveBeenCalledWith(expect.stringContaining('/login'))
    expect(localStorage.getItem('aijob.token')).toBeNull()

    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true })
  })

  it('an unknown path still resolves through "/" to the correct destination for an anonymous visitor', async () => {
    renderAppAt('/this-route-does-not-exist')
    expect(await screen.findByText('全球公开职位')).toBeInTheDocument()
  })
})
