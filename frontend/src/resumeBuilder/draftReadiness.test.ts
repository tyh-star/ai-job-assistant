import { describe, expect, it } from 'vitest'
import { blankDraftContent } from './constants'
import {
  computeDraftReadiness,
  overlayLivePreview,
  unifyDraftTarget,
  withSyncedTargetRole,
} from './draftReadiness'

const labels = {
  name: 'your name',
  contact: 'a way to contact you (email or phone)',
  target: 'a target job title',
}

describe('computeDraftReadiness', () => {
  it('lists all three missing facts on a blank draft', () => {
    const readiness = computeDraftReadiness(blankDraftContent(), '', labels)
    expect(readiness.readyToGenerate).toBe(false)
    expect(readiness.missingKeyFacts).toEqual([labels.name, labels.contact, labels.target])
  })

  it('drops the name fact once a full name is present', () => {
    const content = blankDraftContent()
    content.basicInfo.fullName = 'Alex Morgan'
    const readiness = computeDraftReadiness(content, '', labels)
    expect(readiness.hasName).toBe(true)
    expect(readiness.missingKeyFacts).not.toContain(labels.name)
    expect(readiness.readyToGenerate).toBe(false)
  })

  it('treats a trimmed phone as enough contact', () => {
    const content = blankDraftContent()
    content.basicInfo.phone = '  (416) 555-0142  '
    expect(computeDraftReadiness(content, '', labels).missingKeyFacts).not.toContain(labels.contact)
  })

  it('treats a trimmed email as enough contact', () => {
    const content = blankDraftContent()
    content.basicInfo.email = '  alex.morgan@example.com  '
    expect(computeDraftReadiness(content, '', labels).missingKeyFacts).not.toContain(labels.contact)
  })

  it('does not treat whitespace-only email and phone as contact', () => {
    const content = blankDraftContent()
    content.basicInfo.email = '   '
    content.basicInfo.phone = '\t'
    expect(computeDraftReadiness(content, '', labels).missingKeyFacts).toContain(labels.contact)
  })

  it('accepts the draft-settings target role even when jobTarget.targetTitle is empty', () => {
    const content = blankDraftContent()
    const readiness = computeDraftReadiness(content, 'Junior Software Developer', labels)
    expect(readiness.hasTarget).toBe(true)
    expect(readiness.missingKeyFacts).not.toContain(labels.target)
  })

  it('accepts jobTarget.targetTitle when the draft-settings role is empty', () => {
    const content = blankDraftContent()
    content.jobTarget.targetTitle = 'Junior Software Developer'
    const readiness = computeDraftReadiness(content, '', labels)
    expect(readiness.hasTarget).toBe(true)
    expect(readiness.missingKeyFacts).not.toContain(labels.target)
  })

  it('is ready only when name, contact, and target are all present', () => {
    const content = blankDraftContent()
    content.basicInfo.fullName = 'Alex Morgan'
    content.basicInfo.email = 'alex.morgan@example.com'
    expect(computeDraftReadiness(content, 'Junior Software Developer', labels).readyToGenerate).toBe(true)
    content.basicInfo.email = ''
    content.basicInfo.phone = ''
    expect(computeDraftReadiness(content, 'Junior Software Developer', labels).readyToGenerate).toBe(false)
  })
})

describe('unifyDraftTarget / withSyncedTargetRole', () => {
  it('copies a draft-settings role into jobTarget.targetTitle', () => {
    const unified = unifyDraftTarget(blankDraftContent(), 'Junior Software Developer')
    expect(unified.targetRole).toBe('Junior Software Developer')
    expect(unified.content.jobTarget.targetTitle).toBe('Junior Software Developer')
    expect(withSyncedTargetRole(blankDraftContent(), 'Junior Software Developer').jobTarget.targetTitle).toBe(
      'Junior Software Developer',
    )
  })

  it('copies jobTarget.targetTitle into the draft-settings role when the role is blank', () => {
    const content = blankDraftContent()
    content.jobTarget.targetTitle = 'Junior Software Developer'
    const unified = unifyDraftTarget(content, '  ')
    expect(unified.targetRole).toBe('Junior Software Developer')
    expect(unified.content.jobTarget.targetTitle).toBe('Junior Software Developer')
  })
})

describe('overlayLivePreview', () => {
  it('overlays the live form header onto a stale server preview', () => {
    const content = blankDraftContent()
    content.basicInfo.fullName = 'Alex Morgan'
    content.basicInfo.phone = '(416) 555-0142'
    content.basicInfo.email = 'alex.morgan@example.com'
    const stale = overlayLivePreview(null, blankDraftContent(), '', labels)
    expect(stale.header.fullName).toBe('')
    expect(stale.readyToGenerate).toBe(false)

    const live = overlayLivePreview(stale, content, 'Junior Software Developer', labels)
    expect(live.header.fullName).toBe('Alex Morgan')
    expect(live.header.phone).toBe('(416) 555-0142')
    expect(live.header.email).toBe('alex.morgan@example.com')
    expect(live.header.targetTitle).toBe('Junior Software Developer')
    expect(live.missingKeyFacts).toEqual([])
    expect(live.readyToGenerate).toBe(true)
  })

  it('overlays only filled professional links and never emits empty GitHub rows', () => {
    const content = blankDraftContent()
    content.basicInfo.professionalLinks = [{ type: 'GITHUB', label: 'GitHub', url: '' }]
    expect(overlayLivePreview(null, content, '', labels).header.links).toEqual([])

    content.basicInfo.professionalLinks = [
      { type: 'GITHUB', label: '', url: 'https://github.com/alexmorgan' },
    ]
    expect(overlayLivePreview(null, content, '', labels).header.links).toEqual(['https://github.com/alexmorgan'])
  })

  it('reads legacy linkedin/github/website scalars when professionalLinks is missing', () => {
    const content = blankDraftContent()
    delete (content.basicInfo as { professionalLinks?: unknown }).professionalLinks
    content.basicInfo.linkedin = 'https://linkedin.com/in/alexmorgan'
    content.basicInfo.github = 'https://github.com/alexmorgan'
    content.basicInfo.website = 'https://alex.example'
    expect(overlayLivePreview(null, content, '', labels).header.links).toEqual([
      'https://linkedin.com/in/alexmorgan',
      'https://github.com/alexmorgan',
      'https://alex.example',
    ])
  })
})
