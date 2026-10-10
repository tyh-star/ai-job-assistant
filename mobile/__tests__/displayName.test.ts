import { decodeUploadedFileName, displayFileName } from '../src/resumes/displayName'
import { safeDownloadName } from '../src/resumes/model'

/** Exactly what React Native 0.86's FormData puts into filename="..." for a picked file. */
function reactNativeFormDataName(name: string): string {
  return encodeURIComponent(name.replace(/\//g, '_'))
}

describe('names stored from a React Native upload', () => {
  it('restores Chinese names', () => {
    expect(reactNativeFormDataName('简历.pdf')).toBe('%E7%AE%80%E5%8E%86.pdf')
    expect(displayFileName('%E7%AE%80%E5%8E%86.pdf')).toBe('简历.pdf')
  })

  it('restores Chinese names with spaces and brackets', () => {
    const stored = reactNativeFormDataName('我的 简历 (1).docx')
    expect(stored).toBe('%E6%88%91%E7%9A%84%20%E7%AE%80%E5%8E%86%20(1).docx')
    expect(displayFileName(stored)).toBe('我的 简历 (1).docx')
  })

  it('accepts lowercase escapes', () => {
    expect(displayFileName('%e7%ae%80%e5%8e%86.pdf')).toBe('简历.pdf')
  })

  it('restores a real name that contains a percent sign', () => {
    const stored = reactNativeFormDataName('成绩 100%.pdf')
    expect(stored).toBe('%E6%88%90%E7%BB%A9%20100%25.pdf')
    expect(displayFileName(stored)).toBe('成绩 100%.pdf')
  })

  it('shows the file the user actually picked when its name was already encoded', () => {
    // A file literally named "%E7%AE%80%E5%8E%86.pdf" is uploaded as "%25E7%25AE...".
    const stored = reactNativeFormDataName('%E7%AE%80%E5%8E%86.pdf')
    expect(stored).toBe('%25E7%25AE%2580%25E5%258E%2586.pdf')
    expect(decodeUploadedFileName(stored)).toBe('%E7%AE%80%E5%8E%86.pdf')
    expect(displayFileName(stored)).toBe('%E7%AE%80%E5%8E%86.pdf')
  })
})

describe('names that must be left alone', () => {
  it('keeps plain English and plain Chinese names', () => {
    expect(displayFileName('Resume - Data Analyst.pdf')).toBe('Resume - Data Analyst.pdf')
    expect(displayFileName('简历 (1).pdf')).toBe('简历 (1).pdf')
  })

  it('keeps a real name with a literal percent sign', () => {
    expect(displayFileName('100% done.pdf')).toBe('100% done.pdf')
    expect(displayFileName('50%.docx')).toBe('50%.docx')
    // Escape-like text next to raw characters was not produced by encodeURIComponent.
    expect(displayFileName('折扣%20说明.pdf')).toBe('折扣%20说明.pdf')
    expect(displayFileName('a%20b c.pdf')).toBe('a%20b c.pdf')
  })

  it('keeps broken or incomplete escapes unchanged', () => {
    expect(displayFileName('%E7%AE.pdf')).toBe('%E7%AE.pdf')
    expect(displayFileName('%ZZ.pdf')).toBe('%ZZ.pdf')
    expect(displayFileName('%E7%AE%8.pdf')).toBe('%E7%AE%8.pdf')
    expect(displayFileName('%FF%FE.pdf')).toBe('%FF%FE.pdf')
  })
})

describe('safety after decoding', () => {
  it('removes path separators and control characters that decoding would expose', () => {
    expect(displayFileName('..%2F..%2Fetc%2Fpasswd.pdf')).toBe('.._.._etc_passwd.pdf')
    expect(displayFileName('a%5Cb.pdf')).toBe('a_b.pdf')
    expect(displayFileName('%01%0Abad.pdf')).toBe('bad.pdf')
  })

  it('never returns an empty name for a non-empty stored value', () => {
    expect(displayFileName('%0A')).toBe('%0A')
    expect(displayFileName(null)).toBe('')
  })

  it('gives the share sheet the same readable name as the list', () => {
    expect(safeDownloadName(displayFileName('%E7%AE%80%E5%8E%86.pdf'), 'pdf')).toBe('简历.pdf')
    expect(safeDownloadName(displayFileName('%E6%88%91%E7%9A%84%20%E7%AE%80%E5%8E%86.docx'), 'docx')).toBe('我的 简历.docx')
    // A hostile name stays readable in the list, and the saved file is never hidden.
    expect(displayFileName('..%2Fsecret.pdf')).toBe('.._secret.pdf')
    expect(safeDownloadName(displayFileName('..%2Fsecret.pdf'), 'pdf')).toBe('_secret.pdf')
  })
})
