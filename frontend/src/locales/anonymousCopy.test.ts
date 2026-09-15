import { describe, expect, it } from 'vitest'
import en from './en.json'
import zh from './zh-CN.json'

function flatten(value: unknown, prefix = ''): string[] {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) => {
      const next = prefix ? `${prefix}.${key}` : key
      return flatten(child, next)
    })
  }
  return prefix ? [prefix] : []
}

describe('anonymous-access copy', () => {
  it('keeps every Chinese key present in English', () => {
    const english = new Set(flatten(en))
    for (const key of flatten(zh)) {
      expect(english.has(key)).toBe(true)
    }
  })

  it('uses distinct first-visit and session-expired login messages', () => {
    expect(zh.auth.loginRequired).toBe('登录后即可使用此功能。')
    expect(en.auth.loginRequired).toBe('Sign in to use this feature.')
    expect(zh.auth.sessionExpired).toBe('登录状态已过期，请重新登录。')
    expect(en.auth.sessionExpired).toBe('Your session has expired. Please sign in again.')
  })

  it('names the homepage hero, features, and shortened English nav labels in both languages', () => {
    expect(zh.home.hero.title).toBe('找职位、做简历、管投递，一站完成')
    expect(en.home.hero.title).toBe('Find jobs, build resumes, and track applications—all in one place')
    expect(zh.home.hero.findJobs).toBe('查找职位')
    expect(en.home.hero.findJobs).toBe('Find jobs')
    expect(zh.home.hero.buildResume).toBe('创建简历')
    expect(en.home.hero.buildResume).toBe('Build a resume')
    expect(zh.home.features.findTitle).toBe('查找职位')
    expect(en.home.features.findTitle).toBe('Find jobs')
    expect(zh.nav.home).toBe('首页')
    expect(en.nav.home).toBe('Home')
    expect(zh.nav.dashboard).toBe('控制台')
    expect(en.nav.dashboard).toBe('Dashboard')
    expect(zh.nav.resumeTools).toBe('简历工具')
    expect(en.nav.resumeTools).toBe('Resume tools')
    expect(zh.nav.resumeBuilder).toBe('创建简历')
    expect(en.nav.resumeBuilder).toBe('Build resume')
    expect(zh.nav.tailor).toBe('职位改写')
    expect(en.nav.tailor).toBe('Tailor')
    expect(zh.home).not.toHaveProperty('demo')
    expect(en.home).not.toHaveProperty('demo')
  })
})
