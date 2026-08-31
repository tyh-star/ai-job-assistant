import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { apiErrorKey } from './client'
import { backendOnlyRelativePaths, endpoints, frontendRelativeEndpoints } from './endpoints'
import { toApiPath } from './resumes'
import {
  hasDuplicateApiPrefix,
  isPublicAuthEndpoint,
  joinApiUrl,
  PRODUCTION_API_BASE,
  resolveApiBase,
  stripApiV1Prefix,
} from './url'

const srcDir = dirname(fileURLToPath(import.meta.url))
const productionBase = '/api/v1'

function axiosError(status: number, message = 'Name is required') {
  return { response: { status, data: { message } } }
}

describe('API URL scheme A', () => {
  it('defaults production and missing env to /api/v1, not localhost', () => {
    expect(resolveApiBase({})).toBe(PRODUCTION_API_BASE)
    expect(resolveApiBase({ DEV: true })).toBe('/api/v1')
    expect(resolveApiBase({ PROD: true })).toBe('/api/v1')
    expect(resolveApiBase({ VITE_API_BASE_URL: '/api/v1' })).toBe('/api/v1')
    expect(resolveApiBase({ VITE_API_BASE_URL: '/api/v1/' })).toBe('/api/v1')
    expect(resolveApiBase({})).not.toContain('localhost')
    expect(resolveApiBase({})).not.toContain('8080')
  })

  it('joins register and login to a single /api/v1 prefix', () => {
    expect(joinApiUrl(productionBase, endpoints.register)).toBe('/api/v1/auth/register')
    expect(joinApiUrl(productionBase, endpoints.login)).toBe('/api/v1/auth/login')
    expect(joinApiUrl(productionBase, '/api/v1/auth/register')).toBe('/api/v1/auth/register')
    expect(joinApiUrl(productionBase, '/api/v1/auth/login')).toBe('/api/v1/auth/login')
    expect(hasDuplicateApiPrefix(joinApiUrl(productionBase, endpoints.register))).toBe(false)
    expect(hasDuplicateApiPrefix(joinApiUrl(productionBase, endpoints.login))).toBe(false)
  })

  it('joins every frontend and download-style endpoint without a duplicate prefix', () => {
    const all = [
      ...frontendRelativeEndpoints(),
      ...backendOnlyRelativePaths,
    ]
    for (const endpoint of all) {
      const joined = joinApiUrl(productionBase, endpoint)
      expect(joined.startsWith('/api/v1/'), joined).toBe(true)
      expect(hasDuplicateApiPrefix(joined), joined).toBe(false)
      expect(joined).not.toContain('localhost')
      expect(joined).not.toContain('3.99.54.214')
      expect(joined).not.toMatch(/:\s*8080/)
    }
  })

  it('covers resume upload/list/detail/delete and evidence', () => {
    expect(joinApiUrl(productionBase, endpoints.resumes)).toBe('/api/v1/resumes')
    expect(joinApiUrl(productionBase, endpoints.resume(9))).toBe('/api/v1/resumes/9')
    expect(joinApiUrl(productionBase, endpoints.resumeFamily(9))).toBe('/api/v1/resumes/9/family')
    expect(joinApiUrl(productionBase, endpoints.resumeEvidence(9))).toBe('/api/v1/resumes/9/evidence')
    expect(joinApiUrl(productionBase, endpoints.resumeEvidenceItem(9, 3)))
      .toBe('/api/v1/resumes/9/evidence/3')
  })

  it('covers jobs CRUD, search, quick optimize, and enhanced flow', () => {
    expect(joinApiUrl(productionBase, endpoints.jobs)).toBe('/api/v1/jobs')
    expect(joinApiUrl(productionBase, endpoints.job(7))).toBe('/api/v1/jobs/7')
    expect(joinApiUrl(productionBase, endpoints.jobsSearch)).toBe('/api/v1/jobs/search')
    expect(joinApiUrl(productionBase, endpoints.jobTailorProposals(7)))
      .toBe('/api/v1/jobs/7/tailor-proposals')
    expect(joinApiUrl(productionBase, endpoints.jobEnhancedOpportunity(7)))
      .toBe('/api/v1/jobs/7/tailor-enhanced/opportunity')
    expect(joinApiUrl(productionBase, endpoints.jobEnhancedAnswers(7)))
      .toBe('/api/v1/jobs/7/tailor-enhanced/answers')
    expect(joinApiUrl(productionBase, endpoints.jobTailorConfirm(7, 42)))
      .toBe('/api/v1/jobs/7/tailor-proposals/42/confirm')
  })

  it('does not re-prefix backend downloadUrl values', () => {
    const docx = '/api/v1/jobs/7/tailored/out.docx'
    const pdf = '/api/v1/jobs/7/tailored/out.pdf'
    const resume = '/api/v1/resumes/9/download'
    expect(toApiPath(docx)).toBe('/jobs/7/tailored/out.docx')
    expect(toApiPath(pdf)).toBe('/jobs/7/tailored/out.pdf')
    expect(toApiPath(resume)).toBe('/resumes/9/download')
    expect(joinApiUrl(productionBase, toApiPath(docx))).toBe(docx)
    expect(joinApiUrl(productionBase, toApiPath(pdf))).toBe(pdf)
    expect(joinApiUrl(productionBase, toApiPath(resume))).toBe(resume)
    expect(joinApiUrl(productionBase, docx)).toBe(docx)
    expect(stripApiV1Prefix(docx)).toBe('/jobs/7/tailored/out.docx')
  })

  it('treats login and register as public auth endpoints', () => {
    expect(isPublicAuthEndpoint('/auth/register')).toBe(true)
    expect(isPublicAuthEndpoint('/auth/login')).toBe(true)
    expect(isPublicAuthEndpoint('/api/v1/auth/register')).toBe(true)
    expect(isPublicAuthEndpoint('/api/v1/auth/login?x=1')).toBe(true)
    expect(isPublicAuthEndpoint('/users/me')).toBe(false)
    expect(isPublicAuthEndpoint('/resumes')).toBe(false)
  })
})

describe('apiErrorKey status mapping', () => {
  it('maps 400 validation to badRequest, not permission errors', () => {
    expect(apiErrorKey(axiosError(400, 'name: Name is required'))).toBe('errors.badRequest')
    expect(apiErrorKey(axiosError(400, 'email: Email is required'))).toBe('errors.badRequest')
    expect(apiErrorKey(axiosError(400))).not.toBe('errors.forbidden')
    expect(apiErrorKey(axiosError(400))).not.toBe('errors.unauthorized')
  })

  it('maps 401 and 403 to auth/permission keys only', () => {
    expect(apiErrorKey(axiosError(401))).toBe('errors.unauthorized')
    expect(apiErrorKey(axiosError(403))).toBe('errors.forbidden')
  })
})

describe('frontend API modules', () => {
  it('do not hardcode localhost, EC2 IP, or a second /api/v1 on endpoints', () => {
    const files = [
      'auth.ts',
      'resumes.ts',
      'jobs.ts',
      'client.ts',
      'endpoints.ts',
      'url.ts',
    ]
    for (const file of files) {
      const source = readFileSync(join(srcDir, file), 'utf8')
      expect(source, file).not.toContain('localhost:8080')
      expect(source, file).not.toContain('3.99.54.214')
      expect(source, file).not.toMatch(/https?:\/\/[^'"\s]*:8080/)
    }
    const endpointSource = readFileSync(join(srcDir, 'endpoints.ts'), 'utf8')
    expect(endpointSource).not.toMatch(/['`]\/api\/v1\//)
  })
})
