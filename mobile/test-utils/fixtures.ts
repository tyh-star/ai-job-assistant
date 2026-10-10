/**
 * Test-only builders. Nothing under test-utils/ is imported by app/ or src/.
 * Names are deliberately generic so they can never pass for a real account.
 */
import type {
  JobCatalogDetail,
  JobCatalogSearchResponse,
  JobCatalogSummary,
  JobResponse,
  ResumeBuilderDraftResponse,
  ResumeBuilderDraftSummary,
  ResumeQualityScoreResponse,
  ResumeResponse,
} from '../src/api/types'

export function personalJob(id: number, patch: Partial<JobResponse> = {}): JobResponse {
  return {
    id,
    company: `Org ${id}`,
    title: `Role ${id}`,
    location: 'Toronto, ON',
    jobUrl: 'https://jobs.example.test/role',
    description: 'Test description.',
    source: 'FREEHIRE',
    status: 'SAVED',
    externalJobId: 'a'.repeat(64),
    applied: false,
    appliedAt: null,
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-01T10:00:00',
    ...patch,
  }
}

export function catalogSummary(id: string, patch: Partial<JobCatalogSummary> = {}): JobCatalogSummary {
  return {
    id,
    title: `Catalog role ${id}`,
    companyName: 'Test Company',
    locationText: 'Toronto, ON',
    countryCode: 'ca',
    remoteType: 'REMOTE',
    employmentType: 'FULL_TIME',
    salaryMin: null,
    salaryMax: null,
    currency: null,
    canonicalUrl: 'https://jobs.example.test/posting',
    source: 'FREEHIRE',
    postedAt: '2026-09-08T09:00:00',
    firstSeenAt: '2026-09-08T09:00:00',
    lastSeenAt: '2026-09-10T09:00:00',
    status: 'ACTIVE',
    saved: false,
    personalJobId: null,
    applied: false,
    appliedAt: null,
    cityMatch: null,
    ...patch,
  }
}

export function catalogDetail(id: string, patch: Partial<JobCatalogDetail> = {}): JobCatalogDetail {
  const { cityMatch: _unused, ...summary } = catalogSummary(id)
  return {
    ...summary,
    descriptionText: 'Test description for this posting.',
    descriptionHtml: null,
    city: 'Toronto',
    region: 'ON',
    applyUrl: 'https://jobs.example.test/apply',
    ...patch,
  }
}

export function searchPage(
  results: JobCatalogSummary[],
  patch: Partial<JobCatalogSearchResponse> = {},
): JobCatalogSearchResponse {
  return {
    page: 1,
    size: 20,
    totalCount: results.length,
    totalPages: 1,
    hasMore: false,
    results,
    ...patch,
  }
}

export function uploadResume(
  id: number,
  fileName: string,
  updatedAt = '2026-09-08T00:00:00Z',
  patch: Partial<ResumeResponse> = {},
): ResumeResponse {
  return {
    id,
    fileName,
    fileType: fileName.toLowerCase().endsWith('.docx') ? 'docx' : 'pdf',
    createdAt: updatedAt,
    updatedAt,
    origin: 'UPLOAD',
    reviewStatus: 'READY',
    parentResumeId: null,
    canonicalResumeId: null,
    fileAvailable: true,
    downloadUrl: `/api/v1/resumes/${id}/download`,
    ...patch,
  }
}

export function draftSummary(id: number, patch: Partial<ResumeBuilderDraftSummary> = {}): ResumeBuilderDraftSummary {
  return {
    id,
    title: `Draft ${id}`,
    templateType: 'STUDENT',
    targetRole: 'Junior Analyst',
    locale: 'en',
    version: 2,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-07T00:00:00Z',
    ...patch,
  }
}

export function draftDetail(id: number, patch: Partial<ResumeBuilderDraftResponse> = {}): ResumeBuilderDraftResponse {
  return {
    ...draftSummary(id),
    content: {
      basicInfo: { fullName: 'Test Person', email: 'person@example.test', phone: '', city: 'Toronto', country: 'Canada' },
      jobTarget: { targetTitle: 'Junior Analyst' },
      education: [{}],
      experience: [{}, {}],
      projects: [],
    },
    ...patch,
  }
}

export function qualityScore(patch: Partial<ResumeQualityScoreResponse> = {}): ResumeQualityScoreResponse {
  return {
    scorable: true,
    reasonCode: null,
    score: 72,
    level: 'GOOD',
    rubricVersion: 'v1',
    categories: [
      { code: 'STRUCTURE', score: 18, maxScore: 25 },
      { code: 'ACHIEVEMENTS', score: 9, maxScore: 20 },
    ],
    strengths: ['STRUCTURE_COMPLETE'],
    suggestions: ['QUANTIFY_ACHIEVEMENTS'],
    ...patch,
  }
}
