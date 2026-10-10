import '../src/i18n'
import { Alert } from 'react-native'
import { screen, userEvent } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { resumesApi } from '../src/api/endpoints'
import { displayFileName } from '../src/resumes/displayName'
import { ShareUnavailableError, downloadAndShare } from '../src/resumes/files'
import { resumeKeys } from '../src/resumes/queries'
import { ResumeFileScreen } from '../src/screens/ResumeFileScreen'
import { uploadResume } from '../test-utils/fixtures'
import { createTestQueryClient, renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  resumesApi: { list: jest.fn(), get: jest.fn(), remove: jest.fn(), removeFamily: jest.fn() },
  draftsApi: {},
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('../src/resumes/files', () => {
  const actual = jest.requireActual('../src/resumes/files')
  return { ...actual, downloadAndShare: jest.fn() }
})

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const resumes = resumesApi as jest.Mocked<typeof resumesApi>
const share = downloadAndShare as jest.MockedFunction<typeof downloadAndShare>

function answerDialogs(confirm: boolean) {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    const button = confirm ? buttons?.[buttons.length - 1] : buttons?.[0]
    button?.onPress?.()
  })
}

async function renderFile(resume = uploadResume(12, 'cv.pdf')) {
  resumes.get.mockResolvedValue(resume)
  const onOpenQuality = jest.fn()
  const onDeleted = jest.fn()
  const client = createTestQueryClient()
  client.setQueryData(resumeKeys.files(1), [resume, uploadResume(13, 'other.docx')])
  await renderWithQuery(
    <ResumeFileScreen resumeId={resume.id} onBack={jest.fn()} onOpenQuality={onOpenQuality} onDeleted={onDeleted} />,
    client,
  )
  await screen.findByRole('heading', { name: displayFileName(resume.fileName) })
  return { onOpenQuality, onDeleted, client }
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  resumes.list.mockResolvedValue([])
})

it('titles a phone-uploaded file with its readable name', async () => {
  await renderFile(uploadResume(12, '%E7%AE%80%E5%8E%86.pdf'))
  expect(screen.getByRole('heading', { name: '简历.pdf' })).toBeOnTheScreen()
})

it('opens the quality score with the file id', async () => {
  const { onOpenQuality } = await renderFile()
  await userEvent.setup().press(screen.getByRole('button', { name: '查看质量评分' }))
  expect(onOpenQuality).toHaveBeenCalledWith(12)
})

it('downloads and shares, then says so', async () => {
  share.mockResolvedValue({ name: 'cv.pdf', uri: 'file:///cache/resumes/cv.pdf' })
  await renderFile()
  await userEvent.setup().press(screen.getByRole('button', { name: '下载并分享' }))
  expect(await screen.findByText('文件已准备好，可以分享或打开')).toBeOnTheScreen()
  expect(share).toHaveBeenCalledWith(expect.objectContaining({ id: 12, fileName: 'cv.pdf' }))
})

it('explains a failed download instead of pretending it worked', async () => {
  share.mockRejectedValueOnce(new ApiError('/resumes/12/download', 404, { message: 'Resume not found' }))
  await renderFile()
  const user = userEvent.setup()
  await user.press(screen.getByRole('button', { name: '下载并分享' }))
  expect(await screen.findByText('下载没有成功：未找到该资源。')).toBeOnTheScreen()

  share.mockRejectedValueOnce(new ShareUnavailableError())
  await user.press(screen.getByRole('button', { name: '下载并分享' }))
  expect(await screen.findByText('下载没有成功：这台设备不支持系统分享。')).toBeOnTheScreen()
})

it('disables download when the server has no file for this resume', async () => {
  await renderFile(uploadResume(12, 'cv.pdf', undefined, { fileAvailable: false, downloadUrl: null }))
  expect(screen.getByRole('button', { name: '下载并分享' })).toBeDisabled()
  expect(screen.getByText('服务器上找不到这份简历的文件，暂时无法下载。')).toBeOnTheScreen()
})

it('has no tailoring button any more and points to the 改写 tab instead', async () => {
  const resume = uploadResume(14, 'cv.docx')
  resumes.get.mockResolvedValue(resume)
  await renderWithQuery(<ResumeFileScreen resumeId={resume.id} onBack={jest.fn()} onOpenQuality={jest.fn()} onDeleted={jest.fn()} />)
  await screen.findByRole('heading', { name: displayFileName(resume.fileName) })
  expect(screen.queryByRole('button', { name: '按职位改写' })).not.toBeOnTheScreen()
  expect(screen.getByText(/底部「改写」/)).toBeOnTheScreen()
})

it('offers the recognized-content review for an available PDF', async () => {
  const onOpenStructure = jest.fn()
  const resume = uploadResume(12, 'cv.pdf')
  resumes.get.mockResolvedValue(resume)
  await renderWithQuery(
    <ResumeFileScreen resumeId={12} onBack={jest.fn()} onOpenQuality={jest.fn()} onDeleted={jest.fn()} onOpenStructure={onOpenStructure} />,
  )
  await userEvent.setup().press(await screen.findByRole('button', { name: '确认识别结果' }))
  expect(onOpenStructure).toHaveBeenCalledWith(12)
})

it('does not offer the recognized-content review for a DOCX', async () => {
  resumes.get.mockResolvedValue(uploadResume(14, 'cv.docx'))
  await renderWithQuery(
    <ResumeFileScreen resumeId={14} onBack={jest.fn()} onOpenQuality={jest.fn()} onDeleted={jest.fn()} onOpenStructure={jest.fn()} />,
  )
  await screen.findByRole('heading', { name: 'cv.docx' })
  expect(screen.queryByRole('button', { name: '确认识别结果' })).not.toBeOnTheScreen()
})

it('asks before deleting, and does nothing when cancelled', async () => {
  const alert = answerDialogs(false)
  await renderFile()
  await userEvent.setup().press(screen.getByRole('button', { name: '删除简历' }))
  expect(alert).toHaveBeenCalledWith('删除这份简历？', '删除后无法恢复。', expect.any(Array), expect.anything())
  expect(resumes.remove).not.toHaveBeenCalled()
})

it('deletes after confirmation and removes the row from the shared list', async () => {
  answerDialogs(true)
  resumes.remove.mockResolvedValue(undefined)
  const { onDeleted, client } = await renderFile()
  await userEvent.setup().press(screen.getByRole('button', { name: '删除简历' }))
  expect(resumes.remove).toHaveBeenCalledWith(12)
  expect(resumes.removeFamily).not.toHaveBeenCalled()
  expect(onDeleted).toHaveBeenCalledTimes(1)
  expect(client.getQueryData<{ id: number }[]>(resumeKeys.files(1))?.map((row) => row.id)).toEqual([13])
})

it('deletes a PDF with a Word working copy as a family, and warns about it', async () => {
  const alert = answerDialogs(true)
  resumes.removeFamily.mockResolvedValue(undefined)
  await renderFile(uploadResume(12, 'cv.pdf', undefined, { canonicalResumeId: 99 }))
  await userEvent.setup().press(screen.getByRole('button', { name: '删除简历' }))
  expect(alert.mock.calls[0][1]).toContain('内部 Word 文件')
  expect(resumes.removeFamily).toHaveBeenCalledWith(12)
  expect(resumes.remove).not.toHaveBeenCalled()
})
