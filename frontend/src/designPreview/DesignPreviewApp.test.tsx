import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { saveSession } from '../auth/session'
import DesignPreviewApp from './DesignPreviewApp'
import { SYNTHETIC_FIXTURE_NOTICE } from './fixture'

function renderPreview(path: string) {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <Routes>
            <Route path="/__design/*" element={<DesignPreviewApp />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

function stubMatchMedia(matchesCompact: boolean) {
  window.matchMedia = (query: string) =>
    ({
      matches: matchesCompact && query.includes('1279'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

describe('design preview (DEV prototype)', () => {
  beforeEach(async () => {
    localStorage.clear()
    stubMatchMedia(false)
    await i18n.changeLanguage('zh-CN')
  })

  afterEach(async () => {
    cleanup()
    localStorage.clear()
    stubMatchMedia(false)
    await i18n.changeLanguage('zh-CN')
  })

  it('renders a centered home prototype with refined nav and no sample block', () => {
    renderPreview('/__design/home')
    expect(screen.getByText(/设计原型/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '找职位、做简历、管投递，一站完成' })).toBeInTheDocument()
    expect(screen.getByText('公开搜索全球职位；登录后创建简历、检查质量并管理求职进度。')).toBeInTheDocument()
    expect(screen.getAllByText('查找职位').length).toBeGreaterThan(0)
    expect(screen.getAllByText('创建简历').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: '创建与检查简历' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '改写简历并管理投递' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: '网站菜单' })
    expect(within(nav).getByRole('link', { name: '首页' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: '职位' })).toBeInTheDocument()
    expect(within(nav).getByRole('button', { name: '简历工具' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: '简历改写' })).toHaveAttribute(
      'href',
      loginPathForAnonymousFeature('/tailor'),
    )
    expect(within(nav).queryByText('职位改写')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '注册' })).toBeInTheDocument()
    expect(screen.queryByText('示例 · 职位搜索')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('示例')).not.toBeInTheDocument()
    expect(document.querySelector('.ds-home-sample')).toBeNull()
    expect(screen.queryByText(/不展示虚构用户数量/)).not.toBeInTheDocument()
    expect(screen.queryByText('完成求职设置')).not.toBeInTheDocument()
    expect(screen.queryByText('从这里开始')).not.toBeInTheDocument()
  })

  it('opens resume-tools with keyboard-accessible items and Escape', async () => {
    const user = userEvent.setup()
    renderPreview('/__design/home?chrome=0')
    await user.click(screen.getByRole('button', { name: '简历工具' }))
    expect(screen.getByRole('menuitem', { name: '我的简历' })).toHaveAttribute('href', loginPathForAnonymousFeature('/resumes'))
    expect(screen.getByRole('menuitem', { name: '创建简历' })).toHaveAttribute(
      'href',
      loginPathForAnonymousFeature('/resumes/builder'),
    )
    expect(screen.getByRole('menuitem', { name: '简历质量评分' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('uses a hamburger menu at compact widths without wrapping desktop nav', async () => {
    stubMatchMedia(true)
    const user = userEvent.setup()
    renderPreview('/__design/home?chrome=0')
    expect(screen.queryByRole('navigation', { name: '网站菜单' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '打开菜单' }))
    const menu = screen.getByRole('dialog', { name: '网站菜单' })
    expect(within(menu).getByRole('link', { name: '首页' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '简历改写' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '登录' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: '注册' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '网站菜单' })).not.toBeInTheDocument()
  })

  it('switches product copy and nav labels together in English', async () => {
    const user = userEvent.setup()
    renderPreview('/__design/home?chrome=0')
    await user.click(screen.getByRole('button', { name: 'EN' }))
    expect(await screen.findByRole('heading', { level: 1, name: /Find jobs, build resumes/ })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Site menu' })
    expect(within(nav).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Jobs' })).toBeInTheDocument()
    expect(within(nav).getByRole('button', { name: 'Resume tools' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Resume Rewrite' })).toBeInTheDocument()
    expect(within(nav).queryByText('职位改写')).not.toBeInTheDocument()
    expect(within(nav).queryByText('简历改写')).not.toBeInTheDocument()
  })

  it('shows dashboard, profile, and log out when a session exists', () => {
    saveSession('preview-token', { userId: 1, name: 'Alex', email: 'alex@example.com', role: 'USER' })
    renderPreview('/__design/home?chrome=0')
    const nav = screen.getByRole('navigation', { name: '网站菜单' })
    expect(within(nav).getByRole('link', { name: '控制台' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: '首页' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Alex, 个人中心' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '登录' })).not.toBeInTheDocument()
  })

  it('hides the review toolbar when chrome=0', () => {
    renderPreview('/__design/home?chrome=0')
    expect(screen.queryByText(/设计原型/)).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '找职位、做简历、管投递，一站完成' })).toBeInTheDocument()
  })

  it('shows a signed-in dashboard for new users with compact start actions, quiet zeros, and no duplicate empty cards', () => {
    saveSession('preview-token', { userId: 1, name: 'nb', email: 'nb@example.com', role: 'USER' })
    renderPreview('/__design/dashboard-new?chrome=0')
    const nav = screen.getByRole('navigation', { name: '网站菜单' })
    expect(within(nav).getByRole('link', { name: '控制台' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: '职位' })).toBeInTheDocument()
    expect(within(nav).getByRole('button', { name: '简历工具' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: '简历改写' })).toHaveAttribute('href', '/tailor')
    expect(screen.getByRole('link', { name: 'Alex Morgan, 个人中心' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'nb, 个人中心' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '登录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '注册' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '求职概览' })).toBeInTheDocument()
    expect(screen.getByText('你好，Alex Morgan')).toBeInTheDocument()
    expect(document.querySelector('.ds-page-head .ds-actions')).toBeNull()
    expect(screen.getByRole('heading', { name: '创建或上传简历' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '职位' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: '查找职位' })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: '创建简历' })).toHaveLength(1)
    expect(screen.getByRole('link', { name: '上传简历' })).toHaveAttribute('href', '/resumes')
    expect(screen.queryByRole('heading', { name: '最近搜索' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '最近简历' })).not.toBeInTheDocument()
    expect(screen.queryByText('搜索职位后会显示在这里。')).not.toBeInTheDocument()
    expect(screen.queryByText('还没有简历。')).not.toBeInTheDocument()
    expect(screen.getByText('今日投递')).toBeInTheDocument()
    expect(screen.getByText('当前已收藏')).toBeInTheDocument()
    expect(screen.queryByText('从这里开始')).not.toBeInTheDocument()
    expect(screen.queryByText('完成求职设置')).not.toBeInTheDocument()
    expect(screen.queryByText('查看全部')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '最近投递' })).not.toBeInTheDocument()
  })

  it('shows live stats and recent activity for an active dashboard without invented trend widgets', () => {
    renderPreview('/__design/dashboard-data?chrome=0')
    expect(screen.getByRole('heading', { level: 1, name: '求职概览' })).toBeInTheDocument()
    expect(screen.getByText('你好，Alex Morgan')).toBeInTheDocument()
    expect(screen.getByText('演示数据')).toBeInTheDocument()
    expect(screen.queryByText(SYNTHETIC_FIXTURE_NOTICE)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查找职位' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '创建简历' })).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '最近投递' })).toBeInTheDocument()
    expect(screen.getByText('Operations Coordinator')).toBeInTheDocument()
    expect(screen.getAllByText(/Example Company/).length).toBeGreaterThan(0)
    expect(screen.getByText('operations coordinator toronto')).toBeInTheDocument()
    expect(screen.getByText('Alex_Morgan_Resume.pdf')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: '查看全部' }).length).toBeGreaterThan(0)
    expect(screen.queryByText('近 7 天投递趋势')).not.toBeInTheDocument()
    expect(document.querySelector('.ds-chart')).toBeNull()
  })

  it('keeps English dashboard copy on the same prototype identity', async () => {
    const user = userEvent.setup()
    renderPreview('/__design/dashboard-data?chrome=0')
    await user.click(screen.getByRole('button', { name: 'EN' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Job overview' })).toBeInTheDocument()
    expect(screen.getByText('Hello, Alex Morgan')).toBeInTheDocument()
    expect(screen.getByText('Demo data')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Alex Morgan, Profile' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Find jobs' })).toBeInTheDocument()
    expect(screen.queryByText('演示数据')).not.toBeInTheDocument()
  })

  it('keeps loading and error states distinct from empty zeros', () => {
    renderPreview('/__design/dashboard-loading?chrome=0')
    expect(screen.getByText('正在加载控制台')).toBeInTheDocument()
    expect(screen.queryByText('还没有简历。')).not.toBeInTheDocument()
    expect(screen.queryByText('今日投递')).not.toBeInTheDocument()
    cleanup()
    renderPreview('/__design/dashboard-error?chrome=0')
    expect(screen.getByRole('alert')).toHaveTextContent('无法加载控制台数据。')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
    expect(screen.queryByText('还没有记录')).not.toBeInTheDocument()
    expect(screen.queryByText('今日投递')).not.toBeInTheDocument()
  })

  it('structures job search and detail instead of a raw JD blob', () => {
    renderPreview('/__design/jobs')
    expect(screen.getByLabelText('关键词')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Operations Coordinator' })).toBeInTheDocument()
    cleanup()
    renderPreview('/__design/job-detail')
    expect(screen.getByRole('heading', { name: '职责' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '要求' })).toBeInTheDocument()
    expect(screen.getByText('去官网申请')).toBeInTheDocument()
  })

  it('is not imported by official product pages', () => {
    const root = resolve(process.cwd(), 'src')
    const pages = [
      'pages/HomePage.tsx',
      'pages/DashboardPage.tsx',
      'pages/JobsPage.tsx',
      'pages/JobCatalogDetailPage.tsx',
      'pages/ResumeBuilderListPage.tsx',
      'pages/ResumeBuilderEditorPage.tsx',
    ]
    for (const file of pages) {
      const source = readFileSync(resolve(root, file), 'utf8')
      expect(source, file).not.toMatch(/designPreview|Alex Morgan|Example Company/)
    }
  })
})
