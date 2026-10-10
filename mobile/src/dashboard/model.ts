/**
 * Everything the dashboard shows comes from the same shared caches the tabs use:
 *   primary resume   GET /resume-builder/drafts (+ GET /drafts/{id} for readiness)
 *   uploads          GET /resumes
 *   saved / applied  GET /jobs
 *
 * A failed request is never shown as a zero, and nothing is filled in when a list
 * is empty.
 */
import type {
  JobResponse,
  ResumeBuilderDraftContent,
  ResumeBuilderDraftSummary,
  ResumeResponse,
} from '../api/types'
import { splitMyJobs } from '../jobs/model'

export type ResumeSlice = {
  draft: ResumeBuilderDraftSummary | null
  /** null when the draft's detail could not be read -- then no status is shown. */
  draftReady: boolean | null
  uploads: ResumeResponse[]
}

export type DashboardData = {
  resumes: ResumeSlice | null
  jobs: JobResponse[] | null
}

export function byNewest<T extends { updatedAt: string }>(rows: T[]): T[] {
  return rows.slice().sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
}

export function newestDraft(drafts: ResumeBuilderDraftSummary[]): ResumeBuilderDraftSummary | null {
  return byNewest(drafts)[0] ?? null
}

/** Same rule as the website: a name, a way to contact, and a target title. */
export function isDraftReady(content: ResumeBuilderDraftContent, targetRole: string | null): boolean {
  const text = (value: string | null | undefined) => value?.trim() ?? ''
  const hasName = Boolean(text(content.basicInfo?.fullName))
  const hasContact = Boolean(text(content.basicInfo?.email) || text(content.basicInfo?.phone))
  const hasTarget = Boolean(text(targetRole) || text(content.jobTarget?.targetTitle))
  return hasName && hasContact && hasTarget
}

/** Newest draft first; template working copies are never a user's resume. */
export function buildResumeSlice(
  drafts: ResumeBuilderDraftSummary[],
  files: ResumeResponse[],
  draftReady: boolean | null,
): ResumeSlice {
  return {
    draft: newestDraft(drafts),
    draftReady,
    uploads: byNewest(files.filter((resume) => resume.origin !== 'TEMPLATE')),
  }
}

export type PrimaryResume =
  | { kind: 'draft'; draft: ResumeBuilderDraftSummary; ready: boolean | null }
  | { kind: 'upload'; upload: ResumeResponse }

export type ResumeSection =
  | { kind: 'failed' }
  | { kind: 'empty' }
  | { kind: 'ready'; primary: PrimaryResume; secondary: ResumeResponse | null }

export type JobsSection =
  | { kind: 'failed' }
  | { kind: 'ready'; saved: JobResponse[]; applied: JobResponse[] }

export type DashboardView =
  | { kind: 'failed' }
  | { kind: 'onboarding' }
  | { kind: 'workbench'; resume: ResumeSection; jobs: JobsSection }

export function deriveView(data: DashboardData): DashboardView {
  const { resumes, jobs } = data
  if (resumes === null && jobs === null) {
    return { kind: 'failed' }
  }

  const hasResume = resumes !== null && (resumes.draft !== null || resumes.uploads.length > 0)
  if (resumes !== null && !hasResume && jobs !== null && jobs.length === 0) {
    return { kind: 'onboarding' }
  }

  let resume: ResumeSection
  if (resumes === null) {
    resume = { kind: 'failed' }
  } else if (resumes.draft) {
    resume = {
      kind: 'ready',
      primary: { kind: 'draft', draft: resumes.draft, ready: resumes.draftReady },
      secondary: resumes.uploads[0] ?? null,
    }
  } else if (resumes.uploads[0]) {
    resume = {
      kind: 'ready',
      primary: { kind: 'upload', upload: resumes.uploads[0] },
      secondary: resumes.uploads[1] ?? null,
    }
  } else {
    resume = { kind: 'empty' }
  }

  const jobsSection: JobsSection = jobs === null ? { kind: 'failed' } : { kind: 'ready', ...splitMyJobs(jobs) }

  return { kind: 'workbench', resume, jobs: jobsSection }
}

/**
 * The file a "quality score" link may open. A draft is never scored: when the primary
 * item is a draft, only an uploaded file shown next to it qualifies.
 */
export function qualityTargetId(section: ResumeSection): number | null {
  if (section.kind !== 'ready') {
    return null
  }
  if (section.primary.kind === 'upload') {
    return section.primary.upload.id
  }
  return section.secondary?.id ?? null
}

export function firstName(name: string | null | undefined): string {
  const trimmed = (name ?? '').trim()
  return trimmed.split(/\s+/)[0] || trimmed
}
