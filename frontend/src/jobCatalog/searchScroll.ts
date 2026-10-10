const SCROLL_KEY = 'aijob.jobsSearch.scroll'
const QUERY_KEY = 'aijob.jobsSearch.query'

type ScrollRecord = { search: string; y: number }

function readJson(key: string): unknown {
  try {
    const raw = sessionStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/** Stores list scroll. Never stores JWT or job descriptions. */
export function saveJobsSearchScroll(search: string, y: number) {
  try {
    const record: ScrollRecord = { search, y }
    sessionStorage.setItem(SCROLL_KEY, JSON.stringify(record))
    sessionStorage.setItem(QUERY_KEY, search)
  } catch {
    /* quota / private mode */
  }
}

export function consumeJobsSearchScroll(search: string): number | null {
  const parsed = readJson(SCROLL_KEY)
  if (!parsed || typeof parsed !== 'object') {
    return null
  }
  const record = parsed as Partial<ScrollRecord>
  if (record.search !== search || typeof record.y !== 'number') {
    return null
  }
  try {
    sessionStorage.removeItem(SCROLL_KEY)
  } catch {
    /* ignore */
  }
  return record.y
}

export function jobsSearchListPath(): string {
  try {
    const saved = sessionStorage.getItem(QUERY_KEY)
    if (!saved) {
      return '/jobs'
    }
    return saved.startsWith('?') ? `/jobs${saved}` : saved.startsWith('/jobs') ? saved : `/jobs?${saved}`
  } catch {
    return '/jobs'
  }
}
