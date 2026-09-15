import type { RecordJobSearchHistoryRequest } from '../api/types'

export type JobsSearchQuery = {
  q: string
  city: string
  cityCountry: string
  country: string
  workMode: string
  employmentType: string
  postedWithin: string
  sort: string
  page: number
}

export function emptyJobsSearchQuery(): JobsSearchQuery {
  return {
    q: '',
    city: '',
    cityCountry: '',
    country: '',
    workMode: '',
    employmentType: '',
    postedWithin: '',
    sort: '',
    page: 1,
  }
}

export function parseJobsSearchQuery(params: URLSearchParams): JobsSearchQuery {
  const pageRaw = Number(params.get('page') || '1')
  const page = Number.isFinite(pageRaw) && pageRaw >= 1 ? Math.floor(pageRaw) : 1
  return {
    q: params.get('q') ?? '',
    city: params.get('city') ?? '',
    cityCountry: (params.get('cityCountry') ?? '').toUpperCase(),
    country: (params.get('country') ?? '').toUpperCase(),
    workMode: params.get('workMode') ?? '',
    employmentType: params.get('employmentType') ?? '',
    postedWithin: params.get('postedWithin') ?? '',
    sort: params.get('sort') ?? '',
    page,
  }
}

export function serializeJobsSearchQuery(query: JobsSearchQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) {
    params.set('q', query.q)
  }
  if (query.city) {
    params.set('city', query.city)
    if (query.cityCountry) {
      params.set('cityCountry', query.cityCountry)
    }
  } else if (query.country) {
    params.set('country', query.country)
  }
  if (query.workMode) {
    params.set('workMode', query.workMode)
  }
  if (query.employmentType) {
    params.set('employmentType', query.employmentType)
  }
  if (query.postedWithin) {
    params.set('postedWithin', query.postedWithin)
  }
  if (query.sort) {
    params.set('sort', query.sort)
  }
  if (query.page > 1) {
    params.set('page', String(query.page))
  }
  return params
}

export function jobsSearchQueryFromForm(input: {
  keywords: string
  city: string
  country: string
  remoteType: string
  employmentType: string
  postedWithinDays: string
  sort: string
  page: number
}): JobsSearchQuery {
  return {
    q: input.keywords,
    city: input.city,
    cityCountry: input.city ? input.country : '',
    country: input.city ? '' : input.country,
    workMode: input.remoteType,
    employmentType: input.employmentType,
    postedWithin: input.postedWithinDays,
    sort: input.sort,
    page: input.page,
  }
}

export function jobsSearchQueryToCriteria(query: JobsSearchQuery): RecordJobSearchHistoryRequest {
  return {
    keywords: query.q || undefined,
    city: query.city || undefined,
    cityCountry: query.city ? query.cityCountry || undefined : undefined,
    country: query.city ? undefined : query.country || undefined,
    remoteType: query.workMode || undefined,
    employmentType: query.employmentType || undefined,
    postedWithinDays: query.postedWithin ? Number(query.postedWithin) : undefined,
    sort: query.sort || undefined,
  }
}
