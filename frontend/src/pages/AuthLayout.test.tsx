import type { ReactElement } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { LoginPage } from './LoginPage'
import { RegisterPage } from './RegisterPage'
import {
  AUTH_CARD_CLASS,
  AUTH_FOOTER_CLASS,
  AUTH_INPUT_CLASS,
  AUTH_LABEL_CLASS,
  AUTH_SHELL_CLASS,
  AUTH_SUBMIT_CLASS,
  AUTH_TITLE_CLASS,
} from './LoginPage'

function renderAuth(ui: ReactElement, path: string) {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>{ui}</AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('Auth layout', () => {
  afterEach(() => {
    cleanup()
    localStorage.clear()
    void i18n.changeLanguage('zh-CN')
  })

  it('login and register share the enlarged AuthShell classes', () => {
    const login = renderAuth(<LoginPage />, '/login')
    const loginShell = screen.getByTestId('auth-shell')
    const loginCard = screen.getByTestId('auth-card')
    expect(loginShell.className).toBe(AUTH_SHELL_CLASS)
    expect(loginCard.className).toBe(AUTH_CARD_CLASS)
    expect(loginCard.className).toContain('max-w-[min(92vw,540px)]')
    expect(loginCard.className).toContain('px-8')
    expect(loginShell.querySelector('.flex-1')?.className).toContain('items-center')
    expect(screen.getByTestId('language-switch').getAttribute('data-size')).toBe('auth')
    expect(login.container.querySelector('h1')?.className).toBe(AUTH_TITLE_CLASS)
    expect(login.container.querySelector('label')?.className).toBe(AUTH_LABEL_CLASS)
    expect(login.container.querySelector('input')?.className).toBe(AUTH_INPUT_CLASS)
    expect(login.container.querySelector('button[type="submit"]')?.className).toBe(AUTH_SUBMIT_CLASS)
    expect(login.container.querySelector('p.auth-footer')?.className).toBe(AUTH_FOOTER_CLASS)
    expect(AUTH_INPUT_CLASS).toContain('min-h-12')
    expect(AUTH_SUBMIT_CLASS).toContain('min-h-12')
    expect(AUTH_LABEL_CLASS).toContain('text-base')
    cleanup()

    renderAuth(<RegisterPage />, '/register')
    expect(screen.getByTestId('auth-shell').className).toBe(AUTH_SHELL_CLASS)
    expect(screen.getByTestId('auth-card').className).toBe(AUTH_CARD_CLASS)
    expect(screen.getByTestId('language-switch').getAttribute('data-size')).toBe('auth')
    expect(screen.getByRole('heading', { level: 1 }).className).toBe(AUTH_TITLE_CLASS)
    expect(document.querySelectorAll('.auth-input').length).toBeGreaterThanOrEqual(3)
    expect(AUTH_CARD_CLASS).toContain('92vw')
  })

  it('shows the first-visit login prompt, never the expired-session copy', async () => {
    await i18n.changeLanguage('zh-CN')
    renderAuth(<LoginPage />, '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required')
    expect(screen.getByText('登录后即可使用此功能。')).toBeInTheDocument()
    expect(screen.queryByText('登录状态已过期，请重新登录。')).not.toBeInTheDocument()
    cleanup()
    await i18n.changeLanguage('en')
    renderAuth(<LoginPage />, '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required')
    expect(screen.getByText('Sign in to use this feature.')).toBeInTheDocument()
    expect(screen.queryByText('Your session has expired. Please sign in again.')).not.toBeInTheDocument()
  })

  it('shows the expired-session copy only when reason=session_expired', async () => {
    await i18n.changeLanguage('en')
    renderAuth(<LoginPage />, '/login?reason=session_expired')
    expect(screen.getByText('Your session has expired. Please sign in again.')).toBeInTheDocument()
    expect(screen.queryByText('Sign in to use this feature.')).not.toBeInTheDocument()
  })
})
