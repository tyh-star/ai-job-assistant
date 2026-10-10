import { afterEach, describe, expect, it } from 'vitest'
import { consumeJobsSearchScroll, jobsSearchListPath, saveJobsSearchScroll } from './searchScroll'

describe('jobs search scroll storage', () => {
  afterEach(() => {
    sessionStorage.clear()
  })

  it('stores only the query string and y offset, never a token or job body', () => {
    saveJobsSearchScroll('?q=java&page=2', 480)
    expect(sessionStorage.getItem('aijob.jobsSearch.query')).toBe('?q=java&page=2')
    expect(sessionStorage.getItem('aijob.jobsSearch.scroll')).toBe(
      JSON.stringify({ search: '?q=java&page=2', y: 480 }),
    )
    expect(sessionStorage.getItem('aijob.jobsSearch.scroll')).not.toMatch(/jwt|Bearer|description/i)
    expect(jobsSearchListPath()).toBe('/jobs?q=java&page=2')
    expect(consumeJobsSearchScroll('?q=java&page=2')).toBe(480)
    expect(consumeJobsSearchScroll('?q=java&page=2')).toBeNull()
  })
})
