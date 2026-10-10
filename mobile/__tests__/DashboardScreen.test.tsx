import '../src/i18n'
import { Pressable, Text } from 'react-native'
import { screen, userEvent } from '@testing-library/react-native'
import { draftsApi, jobsApi, resumesApi } from '../src/api/endpoints'
import { useUploadResume } from '../src/resumes/queries'
import { DashboardScreen } from '../src/screens/DashboardScreen'
import { draftDetail, draftSummary, personalJob, uploadResume } from '../test-utils/fixtures'
import { createTestQueryClient, renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  resumesApi: { list: jest.fn(), upload: jest.fn() },
  draftsApi: { list: jest.fn(), get: jest.fn() },
  jobsApi: { list: jest.fn() },
  catalogApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({
    user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' },
    logout: jest.fn(),
  }),
}))

const resumes = resumesApi as jest.Mocked<typeof resumesApi>
const drafts = draftsApi as jest.Mocked<typeof draftsApi>
const jobs = jobsApi as jest.Mocked<typeof jobsApi>
const UPLOAD = uploadResume(3, '简历 (1).pdf')

async function renderDashboard(client = createTestQueryClient()) {
  const onOpen = jest.fn()
  await renderWithQuery(<DashboardScreen onOpen={onOpen} />, client)
  return onOpen
}

beforeEach(() => {
  jest.resetAllMocks()
  resumes.list.mockResolvedValue([])
  drafts.list.mockResolvedValue([])
  jobs.list.mockResolvedValue([])
})

it('shows the compact onboarding when the account has nothing yet, and its button opens the picker', async () => {
  const onOpen = await renderDashboard()
  const user = userEvent.setup()
  expect(await screen.findByText('开始你的求职工作台')).toBeOnTheScreen()
  expect(screen.queryByText(/个已收藏/)).not.toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '创建或上传简历' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'upload' })
  await user.press(screen.getByRole('link', { name: '也可以先查找职位' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'catalog' })
})

it('shows an uploaded resume with two compact empty rows, and links manage, file and score by file id', async () => {
  resumes.list.mockResolvedValue([UPLOAD, uploadResume(4, 'second.docx', '2026-09-01T00:00:00Z')])
  const onOpen = await renderDashboard()
  const user = userEvent.setup()

  expect(await screen.findByText('简历 (1).pdf')).toBeOnTheScreen()
  expect(screen.getByText('还没有收藏的职位。')).toBeOnTheScreen()
  expect(screen.getByText('还没有投递记录。')).toBeOnTheScreen()

  await user.press(screen.getByRole('link', { name: '管理全部' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'resumes' })
  await user.press(screen.getByRole('link', { name: '查看简历质量评分' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'quality', resumeId: 3 })
  await user.press(screen.getByRole('link', { name: 'second.docx' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'resumeFile', resumeId: 4 })
})

it('never offers a quality score for a draft on its own, and opens the draft by its draft id', async () => {
  drafts.list.mockResolvedValue([draftSummary(3, { title: 'Student draft' })])
  drafts.get.mockResolvedValue(draftDetail(3, { title: 'Student draft' }))
  const onOpen = await renderDashboard()

  expect(await screen.findByText('Student draft')).toBeOnTheScreen()
  expect(screen.queryByRole('link', { name: '查看简历质量评分' })).not.toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('button', { name: '编辑简历' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'draft', draftId: 3 })
})

it('opens saved and applied records by their personal id and links "view all" to each list', async () => {
  resumes.list.mockResolvedValue([UPLOAD])
  jobs.list.mockResolvedValue([
    personalJob(11, { title: 'Saved One' }),
    personalJob(12, { title: 'Saved Two' }),
    personalJob(13, { title: 'Saved Three' }),
    personalJob(14, { title: 'Saved Four' }),
    personalJob(21, { title: 'Applied One', applied: true, appliedAt: '2026-09-10T12:00:00' }),
  ])
  const onOpen = await renderDashboard()
  const user = userEvent.setup()

  expect(await screen.findByText('4 个已收藏')).toBeOnTheScreen()
  await user.press(screen.getByRole('link', { name: 'Saved One' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'job', jobId: 11 })
  await user.press(screen.getByRole('link', { name: 'Applied One' }))
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'job', jobId: 21 })
  const viewAll = screen.getAllByRole('link', { name: '查看全部' })
  await user.press(viewAll[0])
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'saved' })
  await user.press(viewAll[1])
  expect(onOpen).toHaveBeenLastCalledWith({ kind: 'applied' })
  expect(jobs.list).toHaveBeenCalledTimes(1)
})

it('says the resume request failed instead of showing it as empty', async () => {
  resumes.list.mockRejectedValue(new Error('offline'))
  jobs.list.mockResolvedValue([personalJob(11, { title: 'Saved One' })])
  await renderDashboard()
  expect(await screen.findByText('简历暂时无法加载。')).toBeOnTheScreen()
  expect(screen.queryByText('还没有简历。')).not.toBeOnTheScreen()
})

/** Stands in for the resumes tab: the same upload hook, on the same query client. */
function UploadProbe() {
  const upload = useUploadResume(() => {})
  return (
    <Pressable
      role="button"
      aria-label="probe upload"
      onPress={() =>
        void upload.mutateAsync({ uri: 'file:///x.pdf', name: 'fresh-upload.pdf', size: 10, mimeType: 'application/pdf', kind: 'pdf' })
      }
    >
      <Text>probe upload</Text>
    </Pressable>
  )
}

it('updates immediately after an upload refreshes the shared resume cache', async () => {
  const onOpen = jest.fn()
  await renderWithQuery(
    <>
      <DashboardScreen onOpen={onOpen} />
      <UploadProbe />
    </>,
  )
  expect(await screen.findByText('开始你的求职工作台')).toBeOnTheScreen()

  const created = uploadResume(40, 'fresh-upload.pdf', '2026-09-12T00:00:00Z')
  resumes.upload.mockResolvedValue(created)
  resumes.list.mockResolvedValue([created])
  await userEvent.setup().press(screen.getByRole('button', { name: 'probe upload' }))

  expect(await screen.findByText('fresh-upload.pdf')).toBeOnTheScreen()
  expect(screen.queryByText('开始你的求职工作台')).not.toBeOnTheScreen()
})
