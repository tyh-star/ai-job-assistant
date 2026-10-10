import { buildResumeSlice, deriveView, isDraftReady, qualityTargetId } from '../src/dashboard/model'
import { draftSummary, personalJob, uploadResume } from '../test-utils/fixtures'

describe('dashboard model', () => {
  it('shows onboarding only when resumes and jobs both loaded empty', () => {
    expect(deriveView({ resumes: buildResumeSlice([], [], null), jobs: [] })).toEqual({ kind: 'onboarding' })
  })

  it('shows the resume with two empty job sections when there is only a resume', () => {
    expect(deriveView({ resumes: buildResumeSlice([], [uploadResume(3, 'cv.pdf')], null), jobs: [] })).toMatchObject({
      kind: 'workbench',
      resume: { kind: 'ready', primary: { kind: 'upload' }, secondary: null },
      jobs: { kind: 'ready', saved: [], applied: [] },
    })
  })

  it('never turns a failed request into an empty state', () => {
    expect(deriveView({ resumes: buildResumeSlice([], [], null), jobs: null })).toMatchObject({
      kind: 'workbench',
      resume: { kind: 'empty' },
      jobs: { kind: 'failed' },
    })
    expect(deriveView({ resumes: null, jobs: null })).toEqual({ kind: 'failed' })
  })

  it('leads with the newest draft, keeps uploads second, and ignores template files', () => {
    const slice = buildResumeSlice(
      [draftSummary(7, { updatedAt: '2026-09-01T00:00:00Z' }), draftSummary(8, { updatedAt: '2026-09-09T00:00:00Z' })],
      [
        uploadResume(1, 'a.pdf', '2026-09-01T00:00:00Z'),
        uploadResume(2, 'b.pdf', '2026-09-05T00:00:00Z'),
        uploadResume(9, 'template.docx', '2026-09-10T00:00:00Z', { origin: 'TEMPLATE' }),
      ],
      true,
    )
    expect(deriveView({ resumes: slice, jobs: [] })).toMatchObject({
      resume: { primary: { kind: 'draft', draft: { id: 8 }, ready: true }, secondary: { id: 2 } },
    })
  })

  it('points the quality score at a file id, never at a draft id', () => {
    const uploadOnly = deriveView({ resumes: buildResumeSlice([], [uploadResume(21, 'cv.pdf')], null), jobs: [] })
    const draftWithFile = deriveView({ resumes: buildResumeSlice([draftSummary(21)], [uploadResume(5, 'cv.pdf')], null), jobs: [] })
    const draftOnly = deriveView({ resumes: buildResumeSlice([draftSummary(21)], [], null), jobs: [] })
    if (uploadOnly.kind !== 'workbench' || draftWithFile.kind !== 'workbench' || draftOnly.kind !== 'workbench') {
      throw new Error('expected workbench views')
    }
    expect(qualityTargetId(uploadOnly.resume)).toBe(21)
    expect(qualityTargetId(draftWithFile.resume)).toBe(5)
    expect(qualityTargetId(draftOnly.resume)).toBeNull()
  })

  it('orders applications newest first', () => {
    const view = deriveView({
      resumes: null,
      jobs: [
        personalJob(1, { applied: true, appliedAt: '2026-09-05T00:00:00Z' }),
        personalJob(2),
        personalJob(3, { applied: true, appliedAt: '2026-09-10T00:00:00Z' }),
      ],
    })
    if (view.kind !== 'workbench' || view.jobs.kind !== 'ready') {
      throw new Error('expected a loaded workbench')
    }
    expect(view.jobs.saved.map((j) => j.id)).toEqual([2])
    expect(view.jobs.applied.map((j) => j.id)).toEqual([3, 1])
  })

  it('needs a name, a contact and a target title to call a draft ready', () => {
    const content = { basicInfo: { fullName: 'Test Person', email: 't@example.test' }, jobTarget: { targetTitle: '' } }
    expect(isDraftReady(content, null)).toBe(false)
    expect(isDraftReady(content, 'Analyst')).toBe(true)
  })
})
