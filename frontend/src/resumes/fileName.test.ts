import { describe, expect, it } from 'vitest'
import {
  decodeStoredFileName,
  displayFileName,
  fileNameFromContentDisposition,
  safeDownloadFileName,
} from './fileName'

describe('displayFileName', () => {
  it('keeps real names', () => {
    expect(displayFileName('简历.pdf')).toBe('简历.pdf')
    expect(displayFileName('简历 (1).pdf')).toBe('简历 (1).pdf')
    expect(displayFileName('alex-morgan.pdf')).toBe('alex-morgan.pdf')
  })

  it('decodes a phone-encoded Chinese name', () => {
    expect(displayFileName('%E7%AE%80%E5%8E%86.pdf')).toBe('简历.pdf')
    expect(displayFileName('%E7%AE%80%E5%8E%86%20(1).pdf')).toBe('简历 (1).pdf')
    expect(displayFileName('%e7%ae%80%e5%8e%86%20%281%29.pdf')).toBe('简历 (1).pdf')
  })

  it('keeps the stored name when the percent sequence is not valid, without throwing', () => {
    expect(displayFileName('100%.pdf')).toBe('100%.pdf')
    expect(displayFileName('%ZZ.pdf')).toBe('%ZZ.pdf')
    expect(displayFileName('%E7%AE.pdf')).toBe('%E7%AE.pdf')
    expect(displayFileName('简历%E7.pdf')).toBe('简历%E7.pdf')
    expect(decodeStoredFileName('%')).toBe('%')
  })

  it('decodes repeated encoding layer by layer', () => {
    expect(displayFileName('%25E7%25AE%2580%25E5%258E%2586.pdf')).toBe('简历.pdf')
    expect(displayFileName('%2525E7%2525AE%252580%2525E5%25258E%252586.pdf')).toBe('简历.pdf')
  })

  it('decodes before removing path parts and control characters', () => {
    expect(displayFileName('..%2F..%2Fetc%2Fpasswd.pdf')).toBe('passwd.pdf')
    expect(displayFileName('a%5Cb.pdf')).toBe('b.pdf')
    expect(displayFileName('cv%00.pdf')).toBe('cv.pdf')
    expect(displayFileName('..%2F%E7%AE%80%E5%8E%86.pdf')).toBe('简历.pdf')
    expect(displayFileName('C:\\Users\\a\\x.pdf')).toBe('x.pdf')
    expect(displayFileName('\u202efdp.exe')).toBe('fdp.exe')
    expect(displayFileName('%2E%2E')).toBe('')
    expect(displayFileName(null)).toBe('')
  })
})

describe('download names', () => {
  it('uses filename* from the server and falls back safely', () => {
    const header = "attachment; filename=\"_ (1).pdf\"; filename*=UTF-8''%E7%AE%80%E5%8E%86%20%281%29.pdf"
    expect(fileNameFromContentDisposition(header, 'x.pdf')).toBe('简历 (1).pdf')
    expect(fileNameFromContentDisposition("attachment; filename*=UTF-8''%E7%AE.pdf; filename=\"cv.pdf\"", 'x.pdf')).toBe('cv.pdf')
    expect(fileNameFromContentDisposition('attachment; filename="report.docx"', 'x.pdf')).toBe('report.docx')
    expect(fileNameFromContentDisposition(undefined, '%E7%AE%80%E5%8E%86.pdf')).toBe('简历.pdf')
  })

  it('never produces a path or a reserved character', () => {
    expect(safeDownloadFileName('..%2F..%2Fa%3Ab%3F.pdf')).toBe('a_b_.pdf')
    expect(safeDownloadFileName('..%2F..%2Fa:b?.pdf')).toBe('..%2F..%2Fa_b_.pdf')
    expect(safeDownloadFileName('', 'resume.pdf')).toBe('resume.pdf')
  })
})
