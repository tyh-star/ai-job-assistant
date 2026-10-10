import type { TFunction } from 'i18next'
import type { JobCatalogSummary, JobResponse } from '../api/types'
import type { JobBadge } from '../components/JobCard'
import { formatMonthDay } from '../utils/dates'
import { isKnownEmploymentType, isKnownRemoteType, salaryParts } from './model'

export function salaryLabel(t: TFunction, job: Pick<JobCatalogSummary, 'salaryMin' | 'salaryMax' | 'currency'>): string | null {
  const parts = salaryParts(job.salaryMin, job.salaryMax, job.currency)
  if (!parts) {
    return null
  }
  return t(`jobs.salary.${parts.kind}`, parts).trim()
}

/** Work mode and job type, leaving out values the posting doesn't state. */
export function catalogTags(t: TFunction, job: Pick<JobCatalogSummary, 'remoteType' | 'employmentType'>): string[] {
  const tags: string[] = []
  if (isKnownRemoteType(job.remoteType)) {
    tags.push(t(`jobs.remote.${job.remoteType}`))
  }
  if (isKnownEmploymentType(job.employmentType)) {
    tags.push(t(`jobs.employment.${job.employmentType}`))
  }
  return tags
}

export function catalogLocation(job: Pick<JobCatalogSummary, 'locationText' | 'countryCode'>): string | null {
  return job.locationText?.trim() || job.countryCode?.toUpperCase() || null
}

export function catalogBadges(t: TFunction, job: Pick<JobCatalogSummary, 'saved' | 'applied' | 'status'>): JobBadge[] {
  const badges: JobBadge[] = []
  if (job.applied) {
    badges.push({ label: t('jobs.badge.applied'), tone: 'applied' })
  } else if (job.saved) {
    badges.push({ label: t('jobs.badge.saved'), tone: 'saved' })
  }
  if (job.status && job.status !== 'ACTIVE') {
    badges.push({ label: t('jobs.badge.expired'), tone: 'muted' })
  }
  return badges
}

export function personalBadges(t: TFunction, job: Pick<JobResponse, 'applied'>): JobBadge[] {
  return job.applied
    ? [{ label: t('jobs.badge.applied'), tone: 'applied' }]
    : [{ label: t('jobs.badge.saved'), tone: 'saved' }]
}

export function personalDateLabel(t: TFunction, language: string, job: JobResponse): string | null {
  if (job.applied) {
    const date = formatMonthDay(job.appliedAt ?? job.updatedAt, language)
    return date ? t('jobs.card.appliedOn', { date }) : null
  }
  const date = formatMonthDay(job.createdAt, language)
  return date ? t('jobs.card.savedOn', { date }) : null
}

export function sourceLabel(t: TFunction, source: string | null | undefined): string | null {
  const value = source?.trim()
  return value ? t(`jobs.source.${value}`, { defaultValue: value }) : null
}
