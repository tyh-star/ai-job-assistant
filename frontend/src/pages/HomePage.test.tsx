import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { HomePage } from './HomePage'

function renderHome() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter>
        <AuthProvider>
          <HomePage />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('HomePage', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
  })

  it('shows a centered hero and three features, with no demo stats', () => {
    renderHome()
    expect(screen.getByRole('heading', { level: 1, name: '找职位、做简历、管投递，一站完成' })).toHaveClass('hero-title')
    expect(screen.getByRole('heading', { name: '查找职位' })).toHaveClass('section-title')
    expect(screen.getByText('公开搜索全球职位；登录后创建简历、检查质量并管理求职进度。')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '查找职位' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '创建与检查简历' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '改写简历并管理投递' })).toBeInTheDocument()
    expect(screen.queryByText('Demo')).not.toBeInTheDocument()
    expect(screen.queryByText('nurse · Toronto')).not.toBeInTheDocument()
    expect(screen.queryByText('data analyst · remote')).not.toBeInTheDocument()
    expect(screen.queryByText('示意预览，不是你的真实数据')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '全球公开职位' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '关键词' })).not.toBeInTheDocument()
  })

  it('sends the two primary CTAs to jobs and the resume builder login path', () => {
    renderHome()
    expect(screen.getByRole('link', { name: '查找职位' })).toHaveAttribute('href', '/jobs')
    for (const link of screen.getAllByRole('link', { name: '创建简历' })) {
      expect(link).toHaveAttribute('href', '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required')
    }
    expect(screen.getByRole('link', { name: '简历质量评分' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fresumes&reason=login_required',
    )
    expect(screen.getByRole('link', { name: '职位改写' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Ftailor&reason=login_required',
    )
    expect(screen.getByRole('link', { name: '管理投递' })).toHaveAttribute(
      'href',
      '/login?returnTo=%2Fjobs%3Ftab%3Dfavorites&reason=login_required',
    )
  })

  it('keeps feature areas in a min-width-zero grid for 390px viewports', () => {
    renderHome()
    const list = screen.getByRole('heading', { name: '查找职位' }).closest('ul')
    expect(list?.className).toMatch(/min-w-0/)
    expect(list?.className).toMatch(/grid/)
  })

  it('switches English copy without missing hero or feature titles', async () => {
    renderHome()
    await i18n.changeLanguage('en')
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'Find jobs, build resumes, and track applications—all in one place',
      }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Find jobs' })).toHaveAttribute('href', '/jobs')
    for (const link of screen.getAllByRole('link', { name: 'Build a resume' })) {
      expect(link).toHaveAttribute('href', '/login?returnTo=%2Fresumes%2Fbuilder&reason=login_required')
    }
    expect(screen.getByRole('heading', { name: 'Find jobs' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Build and check resumes' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Tailor and track applications' })).toBeInTheDocument()
  })
})
