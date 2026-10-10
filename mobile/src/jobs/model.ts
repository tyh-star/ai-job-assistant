/**
 * Pure rules for the jobs module. Two id spaces exist and never mix:
 *   catalog posting   JobCatalogSummary.id   string, e.g. "freehire:<slug>"
 *   personal record   JobResponse.id         number, from GET /jobs
 * A personal record stores only a hash of the catalog slug, so it cannot be turned
 * back into a catalog id: saved/applied records open the personal detail screen.
 */
import type {
  CatalogSearchQuery,
  CitySuggestion,
  JobCatalogSearchResponse,
  JobCatalogSummary,
  JobResponse,
} from '../api/types'

export const CATALOG_PAGE_SIZE = 20
export const CITY_QUERY_MIN_LENGTH = 2

export type SearchFilters = {
  q: string
  city: CitySuggestion | null
}

export const EMPTY_FILTERS: SearchFilters = { q: '', city: null }

export type JobsSegment = 'catalog' | 'saved' | 'applied'

export function parseSegment(raw: string | string[] | undefined): JobsSegment {
  const value = Array.isArray(raw) ? raw[0] : raw
  return value === 'saved' || value === 'applied' ? value : 'catalog'
}

export function hasActiveFilters(filters: SearchFilters): boolean {
  return filters.q.trim().length > 0 || filters.city !== null
}

/** A structured city is only ever sent as the suggestion the user picked. */
export function toSearchQuery(filters: SearchFilters, page: number): CatalogSearchQuery {
  const q = filters.q.trim()
  return {
    q: q || undefined,
    city: filters.city?.value,
    cityCountry: filters.city?.countryCode ?? undefined,
    page,
    size: CATALOG_PAGE_SIZE,
  }
}

export function nextCatalogPage(last: JobCatalogSearchResponse): number | undefined {
  return last.hasMore ? last.page + 1 : undefined
}

/** Keeps first-seen order; a posting that shifted pages between requests shows once. */
export function flattenCatalogPages(pages: JobCatalogSearchResponse[]): JobCatalogSummary[] {
  const seen = new Set<string>()
  const rows: JobCatalogSummary[] = []
  for (const page of pages) {
    for (const row of page.results) {
      if (!seen.has(row.id)) {
        seen.add(row.id)
        rows.push(row)
      }
    }
  }
  return rows
}

export function appliedTimestamp(job: JobResponse): number {
  const parsed = Date.parse(job.appliedAt ?? job.updatedAt)
  return Number.isNaN(parsed) ? 0 : parsed
}

/** Same rule as the website: applied records are "已投递", every other record "已收藏". */
export function splitMyJobs(jobs: JobResponse[]): { saved: JobResponse[]; applied: JobResponse[] } {
  const saved = jobs
    .filter((job) => !job.applied)
    .sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
  const applied = jobs.filter((job) => job.applied).sort((a, b) => appliedTimestamp(b) - appliedTimestamp(a))
  return { saved, applied }
}

export function parsePersonalJobId(raw: string | string[] | undefined): number | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value || !/^\d+$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function groupThousands(value: number): string {
  return Math.round(value)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export type SalaryParts =
  | { kind: 'range'; min: string; max: string; currency: string }
  | { kind: 'from'; min: string; currency: string }
  | { kind: 'upTo'; max: string; currency: string }

/** null when the posting gives no salary at all -- nothing is shown then. */
export function salaryParts(min: number | null, max: number | null, currency: string | null): SalaryParts | null {
  const code = currency?.trim() ?? ''
  const hasMin = typeof min === 'number' && min > 0
  const hasMax = typeof max === 'number' && max > 0
  if (hasMin && hasMax) {
    return min === max
      ? { kind: 'from', min: groupThousands(min), currency: code }
      : { kind: 'range', min: groupThousands(min), max: groupThousands(max), currency: code }
  }
  if (hasMin) {
    return { kind: 'from', min: groupThousands(min), currency: code }
  }
  if (hasMax) {
    return { kind: 'upTo', max: groupThousands(max), currency: code }
  }
  return null
}

export function isKnownRemoteType(value: string | null | undefined): boolean {
  return value === 'REMOTE' || value === 'HYBRID' || value === 'ONSITE'
}

export function isKnownEmploymentType(value: string | null | undefined): boolean {
  return (
    value === 'FULL_TIME' ||
    value === 'PART_TIME' ||
    value === 'CONTRACT' ||
    value === 'INTERNSHIP' ||
    value === 'TEMPORARY' ||
    value === 'OTHER'
  )
}

/** Only http(s) links may leave the app. */
export function safeHttpUrl(url: string | null | undefined): string | null {
  const value = url?.trim()
  return value && /^https?:\/\//i.test(value) ? value : null
}

/** Plain-text fallback for a posting that only ships sanitized HTML. Never rendered as HTML. */
export function htmlToPlainText(html: string | null | undefined): string {
  if (!html) {
    return ''
  }
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|h[1-6]|ul|ol)>/gi, '\n\n')
    .replace(/<li[^>]*>/gi, '• ')
    .replace(/<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function descriptionOf(detail: { descriptionText: string | null; descriptionHtml: string | null }): string {
  return detail.descriptionText?.trim() || htmlToPlainText(detail.descriptionHtml)
}
