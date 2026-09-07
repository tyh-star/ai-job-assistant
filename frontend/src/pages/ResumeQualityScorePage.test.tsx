import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { ResumeQualityScorePage } from './ResumeQualityScorePage'

const getResumeQualityScore = vi.fn()

vi.mock('../api/resumes', () => ({
  getResumeQualityScore: (...args: unknown[]) => getResumeQualityScore(...args),
}))

function renderAt(resumeId: number | string) {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[`/resumes/${resumeId}/quality-score`]}>
        <Routes>
          <Route path="/resumes/:id/quality-score" element={<ResumeQualityScorePage />} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('ResumeQualityScorePage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN')
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('shows the total score, level, category bars, strengths, and top-3 suggestions', async () => {
    getResumeQualityScore.mockResolvedValue({
      data: {
        scorable: true,
        reasonCode: null,
        score: 78,
        level: 'GOOD',
        rubricVersion: 'v1',
        categories: [
          { code: 'STRUCTURE', score: 18, maxScore: 20 },
          { code: 'EXPERIENCE', score: 22, maxScore: 30 },
          { code: 'ACHIEVEMENTS', score: 12, maxScore: 20 },
          { code: 'SKILLS', score: 13, maxScore: 15 },
          { code: 'READABILITY', score: 13, maxScore: 15 },
        ],
        strengths: ['STRUCTURE_COMPLETE', 'FOCUSED_SKILLS_LIST'],
        suggestions: ['QUANTIFY_ACHIEVEMENTS', 'ADD_MORE_DESCRIPTIVE_DETAIL', 'SHORTEN_LONG_BULLETS', 'USE_CONSISTENT_DATE_FORMAT'],
      },
    })

    renderAt(9)

    expect(await screen.findByText('78 / 100')).toBeInTheDocument()
    expect(screen.getByText('良好')).toBeInTheDocument()

    // Both required disclaimers, verbatim.
    expect(screen.getByText('这是简历结构与内容完整度评分，不是录用概率，也不是职位匹配分数。')).toBeInTheDocument()
    expect(screen.getByText('本评分不消耗AI额度。')).toBeInTheDocument()

    // All 5 category bars, each with its own score/max -- scored via the progress
    // bar's own aria attributes (robust to "18 / 20" being split across sibling text
    // nodes, and to two categories sharing the same displayed text).
    for (const [label, score, maxScore] of [
      ['结构与章节完整性', 18, 20],
      ['经历与项目清晰度', 22, 30],
      ['可量化成果', 12, 20],
      ['技能', 13, 15],
      ['可读性与格式一致性', 13, 15],
    ] as const) {
      const bar = screen.getByRole('progressbar', { name: label })
      expect(bar).toHaveAttribute('aria-valuenow', String(score))
      expect(bar).toHaveAttribute('aria-valuemax', String(maxScore))
    }

    // Strengths shown in full.
    expect(screen.getByText('结构完整，章节齐全')).toBeInTheDocument()
    expect(screen.getByText('技能列表精炼聚焦')).toBeInTheDocument()

    // Suggestions: only the TOP 3 of the 4 returned, in the order the backend sent.
    expect(screen.getByText('为你的成果添加具体数字或百分比')).toBeInTheDocument()
    expect(screen.getByText('为经历或项目条目补充更具体的描述')).toBeInTheDocument()
    expect(screen.getByText('精简过长的条目或段落')).toBeInTheDocument()
    expect(screen.queryByText('全文使用统一的日期格式')).not.toBeInTheDocument()
  })

  it('shows the honest "unable to score" state with the real reason, never a misleading 0', async () => {
    getResumeQualityScore.mockResolvedValue({
      data: {
        scorable: false,
        reasonCode: 'TEXT_NOT_AVAILABLE',
        score: null,
        level: null,
        rubricVersion: 'v1',
        categories: [],
        strengths: [],
        suggestions: [],
      },
    })

    renderAt(9)

    expect(await screen.findByText('暂时无法评分')).toBeInTheDocument()
    expect(screen.getByText(/未能从这份简历中提取到文字内容/)).toBeInTheDocument()
    expect(screen.queryByText('0 / 100')).not.toBeInTheDocument()
  })

  it('shows a fallback message when there are no strengths or suggestions at all', async () => {
    getResumeQualityScore.mockResolvedValue({
      data: {
        scorable: true,
        reasonCode: null,
        score: 40,
        level: 'WEAK',
        rubricVersion: 'v1',
        categories: [
          { code: 'STRUCTURE', score: 10, maxScore: 20 },
          { code: 'EXPERIENCE', score: 10, maxScore: 30 },
          { code: 'ACHIEVEMENTS', score: 5, maxScore: 20 },
          { code: 'SKILLS', score: 10, maxScore: 15 },
          { code: 'READABILITY', score: 5, maxScore: 15 },
        ],
        strengths: [],
        suggestions: [],
      },
    })

    renderAt(9)

    expect(await screen.findByText('暂无突出优点——请参考下方改进建议。')).toBeInTheDocument()
    expect(screen.getByText('暂无具体建议——这份简历在各项规则上表现良好。')).toBeInTheDocument()
  })
})
