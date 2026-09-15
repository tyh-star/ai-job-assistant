import { describe, expect, it } from 'vitest'
import { catalogJobIdFromParam, catalogJobPath } from './catalogJobPath'
import { parseJobsSearchQuery, serializeJobsSearchQuery } from './searchQuery'

describe('catalogJobPath', () => {
  it('encodes a freehire slug with a colon so the route stays one segment', () => {
    expect(catalogJobPath('freehire:case-manager-3118')).toBe(
      '/jobs/freehire%3Acase-manager-3118',
    )
    expect(catalogJobIdFromParam('freehire%3Acase-manager-3118')).toBe(
      'freehire:case-manager-3118',
    )
    expect(catalogJobIdFromParam('freehire:case-manager-3118')).toBe(
      'freehire:case-manager-3118',
    )
  })
})

describe('jobs search query', () => {
  it('round-trips the documented filter fields and omits empty values', () => {
    const params = serializeJobsSearchQuery({
      q: 'java',
      city: '',
      cityCountry: '',
      country: 'CA',
      workMode: 'REMOTE',
      employmentType: '',
      postedWithin: '',
      sort: '',
      page: 2,
    })
    expect(params.toString()).toBe('q=java&country=CA&workMode=REMOTE&page=2')
    expect(parseJobsSearchQuery(params)).toMatchObject({
      q: 'java',
      country: 'CA',
      workMode: 'REMOTE',
      page: 2,
    })
  })
})
