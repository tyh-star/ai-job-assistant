import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession, clearSession } from '../auth/session'
import { AppLayout, COMPACT_NAV_QUERY } from './AppLayout'

function renderLayout(path = '/') {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<div>page content</div>} />
              <Route path="/jobs" element={<div>jobs</div>} />
              <Route path="/resumes/builder/:id" element={<div>editor</div>} />
              <Route path="/dashboard" element={<div>dashboard</div>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

function stubMatchMedia(compact: boolean) {
  window.matchMedia = (query: string) =>
    ({
      matches: compact && query.includes('1279'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

describe('AppLayout', () => {
  beforeEach(async () => {
    localStorage.clear()
    stubMatchMedia(false)
    await i18n.changeLanguage('zh-CN')
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
    stubMatchMedia(false)
  })

  it('signed in, shows Dashboard instead of Home, with icons and no lock glyphs', () => {
    saveSession('test-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderLayout()
    expect(screen.queryByRole('link', { name: '首页' })).not.toBeInTheDocument()
    for (const name of [/^控制台$/, /^职位$/, /^简历改写$/]) {
      const link = screen.getByRole('link', { name })
      expect(link.querySelector('svg')).not.toBeNull()
    }
    expect(screen.getByRole('button', { name: '简历工具' })).toBeInTheDocument()
    expect(screen.queryByText('🔒')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'AI Job Assistant' })).toHaveAttribute('href', '/dashboard')
    clearSession()
  })

  it('signed out, shows Home, Jobs, Resume tools, and Resume Rewrite without hiding them', async () => {
    renderLayout()
    const user = userEvent.setup()
    expect(screen.getByRole('link', { name: '首页' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: '职位' })).toHaveAttribute('href', '/jobs')
    expect(screen.queryByRole('link', { name: /^控制台$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '简历改写' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Ftailor&reason=login_required',
    )
    await user.click(screen.getByRole('button', { name: '简历工具' }))
    const menu = screen.getByRole('menu', { name: '简历工具' })
    expect(within(menu).getByRole('menuitem', { name: '我的简历' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes&reason=login_required',
    )
    expect(within(menu).getByRole('menuitem', { name: '创建简历' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required',
    )
    expect(within(menu).getByRole('menuitem', { name: '简历质量评分' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes&reason=login_required',
    )
    expect(screen.queryByText('🔒')).not.toBeInTheDocument()
  })

  it('closes the resume-tools menu with Escape and restores focus', async () => {
    renderLayout()
    const user = userEvent.setup()
    const toggle = screen.getByRole('button', { name: '简历工具' })
    await user.click(toggle)
    expect(screen.getByRole('menu', { name: '简历工具' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu', { name: '简历工具' })).not.toBeInTheDocument()
    expect(toggle).toHaveFocus()
  })

  it('keeps logo, nav, and auth on one nowrap desktop row in Chinese and English', async () => {
    renderLayout()
    expect(document.querySelector('.site-header-inner')).not.toBeNull()
    expect(document.querySelector('.site-header-nav')?.className).not.toMatch(/flex-wrap/)
    expect(document.querySelector('.site-header-actions')?.className).not.toMatch(/flex-wrap/)
    expect(screen.queryByRole('button', { name: '打开菜单' })).not.toBeInTheDocument()

    await i18n.changeLanguage('en')
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resume tools' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Resume Rewrite' })).toBeInTheDocument()
    expect(document.querySelector('.site-header-nav')?.className).not.toMatch(/flex-wrap/)
    expect(document.querySelector('.site-header-actions')?.className).not.toMatch(/flex-wrap/)

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Resume tools' }))
    expect(screen.getByRole('menuitem', { name: 'Build resume' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required',
    )
  })

  it('shows login AND register links, never a user name/logout, when signed out', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '注册' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退出登录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /个人中心/ })).not.toBeInTheDocument()
    const logo = screen.getByRole('link', { name: 'AI Job Assistant' })
    expect(logo).toHaveAttribute('href', '/')
  })

  it('shows the user name and a logout button when signed in, and hides login/register', () => {
    saveSession('test-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderLayout()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '登录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '注册' })).not.toBeInTheDocument()
    clearSession()
  })

  it('opens a compact menu with every entry, closes on Escape, and restores focus', async () => {
    stubMatchMedia(true)
    renderLayout()
    const user = userEvent.setup()
    expect(screen.queryByRole('link', { name: '首页' })).not.toBeInTheDocument()
    const open = screen.getByRole('button', { name: '打开菜单' })
    await user.click(open)

    const menu = screen.getByRole('dialog', { name: '网站菜单' })
    expect(within(menu).getByRole('link', { name: '首页' })).toHaveAttribute('href', '/')
    expect(within(menu).getByRole('link', { name: '职位' })).toHaveAttribute('href', '/jobs')
    expect(within(menu).queryByRole('link', { name: /^控制台$/ })).not.toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '我的简历' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '创建简历' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required',
    )
    expect(within(menu).getByRole('link', { name: '简历质量评分' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '简历改写' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '注册' })).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '网站菜单' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '打开菜单' })).toHaveFocus()
  })

  it('signed-in compact menu shows Dashboard instead of Home', async () => {
    stubMatchMedia(true)
    saveSession('test-token', { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' })
    renderLayout()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '打开菜单' }))
    const menu = screen.getByRole('dialog', { name: '网站菜单' })
    expect(within(menu).getByRole('link', { name: /^控制台$/ })).toHaveAttribute('href', '/dashboard')
    expect(within(menu).queryByRole('link', { name: '首页' })).not.toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '创建简历' })).toHaveAttribute('href', '/resumes/builder')
    expect(within(menu).getByRole('button', { name: '退出登录' })).toBeInTheDocument()
    clearSession()
  })

  it('exposes COMPACT_NAV_QUERY at the 1280px hamburger breakpoint', () => {
    expect(COMPACT_NAV_QUERY).toBe('(max-width: 1279px)')
  })

  it('uses the wider workspace shell only on the resume editor', () => {
    renderLayout('/jobs')
    expect(document.querySelector('main')?.className).toBe('site-shell')
    cleanup()
    renderLayout('/resumes/builder/12')
    expect(document.querySelector('main')?.className).toBe('site-workspace')
  })

  it('gives /dashboard its own console navigation without changing other pages', async () => {
    const user = userEvent.setup()
    saveSession('t', { userId: 1, name: 'Dana Lee', email: 'd@example.com', role: 'USER' })
    renderLayout('/dashboard')

    // text nav, and the current section is marked with a hairline, not a pill
    const current = screen.getByRole('link', { name: '控制台' })
    expect(current.className).toContain('is-current')
    expect(document.querySelector('.wbNav')).not.toBeNull()
    expect(document.querySelector('.site-header')).toBeNull()
    expect(document.querySelector('main')?.className).toBe('site-plain site-main--dashboard')

    // 个人中心 and 退出登录 live in the account dropdown, not in the bar
    expect(screen.queryByRole('link', { name: '个人中心' })).toBeNull()
    expect(screen.queryByRole('button', { name: '退出登录' })).toBeNull()
    await user.click(screen.getByRole('button', { name: /Dana Lee/ }))
    const menu = screen.getByRole('menu', { name: '个人中心' })
    expect(within(menu).getByRole('menuitem', { name: '个人中心' })).toHaveAttribute('href', '/profile')
    expect(within(menu).getByRole('menuitem', { name: '退出登录' })).toBeInTheDocument()
  })

  it('keeps the shared header on every other route', () => {
    saveSession('t', { userId: 1, name: 'Dana Lee', email: 'd@example.com', role: 'USER' })
    renderLayout('/jobs')
    expect(document.querySelector('.site-header')).not.toBeNull()
    expect(document.querySelector('.wbNav')).toBeNull()
  })
})
