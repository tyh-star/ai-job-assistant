import { describe, expect, it } from 'vitest'
import en from '../locales/en.json'
import zh from '../locales/zh-CN.json'

describe('resume builder generic-career copy', () => {
  it('includes the required English and Chinese skills and project hints', () => {
    expect(en.resumeBuilder.hints.skills).toBe(
      'Add job-related skills, tools, certifications, or core competencies, such as customer service, financial analysis, nursing skills, project management, or programming languages.',
    )
    expect(zh.resumeBuilder.hints.skills).toBe(
      '填写与目标职位有关的专业技能、工具、证照或核心能力，例如客户服务、财务分析、护理技能、项目管理、编程语言等。',
    )
    expect(en.resumeBuilder.hints.projects).toBe(
      'A project may be a workplace initiative, course project, research activity, event, portfolio piece, or technical system.',
    )
    expect(zh.resumeBuilder.hints.projects).toBe(
      '项目可以是工作项目、课程项目、研究、活动策划、作品集项目或技术系统。',
    )
  })

  it('does not default skills or project field labels to Java, Python, or AWS', () => {
    const englishFields = JSON.stringify(en.resumeBuilder.fields)
    const chineseFields = JSON.stringify(zh.resumeBuilder.fields)
    expect(englishFields).not.toMatch(/\bJava\b/)
    expect(englishFields).not.toMatch(/\bPython\b/)
    expect(englishFields).not.toMatch(/\bAWS\b/)
    expect(chineseFields).not.toMatch(/\bJava\b/)
    expect(chineseFields).not.toMatch(/\bPython\b/)
    expect(chineseFields).not.toMatch(/\bAWS\b/)
    expect(en.resumeBuilder.fields.githubUrl).not.toMatch(/GitHub/)
    expect(zh.resumeBuilder.fields.githubUrl).not.toMatch(/GitHub/)
  })
})
