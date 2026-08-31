import { describe, expect, it } from 'vitest'
import { usageCounts, usageReasonKey } from './evidenceUsage'

describe('evidenceUsage', () => {
  it('summarizes used and unused saved evidence', () => {
    expect(usageCounts([
      { evidenceItemId: 41, usageStatus: 'USED_IN_ENTRY', usedInEntryKeys: ['PROJECT:prime-deals'] },
      { evidenceItemId: 50, usageStatus: 'SAVED_NOT_USED', reasonCode: 'NO_ADMITTED_REWRITE' },
      { evidenceItemId: 9, usageStatus: 'USER_REJECTED' },
    ])).toEqual({ saved: 2, used: 1, unused: 1 })
  })

  it('maps unused reasons without exposing topic keys', () => {
    expect(usageReasonKey('NO_ADMITTED_REWRITE')).toBe('tailor.enhanced.reasonNoAdmitted')
    expect(usageReasonKey('NOT_A_SKILL')).toBe('tailor.enhanced.reasonNotASkill')
    expect(usageReasonKey('NO_SKILLS_SECTION')).toBe('tailor.enhanced.reasonNoSkillsSection')
    expect(usageReasonKey('CONFIRMED_FACT_NOT_USED')).toBe('tailor.enhanced.reasonConfirmedNotUsed')
    expect(usageReasonKey('data-store')).toBe('tailor.enhanced.reasonNoAdmitted')
  })
})
