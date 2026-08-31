import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { confirmTailorProposal } from './jobs'

vi.mock('./client', () => ({
  api: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('confirmTailorProposal HTTP body', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockResolvedValue({ data: {} } as never)
  })

  it('omits unselected skill reviews from the confirm payload', async () => {
    await confirmTailorProposal(7, 42, undefined, undefined, {
      selectedEntryKeys: ['PROJECT:prime-deals'],
      editedEntries: [],
      selectedSkillReviewIds: [],
      editedSkillReviews: [],
    })
    expect(api.post).toHaveBeenCalledWith(
      '/jobs/7/tailor-proposals/42/confirm',
      { selectedEntryKeys: ['PROJECT:prime-deals'] },
    )
  })

  it('includes selected skill reviews when the user keeps them', async () => {
    await confirmTailorProposal(7, 42, undefined, undefined, {
      selectedSkillReviewIds: ['skills:technical'],
    })
    expect(api.post).toHaveBeenCalledWith(
      '/jobs/7/tailor-proposals/42/confirm',
      { selectedSkillReviewIds: ['skills:technical'] },
    )
  })
})
