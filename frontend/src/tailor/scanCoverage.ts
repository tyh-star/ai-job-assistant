import type { TailoredResumeResponse } from '../api/types'

const MAX_PAGES = 50

export function coverageIncomplete(result: TailoredResumeResponse | null | undefined): boolean {
  if (!result) {
    return false
  }
  if (result.coverageStatus === 'COMPLETE' || result.coverageStatus === 'FAILED') {
    return result.coverageStatus !== 'COMPLETE' && result.hasMoreSuggestions === true
  }
  if (result.coverageStatus === 'PARTIAL') {
    return true
  }
  return result.coverageStatus === 'RUNNING' && result.hasMoreSuggestions === true
}

export function shouldAutoScan(result: TailoredResumeResponse | null | undefined): boolean {
  return result?.coverageStatus === 'RUNNING' && result.hasMoreSuggestions === true
}

export async function scanRemainingCoverage(options: {
  jobId: number
  proposalId: number
  initial: TailoredResumeResponse
  continueTailorProposal: (jobId: number, proposalId: number) => Promise<{ data: TailoredResumeResponse }>
  shouldCancel: () => boolean
  onPage?: (data: TailoredResumeResponse) => void
}): Promise<{ result: TailoredResumeResponse; cancelled: boolean; failed: boolean }> {
  let current = options.initial
  options.onPage?.(current)
  let pages = 0
  while (shouldAutoScan(current) && pages < MAX_PAGES) {
    if (options.shouldCancel()) {
      return { result: current, cancelled: true, failed: false }
    }
    try {
      const { data } = await options.continueTailorProposal(options.jobId, options.proposalId)
      current = data
      pages += 1
      options.onPage?.(current)
    } catch {
      return { result: { ...current, coverageStatus: 'FAILED' }, cancelled: false, failed: true }
    }
  }
  return { result: current, cancelled: false, failed: false }
}
