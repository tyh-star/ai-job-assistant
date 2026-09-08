import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ANONYMOUS_HISTORY_KEY,
  clearAnonymousHistory,
  deleteAnonymousHistory,
  loadAnonymousHistory,
  recordAnonymousHistory,
} from './anonymousHistory'

describe('anonymous job search history', () => {
  afterEach(() => {
    localStorage.removeItem(ANONYMOUS_HISTORY_KEY)
  })

  it('ignores empty criteria, dedupes, caps at 20, and can delete', () => {
    expect(recordAnonymousHistory({ keywords: '', sort: 'RELEVANCE' })).toEqual([])
    recordAnonymousHistory({ keywords: 'java' })
    const again = recordAnonymousHistory({ keywords: '  Java  ' })
    expect(again).toHaveLength(1)
    expect(again[0].searchCount).toBe(2)

    for (let i = 0; i < 20; i += 1) {
      recordAnonymousHistory({ keywords: `term-${i}` })
    }
    const capped = loadAnonymousHistory()
    expect(capped).toHaveLength(20)

    const remaining = deleteAnonymousHistory(capped[0].id)
    expect(remaining).toHaveLength(19)
    expect(clearAnonymousHistory()).toEqual([])
    expect(loadAnonymousHistory()).toEqual([])
  })

  it('treats Toronto and toronto as the same search', () => {
    recordAnonymousHistory({ keywords: 'java', city: 'Toronto', cityCountry: 'CA' })
    const again = recordAnonymousHistory({ keywords: 'java', city: 'toronto', cityCountry: 'ca' })
    expect(again).toHaveLength(1)
    expect(again[0].searchCount).toBe(2)
  })

  it('survives corrupted localStorage and quota failures', () => {
    localStorage.setItem(ANONYMOUS_HISTORY_KEY, '{not-json')
    expect(loadAnonymousHistory()).toEqual([])

    recordAnonymousHistory({ keywords: 'java' })
    expect(loadAnonymousHistory()).toHaveLength(1)

    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })
    try {
      expect(() => recordAnonymousHistory({ keywords: 'python' })).not.toThrow()
    } finally {
      spy.mockRestore()
    }
    expect(loadAnonymousHistory()[0].keywords).toBe('java')
  })
})
