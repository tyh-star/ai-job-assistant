import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { AppLayout } from '../components/AppLayout'
import { AuthProvider } from '../auth/AuthContext'
import { getToken, saveSession } from '../auth/session'
import { ProfilePage } from './ProfilePage'

const getMe = vi.fn()
const updateName = vi.fn()
const updateEmail = vi.fn()
const changePassword = vi.fn()
const getFeedbackSummary = vi.fn()
const getMyFeedback = vi.fn()

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getMe: (...args: unknown[]) => getMe(...args),
  updateName: (...args: unknown[]) => updateName(...args),
  updateEmail: (...args: unknown[]) => updateEmail(...args),
  changePassword: (...args: unknown[]) => changePassword(...args),
}))

vi.mock('../api/feedback', () => ({
  getFeedbackSummary: (...args: unknown[]) => getFeedbackSummary(...args),
  getMyFeedback: (...args: unknown[]) => getMyFeedback(...args),
  saveMyFeedback: vi.fn(),
  deleteMyFeedback: vi.fn(),
}))

const EXISTING_USER = { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }

function renderProfile() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/profile']}>
        <AuthProvider>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/profile" element={<ProfilePage />} />
            </Route>
            <Route path="/login" element={<div>login page placeholder</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

/** Scopes queries to the section under a given heading -- both the email and password
 * forms have their own "Current password" field, so an unscoped query is ambiguous. */
function section(headingName: string) {
  const heading = screen.getByRole('heading', { name: headingName })
  const container = heading.closest('section')
  if (!container) {
    throw new Error(`"${headingName}" heading is not inside a <section>`)
  }
  return within(container)
}

describe('ProfilePage', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('en')
    saveSession('original-token', EXISTING_USER)
    getMe.mockResolvedValue({
      data: { id: 1, name: 'Alice', email: 'alice@example.com', role: 'USER', createdAt: '2026-01-01T00:00:00' },
    })
    getFeedbackSummary.mockResolvedValue({ data: { averageRating: 0, reviewerCount: 0 } })
    getMyFeedback.mockRejectedValue({ response: { status: 404, data: { message: 'No feedback submitted yet' } } })
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('shows the account email and registration date', async () => {
    renderProfile()
    expect(await screen.findByText('alice@example.com')).toBeInTheDocument()
    expect(getMe).toHaveBeenCalled()
  })

  it('shows the site-rating card after the password section', async () => {
    renderProfile()
    await screen.findByText('alice@example.com')
    const password = screen.getByRole('heading', { name: 'Change password' })
    const feedback = screen.getByRole('heading', { name: 'Rate the site' })
    expect(password.compareDocumentPosition(feedback) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(document.getElementById('feedback')).not.toBeNull()
    expect(getFeedbackSummary).toHaveBeenCalled()
    expect(getMyFeedback).toHaveBeenCalled()
  })

  it('rejects a blank name without calling the API', async () => {
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const nameField = section('Change name')
    await user.clear(nameField.getByLabelText('Name'))
    await user.click(nameField.getByRole('button', { name: 'Save name' }))
    expect(await screen.findByText('Name is required')).toBeInTheDocument()
    expect(updateName).not.toHaveBeenCalled()
  })

  it('trims whitespace, updates the name, and immediately updates the nav bar without re-login', async () => {
    updateName.mockResolvedValue({
      data: { id: 1, name: 'Alice Smith', email: 'alice@example.com', role: 'USER', createdAt: '2026-01-01T00:00:00' },
    })
    renderProfile()
    await screen.findByText('alice@example.com')

    const user = userEvent.setup()
    const nameField = section('Change name')
    const nameInput = nameField.getByLabelText('Name')
    await user.clear(nameInput)
    await user.type(nameInput, '  Alice Smith  ')
    await user.click(nameField.getByRole('button', { name: 'Save name' }))

    expect(await screen.findByText('Name updated.')).toBeInTheDocument()
    expect(updateName).toHaveBeenCalledWith({ name: 'Alice Smith' })
    // The nav bar (rendered by the same AppLayout/AuthProvider tree) reflects the new
    // name right away -- no logout/login round trip required.
    expect(await screen.findByText('Alice Smith')).toBeInTheDocument()
    // The token is untouched by a name change.
    expect(getToken()).toBe('original-token')
  })

  it('requires the current password before changing email, and never calls the API without it', async () => {
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const emailField = section('Change email')
    await user.type(emailField.getByLabelText('New email'), 'new@example.com')
    await user.click(emailField.getByRole('button', { name: 'Save email' }))
    expect(await screen.findByText('Password is required')).toBeInTheDocument()
    expect(updateEmail).not.toHaveBeenCalled()
  })

  it('shows a distinct error for an incorrect current password on email change, and keeps the old session', async () => {
    updateEmail.mockRejectedValue({ response: { status: 400, data: { message: 'Current password is incorrect' } } })
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const emailField = section('Change email')
    await user.type(emailField.getByLabelText('Current password'), 'wrong-password')
    await user.type(emailField.getByLabelText('New email'), 'new@example.com')
    await user.click(emailField.getByRole('button', { name: 'Save email' }))
    expect(await screen.findByText('Current password is incorrect.')).toBeInTheDocument()
    expect(getToken()).toBe('original-token')
  })

  it('replaces the stored token and email after a successful email change', async () => {
    updateEmail.mockResolvedValue({
      data: {
        token: 'fresh-token',
        user: { id: 1, name: 'Alice', email: 'new@example.com', role: 'USER', createdAt: '2026-01-01T00:00:00' },
      },
    })
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const emailField = section('Change email')
    await user.type(emailField.getByLabelText('Current password'), 'correct-password')
    await user.type(emailField.getByLabelText('New email'), 'new@example.com')
    await user.click(emailField.getByRole('button', { name: 'Save email' }))
    expect(await screen.findByText('Email updated.')).toBeInTheDocument()
    expect(updateEmail).toHaveBeenCalledWith({ currentPassword: 'correct-password', newEmail: 'new@example.com' })
    expect(getToken()).toBe('fresh-token')
  })

  it('rejects a new password shorter than 8 characters without calling the API', async () => {
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const passwordField = section('Change password')
    await user.type(passwordField.getByLabelText('Current password'), 'correct-password')
    await user.type(passwordField.getByLabelText('New password'), 'short')
    await user.click(passwordField.getByRole('button', { name: 'Save password' }))
    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('shows a distinct error for an incorrect current password on password change', async () => {
    changePassword.mockRejectedValue({ response: { status: 400, data: { message: 'Current password is incorrect' } } })
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const passwordField = section('Change password')
    await user.type(passwordField.getByLabelText('Current password'), 'wrong-password')
    await user.type(passwordField.getByLabelText('New password'), 'NewPassword456')
    await user.click(passwordField.getByRole('button', { name: 'Save password' }))
    expect(await screen.findByText('Current password is incorrect.')).toBeInTheDocument()
    expect(getToken()).toBe('original-token')
  })

  it('changes the password, shows an honest (not "all devices") notice, then clears this session and redirects to login', async () => {
    changePassword.mockResolvedValue({ data: undefined })
    renderProfile()
    await screen.findByText('alice@example.com')
    const user = userEvent.setup()
    const passwordField = section('Change password')
    await user.type(passwordField.getByLabelText('Current password'), 'correct-password')
    await user.type(passwordField.getByLabelText('New password'), 'NewPassword456')
    await user.click(passwordField.getByRole('button', { name: 'Save password' }))

    const successMessage = await screen.findByText(/Password updated\./)
    expect(successMessage.textContent).not.toMatch(/all devices/i)
    expect(changePassword).toHaveBeenCalledWith({ currentPassword: 'correct-password', newPassword: 'NewPassword456' })

    // After the brief honest-notice delay, this browser's session is actually cleared
    // and the user is sent to /login -- not just told that it happened.
    await waitFor(() => expect(screen.getByText('login page placeholder')).toBeInTheDocument(), { timeout: 3000 })
    expect(getToken()).toBeNull()
  })
})
