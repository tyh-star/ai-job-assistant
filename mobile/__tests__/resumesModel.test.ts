import {
  DOCX_MIME,
  MAX_RESUME_BYTES,
  PDF_MIME,
  deletesFamily,
  draftSectionCounts,
  parseResourceId,
  safeDownloadName,
  validatePickedFile,
  visibleFiles,
} from '../src/resumes/model'
import { uploadResume } from '../test-utils/fixtures'

const pick = (name: string | null, size: number | null | undefined, mimeType?: string | null) => ({
  uri: 'file:///picked',
  name,
  size,
  mimeType,
})

describe('picked file validation', () => {
  it('accepts PDF and DOCX, and sends the exact MIME type the server lists', () => {
    expect(validatePickedFile(pick('cv.PDF', 2048, 'application/pdf'))).toMatchObject({
      ok: true,
      file: { name: 'cv.PDF', kind: 'pdf', mimeType: PDF_MIME, size: 2048 },
    })
    // Some Android pickers report DOCX generically; the server accepts that too.
    expect(validatePickedFile(pick('cv.docx', 2048, 'application/octet-stream'))).toMatchObject({
      ok: true,
      file: { kind: 'docx', mimeType: DOCX_MIME },
    })
    expect(validatePickedFile(pick('cv.docx', 2048, undefined))).toMatchObject({ ok: true })
  })

  it('rejects other extensions, and a PDF name hiding another type', () => {
    expect(validatePickedFile(pick('cv.doc', 2048, 'application/msword'))).toEqual({ ok: false, problem: 'badType' })
    expect(validatePickedFile(pick('photo.png', 2048, 'image/png'))).toEqual({ ok: false, problem: 'badType' })
    expect(validatePickedFile(pick('cv', 2048, PDF_MIME))).toEqual({ ok: false, problem: 'badType' })
    expect(validatePickedFile(pick('cv.pdf', 2048, 'image/png'))).toEqual({ ok: false, problem: 'badMime' })
  })

  it('enforces the 10MB limit on the client, inclusive', () => {
    expect(validatePickedFile(pick('cv.pdf', MAX_RESUME_BYTES, PDF_MIME))).toMatchObject({ ok: true })
    expect(validatePickedFile(pick('cv.pdf', MAX_RESUME_BYTES + 1, PDF_MIME))).toEqual({ ok: false, problem: 'tooLarge' })
  })

  it('rejects empty files, unknown sizes and missing names', () => {
    expect(validatePickedFile(pick('cv.pdf', 0, PDF_MIME))).toEqual({ ok: false, problem: 'empty' })
    expect(validatePickedFile(pick('cv.pdf', undefined, PDF_MIME))).toEqual({ ok: false, problem: 'unknownSize' })
    expect(validatePickedFile(pick('   ', 10, PDF_MIME))).toEqual({ ok: false, problem: 'noName' })
    expect(validatePickedFile(pick(null, 10, PDF_MIME))).toEqual({ ok: false, problem: 'noName' })
  })

  it('keeps only the base name of a path-like file name', () => {
    expect(validatePickedFile(pick('../../private/cv.pdf', 10, PDF_MIME))).toMatchObject({ ok: true, file: { name: 'cv.pdf' } })
  })
})

describe('download file names', () => {
  it('strips path parts, control and reserved characters, and forces the real extension', () => {
    expect(safeDownloadName('../../etc/passwd', 'pdf')).toBe('passwd.pdf')
    expect(safeDownloadName('C:\\Users\\me\\简历 (1).pdf', 'pdf')).toBe('简历 (1).pdf')
    expect(safeDownloadName('bad<>:"|?*\u0001name.docx', 'docx')).toBe('badname.docx')
    expect(safeDownloadName('report.pdf', 'docx')).toBe('report.pdf.docx')
  })

  it('never produces an empty, dot-only or hidden name', () => {
    expect(safeDownloadName('', 'pdf')).toBe('resume.pdf')
    expect(safeDownloadName('..', 'pdf')).toBe('resume.pdf')
    expect(safeDownloadName('...pdf', 'pdf')).toBe('resume.pdf')
    expect(safeDownloadName('.hidden.docx', 'docx')).toBe('hidden.docx')
    expect(safeDownloadName(null, 'docx')).toBe('resume.docx')
  })

  it('limits very long names', () => {
    const name = safeDownloadName(`${'a'.repeat(300)}.pdf`, 'pdf')
    expect(name.length).toBeLessThanOrEqual(124)
    expect(name.endsWith('.pdf')).toBe(true)
  })
})

describe('resume rules', () => {
  it('deletes a PDF with a Word working copy as a family, anything else singly', () => {
    expect(deletesFamily(uploadResume(1, 'cv.pdf', undefined, { canonicalResumeId: 9 }))).toBe(true)
    expect(deletesFamily(uploadResume(1, 'cv.pdf'))).toBe(false)
    expect(deletesFamily(uploadResume(1, 'cv.docx', undefined, { canonicalResumeId: 9 }))).toBe(false)
  })

  it('hides template working copies and lists newest first', () => {
    const rows = visibleFiles([
      uploadResume(1, 'old.pdf', '2026-09-01T00:00:00Z'),
      uploadResume(2, 'template.docx', '2026-09-09T00:00:00Z', { origin: 'TEMPLATE' }),
      uploadResume(3, 'new.pdf', '2026-09-05T00:00:00Z', { origin: 'GENERATED' }),
    ])
    expect(rows.map((row) => row.id)).toEqual([3, 1])
  })

  it('counts only the draft sections that have entries', () => {
    expect(
      draftSectionCounts({ basicInfo: {}, jobTarget: {}, education: [{}], experience: [], projects: [{}, {}] }),
    ).toEqual([
      { key: 'education', count: 1 },
      { key: 'projects', count: 2 },
    ])
    expect(draftSectionCounts(null)).toEqual([])
  })

  it('accepts only positive numeric ids from a route', () => {
    expect(parseResourceId('12')).toBe(12)
    expect(parseResourceId('abc')).toBeNull()
    expect(parseResourceId('0')).toBeNull()
  })
})
