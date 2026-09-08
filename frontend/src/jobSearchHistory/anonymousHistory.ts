import type { JobSearchHistoryItem, RecordJobSearchHistoryRequest } from '../api/types'

export const ANONYMOUS_HISTORY_KEY = 'aijob.anonymousJobSearchHistory.v1'
const MAX_ITEMS = 20

function nextAnonymousId(current: JobSearchHistoryItem[]): number {
  return current.reduce((max, item) => Math.max(max, item.id), 0) + 1
}

function canonicalKey(item: RecordJobSearchHistoryRequest): string {
  const keywords = (item.keywords ?? '').trim().replace(/\s+/g, ' ').toLowerCase()
  const city = (item.city ?? '').trim().toLowerCase()
  const cityCountry = (item.cityCountry ?? '').trim().toUpperCase()
  const country = (item.country ?? '').trim().toUpperCase()
  const workMode = (item.remoteType ?? '').trim().toUpperCase()
  const employmentType = (item.employmentType ?? '').trim().toUpperCase()
  const posted = item.postedWithinDays == null ? '' : String(item.postedWithinDays)
  const sortBy = (item.sort ?? '').trim().toUpperCase()
  return [keywords, city, cityCountry, country, workMode, employmentType, posted, sortBy].join('\n')
}

export function isEmptySearchCriteria(item: RecordJobSearchHistoryRequest): boolean {
  const keywords = (item.keywords ?? '').trim()
  const city = (item.city ?? '').trim()
  const cityCountry = (item.cityCountry ?? '').trim()
  const country = (item.country ?? '').trim()
  const workMode = (item.remoteType ?? '').trim()
  const employmentType = (item.employmentType ?? '').trim()
  const sortBy = (item.sort ?? '').trim().toUpperCase()
  return !keywords && !city && !cityCountry && !country && !workMode && !employmentType
    && item.postedWithinDays == null && (!sortBy || sortBy === 'RELEVANCE')
}

export function loadAnonymousHistory(): JobSearchHistoryItem[] {
  try {
    const raw = localStorage.getItem(ANONYMOUS_HISTORY_KEY)
    if (!raw) {
      return []
    }
    const parsed = JSON.parse(raw) as JobSearchHistoryItem[]
    return Array.isArray(parsed) ? parsed.slice(0, MAX_ITEMS) : []
  } catch {
    return []
  }
}

export function recordAnonymousHistory(request: RecordJobSearchHistoryRequest): JobSearchHistoryItem[] {
  if (isEmptySearchCriteria(request)) {
    return loadAnonymousHistory()
  }
  const key = canonicalKey(request)
  const now = new Date().toISOString()
  const current = loadAnonymousHistory()
  const existing = current.find((item) => item.criteriaKey === key)
  const next: JobSearchHistoryItem[] = existing
    ? [
        {
          ...existing,
          searchCount: existing.searchCount + 1,
          lastSearchedAt: now,
          keywords: request.keywords?.trim() || null,
          city: request.city?.trim() || null,
          cityCountry: request.cityCountry?.trim() || null,
          country: request.country?.trim() || null,
          workMode: request.remoteType?.trim() || null,
          employmentType: request.employmentType?.trim() || null,
          postedWithinDays: request.postedWithinDays ?? null,
          sortBy: request.sort?.trim() || null,
        },
        ...current.filter((item) => item.criteriaKey !== key),
      ]
    : [
        {
          id: nextAnonymousId(current),
          criteriaKey: key,
          keywords: request.keywords?.trim() || null,
          city: request.city?.trim() || null,
          cityCountry: request.cityCountry?.trim() || null,
          country: request.country?.trim() || null,
          workMode: request.remoteType?.trim() || null,
          employmentType: request.employmentType?.trim() || null,
          postedWithinDays: request.postedWithinDays ?? null,
          sortBy: request.sort?.trim() || null,
          searchCount: 1,
          lastSearchedAt: now,
        },
        ...current,
      ]
  const trimmed = next.slice(0, MAX_ITEMS)
  return persistAnonymousHistory(trimmed)
}

export function deleteAnonymousHistory(id: number): JobSearchHistoryItem[] {
  const next = loadAnonymousHistory().filter((item) => item.id !== id)
  return persistAnonymousHistory(next)
}

export function clearAnonymousHistory(): JobSearchHistoryItem[] {
  try {
    localStorage.removeItem(ANONYMOUS_HISTORY_KEY)
  } catch {
    // Quota or access errors must not break the search that already succeeded.
  }
  return []
}

function persistAnonymousHistory(items: JobSearchHistoryItem[]): JobSearchHistoryItem[] {
  try {
    localStorage.setItem(ANONYMOUS_HISTORY_KEY, JSON.stringify(items))
    return items
  } catch {
    return loadAnonymousHistory()
  }
}
