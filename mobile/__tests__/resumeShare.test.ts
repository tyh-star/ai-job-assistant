import { ShareUnavailableError, downloadAndShare } from '../src/resumes/files'

const written = (jest.requireMock('expo-file-system') as { __written: Map<string, Uint8Array> }).__written

function deps(overrides: Partial<Parameters<typeof downloadAndShare>[1]> = {}) {
  return {
    // What the server sends back for a phone upload: the stored, upload-encoded name.
    download: jest
      .fn()
      .mockResolvedValue({ bytes: new Uint8Array([1, 2, 3]), fileName: '%E7%AE%80%E5%8E%86%20(1).pdf', contentType: 'application/pdf' }),
    isSharingAvailable: jest.fn().mockResolvedValue(true),
    share: jest.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

beforeEach(() => written.clear())

it('writes the downloaded bytes into the cache under a safe name and opens the share sheet', async () => {
  const d = deps()
  const result = await downloadAndShare({ id: 12, fileName: 'fallback.pdf', fileType: 'pdf' }, d)

  expect(d.download).toHaveBeenCalledWith(12)
  expect(result.name).toBe('简历 (1).pdf')
  expect(result.uri).toBe('file:///cache/resumes/简历 (1).pdf')
  expect(Array.from(written.get(result.uri) ?? [])).toEqual([1, 2, 3])
  expect(d.share).toHaveBeenCalledWith(result.uri, {
    mimeType: 'application/pdf',
    UTI: 'com.adobe.pdf',
    dialogTitle: '简历 (1).pdf',
  })
})

it('still strips characters the file system rejects from a readable name', async () => {
  const d = deps({
    download: jest.fn().mockResolvedValue({ bytes: new Uint8Array([7]), fileName: 'a:b|c?.pdf', contentType: 'application/pdf' }),
  })
  const result = await downloadAndShare({ id: 5, fileName: 'a:b|c?.pdf', fileType: 'pdf' }, d)
  expect(result.name).toBe('abc.pdf')
})

it('falls back to the list file name when the server sent none', async () => {
  const d = deps({ download: jest.fn().mockResolvedValue({ bytes: new Uint8Array([9]), fileName: null, contentType: null }) })
  const result = await downloadAndShare({ id: 3, fileName: 'cv.docx', fileType: 'docx' }, d)
  expect(result.name).toBe('cv.docx')
})

it('does not download at all when the device cannot share', async () => {
  const d = deps({ isSharingAvailable: jest.fn().mockResolvedValue(false) })
  await expect(downloadAndShare({ id: 12, fileName: 'cv.pdf', fileType: 'pdf' }, d)).rejects.toBeInstanceOf(ShareUnavailableError)
  expect(d.download).not.toHaveBeenCalled()
})

it('surfaces a failed download and writes nothing', async () => {
  const d = deps({ download: jest.fn().mockRejectedValue(new Error('boom')) })
  await expect(downloadAndShare({ id: 12, fileName: 'cv.pdf', fileType: 'pdf' }, d)).rejects.toThrow('boom')
  expect(written.size).toBe(0)
  expect(d.share).not.toHaveBeenCalled()
})
