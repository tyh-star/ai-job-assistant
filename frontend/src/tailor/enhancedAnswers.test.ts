import { describe, expect, it } from 'vitest'
import {
  answersForSubmit,
  canSubmitAnswers,
  confirmedUsedNeedsText,
  groupQuestionsByEntry,
  isBareYes,
  specificMaintainAnswer,
  visibleQuestions,
  type EnhancedAnswerDraft,
} from './enhancedAnswers'
import type { V3EnhancedQuestion } from '../api/types'

const questions: V3EnhancedQuestion[] = [
  {
    id: 'eq-PROJECT-prime-deals-maintain',
    canonicalEntryKey: 'PROJECT:prime-deals',
    entryTitle: 'Prime Deals',
    topic: 'maintain',
    promptEn: 'After deploying Prime Deals to AWS, did you continue maintaining this application?',
    promptZh: 'Prime Deals 部署到 AWS 后，你是否继续维护过这个应用？如果有，请说明具体做了什么，例如修复问题、更新依赖、监控或发布更新。',
    skipAllowed: true,
  },
  {
    id: 'eq-PROJECT-prime-deals-testing',
    canonicalEntryKey: 'PROJECT:prime-deals',
    entryTitle: 'Prime Deals',
    topic: 'testing',
    promptEn: 'Did you add tests?',
    promptZh: '你是否为这个项目写过测试？',
    skipAllowed: true,
  },
  {
    id: 'eq-PROJECT-smartpc-builder-metric',
    canonicalEntryKey: 'PROJECT:smartpc-builder',
    entryTitle: 'SmartPC Builder',
    topic: 'metric',
    promptEn: 'Did you measure results?',
    promptZh: '你是否量化过结果？',
    skipAllowed: true,
  },
]

describe('enhancedAnswers', () => {
  it('groups questions by entry and caps at 5', () => {
    const extra: V3EnhancedQuestion[] = Array.from({ length: 6 }, (_, index) => ({
      ...questions[0],
      id: `eq-${index}`,
    }))
    expect(visibleQuestions(extra)).toHaveLength(5)
    const grouped = groupQuestionsByEntry(questions)
    expect(grouped).toHaveLength(2)
    expect(grouped[0].entryTitle).toBe('Prime Deals')
    expect(grouped[0].questions).toHaveLength(2)
  })

  it('rejects bare yes answers for CONFIRMED_USED', () => {
    expect(isBareYes('yes')).toBe(true)
    expect(isBareYes('有')).toBe(true)
    expect(isBareYes('做过')).toBe(true)
    expect(specificMaintainAnswer('yes')).toBe(false)
    expect(specificMaintainAnswer('maintained weekly')).toBe(false)
    expect(specificMaintainAnswer('patched dependencies and monitored AWS weekly')).toBe(true)
    const draft: EnhancedAnswerDraft = { intent: 'used', text: 'yes' }
    expect(confirmedUsedNeedsText(questions[0], draft)).toBe(true)
    expect(canSubmitAnswers(questions, { [questions[0].id]: draft }).ok).toBe(false)
  })

  it('allows not-used and skipped without text', () => {
    const drafts: Record<string, EnhancedAnswerDraft> = {
      [questions[0].id]: { intent: 'not_used', text: '' },
      [questions[1].id]: { intent: 'skipped', text: '' },
    }
    expect(canSubmitAnswers(questions, drafts).ok).toBe(true)
    expect(answersForSubmit(questions, {}).every((item) => item.answerStatus === 'SKIPPED')).toBe(true)
  })

  it('submits the selected allowed entryKey only', () => {
    const skillGap: V3EnhancedQuestion = {
      ...questions[0],
      id: 'eq-skill-nx',
      topic: 'nx-monorepo',
      allowedEntryKeys: ['PROJECT:prime-deals', 'PROJECT:smartpc-builder'],
    }
    const payload = answersForSubmit([skillGap], {
      [skillGap.id]: {
        intent: 'used',
        text: 'Used Nx monorepo with shared libraries in SmartPC Builder',
        selectedEntryKey: 'PROJECT:smartpc-builder',
      },
    })
    expect(payload[0].entryKey).toBe('PROJECT:smartpc-builder')
    const ignored = answersForSubmit([skillGap], {
      [skillGap.id]: {
        intent: 'used',
        text: 'Used Nx monorepo with shared libraries in SmartPC Builder',
        selectedEntryKey: 'PROJECT:forged',
      },
    })
    expect(ignored[0].entryKey).toBe('PROJECT:prime-deals')
  })
})
