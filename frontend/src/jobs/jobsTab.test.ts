import { describe, expect, it } from 'vitest'
import {
  catalogSearchParamsKey,
  jobsTabFromLocation,
  jobsTabReturnPath,
  mergeJobsTabIntoSearch,
  writeJobsTabParam,
} from './jobsTab'

describe('jobs tab query', () => {
  it('reads favorites and applied from a refreshable query param', () => {
    expect(jobsTabFromLocation(new URLSearchParams('tab=favorites'), '')).toBe('SAVED')
    expect(jobsTabFromLocation(new URLSearchParams('tab=applied'), '')).toBe('APPLIED')
    expect(jobsTabFromLocation(new URLSearchParams(), '')).toBe('SEARCH')
  })

  it('still understands the previous hash bookmarks', () => {
    expect(jobsTabFromLocation(new URLSearchParams(), '#applied')).toBe('APPLIED')
    expect(jobsTabFromLocation(new URLSearchParams(), '#saved')).toBe('SAVED')
  })

  it('prefers the query param over a leftover hash', () => {
    expect(jobsTabFromLocation(new URLSearchParams('tab=favorites'), '#applied')).toBe('SAVED')
  })

  it('builds a same-site returnTo for login', () => {
    expect(jobsTabReturnPath('SAVED')).toBe('/jobs?tab=favorites')
    expect(jobsTabReturnPath('APPLIED')).toBe('/jobs?tab=applied')
  })

  it('writes and clears the tab param without dropping other search fields', () => {
    const start = new URLSearchParams('q=nurse&country=CA')
    expect(writeJobsTabParam(start, 'SAVED').toString()).toBe('q=nurse&country=CA&tab=favorites')
    expect(writeJobsTabParam(writeJobsTabParam(start, 'SAVED'), 'SEARCH').toString()).toBe('q=nurse&country=CA')
  })

  it('ignores tab when comparing catalog search URLs and preserves it when writing search params', () => {
    expect(catalogSearchParamsKey(new URLSearchParams('q=nurse&tab=favorites'))).toBe('q=nurse')
    const merged = mergeJobsTabIntoSearch(
      new URLSearchParams('tab=applied'),
      new URLSearchParams('q=nurse'),
    )
    expect(merged.toString()).toBe('q=nurse&tab=applied')
  })
})
