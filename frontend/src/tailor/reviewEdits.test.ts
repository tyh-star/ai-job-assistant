import { describe, expect, it } from 'vitest'
import { buildEditedChanges, defaultSelectedNodeIds, hasUnsavedEdit, suggestionMap } from './reviewEdits'
import type { TailorChange } from '../api/types'

const changes: TailorChange[] = [
  {
    nodeId: 'p-16',
    originalText: 'Original 16',
    newText: 'AI 16',
    reason: 'clarity',
    evidenceType: 'VERIFIED',
  },
  {
    nodeId: 'p-17',
    originalText: 'Original 17',
    newText: 'AI 17',
    reason: 'clarity',
    evidenceType: 'VERIFIED',
  },
]

describe('reviewEdits', () => {
  it('selects every suggestion by default', () => {
    expect(defaultSelectedNodeIds(changes)).toEqual(['p-16', 'p-17'])
  })

  it('omits unselected and unchanged suggestions from editedChanges', () => {
    expect(buildEditedChanges(
      ['p-16'],
      { 'p-16': 'User 16', 'p-17': 'User 17' },
      { 'p-16': 'AI 16', 'p-17': 'AI 17' },
    )).toEqual([{ nodeId: 'p-16', newText: 'User 16' }])
  })

  it('maps AI originals and detects unsaved drafts', () => {
    expect(suggestionMap(changes)).toEqual({ 'p-16': 'AI 16', 'p-17': 'AI 17' })
    expect(hasUnsavedEdit('p-16', 'draft', { 'p-16': 'AI 16' })).toBe(true)
    expect(hasUnsavedEdit('p-16', 'AI 16', { 'p-16': 'AI 16' })).toBe(false)
    expect(hasUnsavedEdit(null, 'draft', { 'p-16': 'AI 16' })).toBe(false)
  })
})
