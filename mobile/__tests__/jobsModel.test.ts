import {
  CATALOG_PAGE_SIZE,
  descriptionOf,
  flattenCatalogPages,
  hasActiveFilters,
  htmlToPlainText,
  nextCatalogPage,
  parsePersonalJobId,
  parseSegment,
  safeHttpUrl,
  salaryParts,
  splitMyJobs,
  toSearchQuery,
} from '../src/jobs/model'
import { catalogSummary, personalJob, searchPage } from '../test-utils/fixtures'

describe('search parameters', () => {
  it('trims keywords and leaves blank filters out', () => {
    expect(toSearchQuery({ q: '  data analyst ', city: null }, 1)).toEqual({
      q: 'data analyst',
      city: undefined,
      cityCountry: undefined,
      page: 1,
      size: CATALOG_PAGE_SIZE,
    })
    expect(toSearchQuery({ q: '   ', city: null }, 3).q).toBeUndefined()
  })

  it('sends a city only as the picked suggestion, with its country as metadata', () => {
    expect(toSearchQuery({ q: '', city: { value: 'Toronto', countryCode: 'ca' } }, 2)).toMatchObject({
      city: 'Toronto',
      cityCountry: 'ca',
      page: 2,
    })
    expect(hasActiveFilters({ q: '', city: { value: 'Toronto', countryCode: null } })).toBe(true)
    expect(hasActiveFilters({ q: ' ', city: null })).toBe(false)
  })
})

describe('pagination', () => {
  it('asks for the next page only while the backend says there is more', () => {
    expect(nextCatalogPage(searchPage([], { page: 1, hasMore: true }))).toBe(2)
    expect(nextCatalogPage(searchPage([], { page: 4, hasMore: false }))).toBeUndefined()
  })

  it('flattens pages in order and shows a posting that moved between pages once', () => {
    const a = catalogSummary('freehire:a')
    const b = catalogSummary('freehire:b')
    const c = catalogSummary('freehire:c')
    const rows = flattenCatalogPages([searchPage([a, b]), searchPage([b, c], { page: 2 })])
    expect(rows.map((row) => row.id)).toEqual(['freehire:a', 'freehire:b', 'freehire:c'])
  })
})

describe('/jobs classification', () => {
  it('puts applied records in 已投递 (newest first) and every other record in 已收藏', () => {
    const { saved, applied } = splitMyJobs([
      personalJob(1, { applied: true, appliedAt: '2026-09-02T00:00:00' }),
      personalJob(2, { updatedAt: '2026-09-03T00:00:00' }),
      personalJob(3, { applied: true, appliedAt: '2026-09-09T00:00:00' }),
      personalJob(4, { updatedAt: '2026-09-05T00:00:00' }),
    ])
    expect(saved.map((job) => job.id)).toEqual([4, 2])
    expect(applied.map((job) => job.id)).toEqual([3, 1])
  })
})

describe('id spaces', () => {
  it('accepts only positive numeric personal ids, never a catalog reference', () => {
    expect(parsePersonalJobId('42')).toBe(42)
    expect(parsePersonalJobId(['7'])).toBe(7)
    expect(parsePersonalJobId('freehire:senior-dev')).toBeNull()
    expect(parsePersonalJobId('0')).toBeNull()
    expect(parsePersonalJobId(undefined)).toBeNull()
  })

  it('reads the segment from the route and falls back to the catalog', () => {
    expect(parseSegment('saved')).toBe('saved')
    expect(parseSegment('applied')).toBe('applied')
    expect(parseSegment('anything')).toBe('catalog')
  })
})

describe('display helpers', () => {
  it('shows salary only when the posting provides one', () => {
    expect(salaryParts(80000, 95000, 'CAD')).toEqual({ kind: 'range', min: '80,000', max: '95,000', currency: 'CAD' })
    expect(salaryParts(60000, null, 'USD')).toEqual({ kind: 'from', min: '60,000', currency: 'USD' })
    expect(salaryParts(null, 120000, null)).toEqual({ kind: 'upTo', max: '120,000', currency: '' })
    expect(salaryParts(null, null, 'CAD')).toBeNull()
    expect(salaryParts(0, 0, 'CAD')).toBeNull()
  })

  it('only lets http(s) links leave the app', () => {
    expect(safeHttpUrl(' https://jobs.example.test/x ')).toBe('https://jobs.example.test/x')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl(null)).toBeNull()
  })

  it('turns HTML-only descriptions into plain text instead of rendering markup', () => {
    expect(htmlToPlainText('<p>Build &amp; ship</p><ul><li>One</li><li>Two</li></ul>')).toBe('Build & ship\n\n• One\n• Two')
    expect(descriptionOf({ descriptionText: '  Plain  ', descriptionHtml: '<p>Ignored</p>' })).toBe('Plain')
    expect(descriptionOf({ descriptionText: null, descriptionHtml: null })).toBe('')
  })
})
