import { describe, expect, it } from 'vitest'
import { blankDraftContent, SECTION_IDS } from './constants'
import { applyTemplateLayoutForNewDraft, suggestedSectionOrder } from './templateLayout'

describe('template layout for a new draft', () => {
  it('reorders to the suggested sections but leaves every section shown', () => {
    const next = applyTemplateLayoutForNewDraft(blankDraftContent(), 'GENERIC_ATS')

    expect(next.sectionOrder.slice(0, suggestedSectionOrder('GENERIC_ATS').length)).toEqual(
      suggestedSectionOrder('GENERIC_ATS'),
    )
    expect(suggestedSectionOrder('GENERIC_ATS')).not.toContain(SECTION_IDS.PROJECTS)
    expect(next.sectionEnabled[SECTION_IDS.PROJECTS]).toBe(true)
    expect(next.sectionOrder.every((id) => next.sectionEnabled[id] === true)).toBe(true)
  })

  it('keeps a section the user already turned off hidden', () => {
    const content = blankDraftContent()
    content.sectionEnabled = { ...content.sectionEnabled, [SECTION_IDS.SKILLS]: false }

    const next = applyTemplateLayoutForNewDraft(content, 'TECHNICAL')

    expect(next.sectionOrder[0]).toBe(SECTION_IDS.SKILLS)
    expect(next.sectionEnabled[SECTION_IDS.SKILLS]).toBe(false)
    expect(next.sectionEnabled[SECTION_IDS.PROJECTS]).toBe(true)
  })
})
