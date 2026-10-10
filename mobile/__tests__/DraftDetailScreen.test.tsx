import '../src/i18n'
import { Alert } from 'react-native'
import * as SecureStore from 'expo-secure-store'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { draftsApi, resumesApi } from '../src/api/endpoints'
import { resumeKeys } from '../src/resumes/queries'
import { DraftDetailScreen } from '../src/screens/DraftDetailScreen'
import { draftDetail, draftSummary } from '../test-utils/fixtures'
import { createTestQueryClient, renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  draftsApi: { get: jest.fn(), remove: jest.fn(), list: jest.fn() },
  resumesApi: { qualityScore: jest.fn(), get: jest.fn() },
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('expo-secure-store', () => {
  const mockValues = new Map<string, string>()
  return {
    getItemAsync: async (key: string) => mockValues.get(key) ?? null,
    setItemAsync: async (key: string, value: string) => void mockValues.set(key, value),
    deleteItemAsync: async (key: string) => void mockValues.delete(key),
  }
})

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const drafts = draftsApi as jest.Mocked<typeof draftsApi>
const resumes = resumesApi as jest.Mocked<typeof resumesApi>

function answerDialogs(confirm: boolean) {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    const button = confirm ? buttons?.[buttons.length - 1] : buttons?.[0]
    button?.onPress?.()
  })
}

function renderDetail(props: Partial<Parameters<typeof DraftDetailScreen>[0]> = {}, client = createTestQueryClient()) {
  const handlers = { onBack: jest.fn(), onEdit: jest.fn(), onDeleted: jest.fn() }
  return renderWithQuery(<DraftDetailScreen draftId={7} {...handlers} {...props} />, client).then((c) => ({ ...handlers, client: c }))
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
})

it('shows the real draft fields and opens the editor, using only the draft route', async () => {
  drafts.get.mockResolvedValue(draftDetail(7, { title: 'Student draft' }))
  const { onEdit } = await renderDetail()

  expect(await screen.findByText('Student draft')).toBeOnTheScreen()
  expect(drafts.get).toHaveBeenCalledWith(7)
  expect(screen.getByText('学生模板')).toBeOnTheScreen()
  expect(screen.getAllByText('Junior Analyst').length).toBeGreaterThan(0)
  expect(screen.getByText('person@example.test')).toBeOnTheScreen()
  expect(screen.getByText('2 项')).toBeOnTheScreen()
  expect(screen.queryByText('简历生成器将在下一阶段完成')).not.toBeOnTheScreen()

  await userEvent.setup().press(screen.getByRole('button', { name: '编辑简历' }))
  expect(onEdit).toHaveBeenCalledWith(7)
  expect(resumes.qualityScore).not.toHaveBeenCalled()
  expect(resumes.get).not.toHaveBeenCalled()
})

it('deletes only after confirmation, and updates the shared list', async () => {
  drafts.get.mockResolvedValue(draftDetail(7))
  const client = createTestQueryClient()
  client.setQueryData(resumeKeys.drafts(1), [draftSummary(7), draftSummary(8)])
  drafts.list.mockResolvedValue([draftSummary(8)])
  answerDialogs(false)
  const { onDeleted } = await renderDetail({}, client)
  const user = userEvent.setup()
  await screen.findByText('Draft 7')

  await user.press(screen.getByRole('button', { name: '删除' }))
  expect(drafts.remove).not.toHaveBeenCalled()

  jest.restoreAllMocks()
  const alert = answerDialogs(true)
  drafts.remove.mockResolvedValueOnce(undefined)
  await SecureStore.setItemAsync('aijob.generate.1.7', JSON.stringify({ idempotencyKey: 'k', draftVersion: 2 }))
  await user.press(screen.getByRole('button', { name: '删除' }))
  expect(alert).toHaveBeenCalledWith(
    '删除这份简历？',
    '填写的内容删除后无法恢复。已经生成的简历文件不会被删除。',
    expect.any(Array),
    expect.anything(),
  )
  await waitFor(() => expect(onDeleted).toHaveBeenCalled())
  expect(drafts.remove).toHaveBeenCalledWith(7)
  expect(client.getQueryData(resumeKeys.drafts(1))).toEqual([draftSummary(8)])
  expect(await SecureStore.getItemAsync('aijob.generate.1.7')).toBeNull()
})

it('reports a failed delete and stays', async () => {
  drafts.get.mockResolvedValue(draftDetail(7))
  drafts.remove.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7', 502, null))
  answerDialogs(true)
  const { onDeleted } = await renderDetail()
  await screen.findByText('Draft 7')
  await userEvent.setup().press(screen.getByRole('button', { name: '删除' }))
  expect(await screen.findByText('删除没有成功：服务暂时不可用，请稍后再试。')).toBeOnTheScreen()
  expect(onDeleted).not.toHaveBeenCalled()
})

it('distinguishes a missing draft from a failed request', async () => {
  drafts.get.mockRejectedValue(new ApiError('/resume-builder/drafts/7', 404, null))
  await renderDetail()
  expect(await screen.findByText('找不到这份生成的简历')).toBeOnTheScreen()

  jest.resetAllMocks()
  drafts.get.mockRejectedValue(new ApiError('/resume-builder/drafts/8', 502, null))
  await renderDetail({ draftId: 8 })
  expect(await screen.findByText('生成的简历暂时无法加载')).toBeOnTheScreen()
})
