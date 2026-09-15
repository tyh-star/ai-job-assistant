import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { RegisterPage } from './RegisterPage'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getMe: vi.fn(),
  updateName: vi.fn(),
  updateEmail: vi.fn(),
  changePassword: vi.fn(),
}))

function renderRegister() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/register']}>
        <AuthProvider>
          <RegisterPage />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('RegisterPage password requirements', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
  })

  it('hides password rules on the initial register form', () => {
    renderRegister()
    expect(screen.queryByRole('list', { name: 'Password requirements' })).not.toBeInTheDocument()
  })

  it('focuses the password field and shows red unmet rules after submitting a weak password', async () => {
    const user = userEvent.setup()
    renderRegister()
    await user.type(screen.getByLabelText('Name'), 'Ada')
    await user.type(screen.getByLabelText('Email'), 'ada@example.com')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('This password does not meet the requirements shown.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Password requirements' })).toBeInTheDocument()
    expect(screen.getByText('8–72 characters').closest('li')).toHaveAttribute('data-tone', 'danger')
    expect(screen.getByLabelText('Password')).toHaveFocus()
  })
})
