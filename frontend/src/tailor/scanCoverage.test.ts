import { describe, expect, it, vi } from 'vitest'
import { coverageIncomplete, scanRemainingCoverage, shouldAutoScan } from './scanCoverage'
import type { TailoredResumeResponse } from '../api/types'

function proposal(partial: Partial<TailoredResumeResponse>): TailoredResumeResponse {
  return {
    resumeId: 1,
    jobId: 7,
    mode: 'CONSERVATIVE',
    unchanged: true,
    appliedChangeCount: 0,
    rejectedChangeCount: 0,
    rejectedChanges: [],
    changes: [],
    ...partial,
  }
}

describe('scanCoverage', () => {
  it('auto-scans while coverage is running', async () => {
    const continueTailorProposal = vi.fn()
      .mockResolvedValueOnce({
        data: proposal({
          proposalId: 42,
          coverageStatus: 'RUNNING',
          hasMoreSuggestions: true,
          exploredNodeIds: ['p-16', 'p-17'],
          eligibleNodeIds: ['p-16', 'p-17', 'p-21'],
          changes: [],
        }),
      })
      .mockResolvedValueOnce({
        data: proposal({
          proposalId: 42,
          coverageStatus: 'COMPLETE',
          hasMoreSuggestions: false,
          exploredNodeIds: ['p-16', 'p-17', 'p-21'],
          eligibleNodeIds: ['p-16', 'p-17', 'p-21'],
          changes: [],
        }),
      })
    const result = await scanRemainingCoverage({
      jobId: 7,
      proposalId: 42,
      initial: proposal({
        proposalId: 42,
        coverageStatus: 'RUNNING',
        hasMoreSuggestions: true,
        exploredNodeIds: ['p-16'],
        eligibleNodeIds: ['p-16', 'p-17', 'p-21'],
      }),
      continueTailorProposal,
      shouldCancel: () => false,
    })
    expect(continueTailorProposal).toHaveBeenCalledTimes(2)
    expect(result.result.coverageStatus).toBe('COMPLETE')
    expect(result.failed).toBe(false)
  })

  it('does not treat added=0 running coverage as complete', () => {
    expect(shouldAutoScan(proposal({
      coverageStatus: 'RUNNING',
      hasMoreSuggestions: true,
      addedSuggestionCount: 0,
    }))).toBe(true)
    expect(coverageIncomplete(proposal({
      coverageStatus: 'PARTIAL',
      hasMoreSuggestions: true,
    }))).toBe(true)
  })
})
