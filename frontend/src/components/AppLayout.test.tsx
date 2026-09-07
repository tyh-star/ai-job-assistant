import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession, clearSession } from '../auth/session'
import { AppLayout } from './AppLayout'

function renderLayout() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<div>page content</div>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('AppLayout', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
  })

  it('shows an icon next to each top-nav label (signed in) without dropping the accessible text', () => {
    saveSession('test-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderLayout()
    for (const name of ['工作台', '简历', '职位', '改写']) {
      const link = screen.getByRole('link', { name })
      expect(link).toBeInTheDocument()
      expect(link.querySelector('svg')).not.toBeNull()
    }
    clearSession()
  })

  it('signed out, shows only 职位 in the main nav -- never a link to a page that would just redirect away', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: '职位' })).toBeInTheDocument()
    for (const name of ['工作台', '简历', '改写']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('link', { name: '网站评价' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '评价网站' })).not.toBeInTheDocument()
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument()
  })

  it('shows login AND register links, never a user name/logout, when signed out', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '注册' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退出登录' })).not.toBeInTheDocument()
  })

  it('shows the user name and a logout button when signed in, and hides login/register', () => {
    saveSession('test-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderLayout()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '登录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '注册' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '网站评价' })).not.toBeInTheDocument()
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument()
    clearSession()
  })
})
