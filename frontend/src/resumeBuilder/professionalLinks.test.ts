import { describe, expect, it } from 'vitest'
import { blankDraftContent } from './constants'
import { migrateAndSyncProfessionalLinks } from './professionalLinks'
import { reorderForTemplate, suggestedLinkTypes } from './templateLayout'

describe('professional link migration', () => {
  it('copies legacy scalars when professionalLinks is missing', () => {
    const info = {
      ...blankDraftContent().basicInfo,
      linkedin: 'https://linkedin.com/in/alexmorgan',
      github: 'https://github.com/alexmorgan',
      website: 'https://alex.example',
    }
    delete (info as { professionalLinks?: unknown }).professionalLinks
    const migrated = migrateAndSyncProfessionalLinks(info)
    expect(migrated.professionalLinks?.map((link) => link.url)).toEqual([
      'https://linkedin.com/in/alexmorgan',
      'https://github.com/alexmorgan',
      'https://alex.example',
    ])
    expect(migrated.github).toBe('https://github.com/alexmorgan')
  })

  it('keeps an explicit empty list empty', () => {
    const migrated = migrateAndSyncProfessionalLinks({
      ...blankDraftContent().basicInfo,
      github: 'https://github.com/should-not-reappear',
      professionalLinks: [],
    })
    expect(migrated.professionalLinks).toEqual([])
    expect(migrated.github).toBe('')
  })
})

describe('template layout', () => {
  it('GENERIC_ATS suggests LinkedIn only', () => {
    expect(suggestedLinkTypes('GENERIC_ATS')).toEqual(['LINKEDIN'])
  })

  it('TECHNICAL suggests LinkedIn, GitHub, and Portfolio', () => {
    expect(suggestedLinkTypes('TECHNICAL')).toEqual(['LINKEDIN', 'GITHUB', 'PORTFOLIO'])
  })

  it('reordering for a new template keeps every existing section id', () => {
    const original = blankDraftContent().sectionOrder
    const next = reorderForTemplate(original, 'STUDENT')
    expect(next).toHaveLength(original.length)
    expect(original.every((id) => next.includes(id))).toBe(true)
    expect(next[0]).toBe('EDUCATION')
  })
})
