import '../src/i18n'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { draftsApi, resumesApi } from '../src/api/endpoints'
import { pickResumeDocument } from '../src/resumes/picker'
import { resumeKeys } from '../src/resumes/queries'
import { ResumesScreen } from '../src/screens/ResumesScreen'
import { draftSummary, uploadResume } from '../test-utils/fixtures'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  resumesApi: { list: jest.fn(), upload: jest.fn() },
  draftsApi: { list: jest.fn() },
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('../src/resumes/picker', () => ({ pickResumeDocument: jest.fn() }))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const resumes = resumesApi as jest.Mocked<typeof resumesApi>
const drafts = draftsApi as jest.Mocked<typeof draftsApi>
const picker = pickResumeDocument as jest.MockedFunction<typeof pickResumeDocument>

async function renderResumes(props: Partial<Parameters<typeof ResumesScreen>[0]> = {}) {
  const onOpenFile = jest.fn()
  const onOpenDraft = jest.fn()
  const client = await renderWithQuery(<ResumesScreen onOpenFile={onOpenFile} onOpenDraft={onOpenDraft} {...props} />)
  return { onOpenFile, onOpenDraft, client }
}

beforeEach(() => {
  jest.resetAllMocks()
  resumes.list.mockResolvedValue([])
  drafts.list.mockResolvedValue([])
})

it('lists uploaded files and drafts separately and opens each by its own id', async () => {
  resumes.list.mockResolvedValue([uploadResume(12, 'cv-final.pdf', '2026-09-08T00:00:00Z', { reviewStatus: 'PENDING_REVIEW' })])
  drafts.list.mockResolvedValue([draftSummary(12, { title: 'Student draft' })])
  const { onOpenFile, onOpenDraft } = await renderResumes()
  const user = userEvent.setup()

  expect(await screen.findByText('cv-final.pdf')).toBeOnTheScreen()
  expect(screen.getByText('待确认内容')).toBeOnTheScreen()
  expect(screen.queryByText('Student draft')).not.toBeOnTheScreen()
  await user.press(screen.getByRole('link', { name: 'cv-final.pdf, PDF' }))
  expect(onOpenFile).toHaveBeenCalledWith(12)

  await user.press(screen.getByRole('tab', { name: '生成简历' }))
  expect(await screen.findByText('Student draft')).toBeOnTheScreen()
  expect(screen.getByText('学生模板')).toBeOnTheScreen()
  await user.press(screen.getByRole('link', { name: 'Student draft, 学生模板' }))
  expect(onOpenDraft).toHaveBeenCalledWith(12)
  expect(onOpenFile).toHaveBeenCalledTimes(1)
})

it('shows a phone-uploaded Chinese file name readably, and leaves a literal percent alone', async () => {
  resumes.list.mockResolvedValue([
    uploadResume(21, '%E7%AE%80%E5%8E%86.pdf', '2026-09-12T00:00:00Z'),
    uploadResume(22, '成绩 100%.pdf', '2026-09-11T00:00:00Z'),
  ])
  await renderResumes()
  expect(await screen.findByText('简历.pdf')).toBeOnTheScreen()
  expect(screen.getByText('成绩 100%.pdf')).toBeOnTheScreen()
  expect(screen.queryByText('%E7%AE%80%E5%8E%86.pdf')).not.toBeOnTheScreen()
})

it('keeps "no resumes" and "request failed" apart', async () => {
  await renderResumes()
  expect(await screen.findByText('还没有上传的简历')).toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('tab', { name: '生成简历' }))
  expect(await screen.findByText('还没有生成的简历')).toBeOnTheScreen()
  expect(screen.getByText('创建你的第一份简历')).toBeOnTheScreen()
  expect(screen.queryByText(/草稿/)).not.toBeOnTheScreen()
  expect(screen.queryByText('简历暂时无法加载')).not.toBeOnTheScreen()

  jest.resetAllMocks()
  resumes.list.mockRejectedValue(new ApiError('/resumes', 502, null))
  drafts.list.mockResolvedValue([])
  await renderResumes()
  expect(await screen.findByText('简历暂时无法加载')).toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '重试' })).toBeOnTheScreen()
})

it('offers to create a resume from the created-resume list and its empty state', async () => {
  const onCreateDraft = jest.fn()
  await renderResumes({ onCreateDraft })
  const user = userEvent.setup()
  await screen.findByText('还没有上传的简历')
  expect(screen.queryByRole('button', { name: '新建简历' })).not.toBeOnTheScreen()

  await user.press(screen.getByRole('tab', { name: '生成简历' }))
  expect(await screen.findByText('还没有生成的简历')).toBeOnTheScreen()
  const buttons = screen.getAllByRole('button', { name: '新建简历' })
  expect(buttons).toHaveLength(2)
  await user.press(buttons[0])
  await user.press(buttons[1])
  expect(onCreateDraft).toHaveBeenCalledTimes(2)
  expect(screen.queryByText(/草稿/)).not.toBeOnTheScreen()
})

it('opens the editor straight from the online resume list and explains files versus online resumes', async () => {
  drafts.list.mockResolvedValue([draftSummary(12, { title: 'Student draft' })])
  const onEditDraft = jest.fn()
  await renderResumes({ onEditDraft, onCreateDraft: jest.fn() })
  expect(await screen.findByText(/文件本身不能在线编辑/)).toBeOnTheScreen()
  const user = userEvent.setup()
  await user.press(screen.getByRole('tab', { name: '生成简历' }))
  expect(await screen.findByText('Student draft')).toBeOnTheScreen()
  expect(screen.getByText(/可以继续编辑的在线简历/)).toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '编辑简历' }))
  expect(onEditDraft).toHaveBeenCalledWith(12)
})

it('shows a failed online resume list as a failure with the HTTP status, never as empty', async () => {
  drafts.list.mockRejectedValue(new ApiError('/resume-builder/drafts', 500, { message: 'Internal server error' }))
  await renderResumes({ segmentParam: 'drafts' })
  expect(await screen.findByText('生成的简历暂时无法加载')).toBeOnTheScreen()
  expect(screen.getByText(/HTTP 500 · \/resume-builder\/drafts/)).toBeOnTheScreen()
  expect(screen.getByText(/线上服务器还没有部署在线创建简历功能/)).toBeOnTheScreen()
  expect(screen.queryByText('还没有生成的简历')).not.toBeOnTheScreen()
})

it('refuses a non-PDF/DOCX file and a file over 10MB without uploading', async () => {
  await renderResumes()
  const user = userEvent.setup()
  await screen.findByText('还没有上传的简历')

  picker.mockResolvedValueOnce({ uri: 'file:///a.png', name: 'photo.png', size: 1000, mimeType: 'image/png' })
  await user.press(screen.getAllByRole('button', { name: '上传简历' })[0])
  expect(await screen.findByText('只支持 PDF 或 DOCX 文件。')).toBeOnTheScreen()

  picker.mockResolvedValueOnce({ uri: 'file:///big.pdf', name: 'big.pdf', size: 10 * 1024 * 1024 + 1, mimeType: 'application/pdf' })
  await user.press(screen.getAllByRole('button', { name: '上传简历' })[0])
  expect(await screen.findByText('文件超过 10MB，请压缩后再上传。')).toBeOnTheScreen()

  expect(screen.queryByText('确认上传这份简历？')).not.toBeOnTheScreen()
  expect(resumes.upload).not.toHaveBeenCalled()
})

it('confirms, uploads once with progress, then shows the new file in the shared cache', async () => {
  const created = uploadResume(30, 'new-cv.pdf', '2026-09-12T00:00:00Z')
  let finish: (value: typeof created) => void = () => {}
  resumes.upload.mockImplementation((_file, onProgress) => {
    onProgress?.(0.42)
    return new Promise((resolve) => {
      finish = resolve
    })
  })
  picker.mockResolvedValue({ uri: 'file:///cache/new-cv.pdf', name: 'new-cv.pdf', size: 2048, mimeType: 'application/pdf' })
  const { client } = await renderResumes()
  const user = userEvent.setup()
  await screen.findByText('还没有上传的简历')

  await user.press(screen.getAllByRole('button', { name: '上传简历' })[0])
  expect(await screen.findByText('确认上传这份简历？')).toBeOnTheScreen()
  expect(screen.getByText('new-cv.pdf')).toBeOnTheScreen()

  await user.press(screen.getByRole('button', { name: '上传' }))
  expect(await screen.findByText('正在上传… 42%')).toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '上传' })).toBeDisabled()
  await user.press(screen.getByRole('button', { name: '上传' }))
  expect(resumes.upload).toHaveBeenCalledTimes(1)
  expect(resumes.upload.mock.calls[0][0]).toEqual({
    uri: 'file:///cache/new-cv.pdf',
    name: 'new-cv.pdf',
    size: 2048,
    mimeType: 'application/pdf',
    kind: 'pdf',
  })

  resumes.list.mockResolvedValue([created])
  finish(created)
  expect(await screen.findByText('已上传：new-cv.pdf')).toBeOnTheScreen()
  await waitFor(() => expect(client.getQueryData(resumeKeys.files(1))).toEqual([created]))
})

it('reports a failed upload and keeps the file ready to retry', async () => {
  resumes.upload.mockRejectedValue(new ApiError('/resumes', 422, { message: 'Failed to extract text' }))
  picker.mockResolvedValue({ uri: 'file:///cache/scan.pdf', name: 'scan.pdf', size: 2048, mimeType: 'application/pdf' })
  await renderResumes()
  const user = userEvent.setup()
  await screen.findByText('还没有上传的简历')

  await user.press(screen.getAllByRole('button', { name: '上传简历' })[0])
  await user.press(await screen.findByRole('button', { name: '上传' }))
  expect(await screen.findByText('上传没有成功：文件格式无法读取，请上传可提取文字的 PDF 或 DOCX。')).toBeOnTheScreen()
  expect(screen.getByText('确认上传这份简历？')).toBeOnTheScreen()
})

it('opens the picker when the dashboard asks for an upload', async () => {
  picker.mockResolvedValue(null)
  await renderResumes({ actionParam: 'upload', nonce: '1' })
  await waitFor(() => expect(picker).toHaveBeenCalledTimes(1))
})
